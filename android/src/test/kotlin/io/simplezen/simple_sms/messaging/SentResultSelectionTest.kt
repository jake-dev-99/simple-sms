package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import io.simplezen.simple_sms.messaging.DeliveryReportSelectionTest.SqliteSmsProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs [recordSendResult]'s SMS writes and [markStaleOutboxFailed] as real SQL
 * against `sms` and `pdu` tables (UNFY-299): a success moves only a row still
 * on its way out; a failure always marks the row failed; the sweep fails only
 * this app's overdue outbox rows that no live handler awaits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SentResultSelectionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: SqliteSmsProvider
    private lateinit var mms: SqliteMmsProvider

    @Before
    fun setUp() {
        provider = Robolectric.buildContentProvider(SqliteSmsProvider::class.java).create("sms").get()
        mms = Robolectric.buildContentProvider(SqliteMmsProvider::class.java).create("mms").get()
    }

    @Test
    fun success_movesAnOutboxOrQueuedRowToSent() {
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        insert(id = 2, type = Telephony.Sms.MESSAGE_TYPE_QUEUED)

        result(1, Activity.RESULT_OK)
        result(2, Activity.RESULT_OK)

        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(1))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(2))
    }

    @Test
    fun success_neverMovesAFailedOrInboundRow() {
        // A later multipart part after a failed one; an inbound row that
        // reused a deleted message's id.
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_FAILED)
        insert(id = 2, type = Telephony.Sms.MESSAGE_TYPE_INBOX)

        result(1, Activity.RESULT_OK)
        result(2, Activity.RESULT_OK)

        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, provider.type(1))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_INBOX, provider.type(2))
    }

    @Test
    fun success_recoversARowFailedOnlyForLackOfAResult() {
        // Timed out or swept, then the carrier's success arrived after all.
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_FAILED, errorCode = NO_SENT_RESULT_ERROR_CODE)

        result(1, Activity.RESULT_OK)

        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(1))
        assertEquals(0, provider.errorCode(1))
    }

    @Test
    fun timedOutRow_isFailedWithTheNoResultMarker_onlyWhileOnItsWayOut() {
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        insert(id = 2, type = Telephony.Sms.MESSAGE_TYPE_SENT) // a result already settled it
        mms.insert(id = 1, box = Telephony.Mms.MESSAGE_BOX_OUTBOX, date = 0)
        mms.insert(id = 2, box = Telephony.Mms.MESSAGE_BOX_SENT, date = 0)

        assertEquals(1, markOutboxRowFailed(context, Uri.parse("content://sms/1")))
        assertEquals(0, markOutboxRowFailed(context, Uri.parse("content://sms/2")))
        assertEquals(1, markOutboxRowFailed(context, Uri.parse("content://mms/1")))
        assertEquals(0, markOutboxRowFailed(context, Uri.parse("content://mms/2")))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, provider.type(1))
        assertEquals(NO_SENT_RESULT_ERROR_CODE, provider.errorCode(1))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(2))
        assertEquals(Telephony.Mms.MESSAGE_BOX_FAILED, mms.box(1))
        assertEquals(Telephony.Mms.MESSAGE_BOX_SENT, mms.box(2))
    }

    @Test
    fun failure_marksTheRowFailed_evenAfterAnEarlierPartWasSent() {
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_SENT)

        result(1, SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, provider.type(1))
    }

    @Test
    fun staleOutboxSweep_failsOnlyThisAppsOverdueUnownedOutboxRows() {
        val now = 100 * 60 * 1000L
        val overdue = now - STALE_OUTBOX_AGE_MS - 1
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, date = overdue)
        insert(id = 2, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, date = now - 1_000) // still in flight
        insert(id = 3, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, date = overdue, creator = "other.app")
        insert(id = 4, type = Telephony.Sms.MESSAGE_TYPE_SENT, date = overdue)
        insert(id = 5, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, date = overdue) // a live handler awaits it
        insert(id = 6, type = Telephony.Sms.MESSAGE_TYPE_QUEUED, date = overdue)
        // MMS dates are in seconds.
        mms.insert(id = 1, box = Telephony.Mms.MESSAGE_BOX_OUTBOX, date = overdue / 1000 - 1)
        mms.insert(id = 2, box = Telephony.Mms.MESSAGE_BOX_OUTBOX, date = (now - 1_000) / 1000)

        val failed = markStaleOutboxFailed(context, owned = listOf(Uri.parse("content://sms/5")), now = now)

        assertEquals(3, failed)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, provider.type(1))
        assertEquals(NO_SENT_RESULT_ERROR_CODE, provider.errorCode(1))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(2))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(3))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(4))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(5))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, provider.type(6))
        assertEquals(Telephony.Mms.MESSAGE_BOX_FAILED, mms.box(1))
        assertEquals(Telephony.Mms.MESSAGE_BOX_OUTBOX, mms.box(2))
    }

    private fun insert(
        id: Long,
        type: Int,
        date: Long = 0,
        creator: String = context.packageName,
        errorCode: Int = 0,
    ) {
        provider.db.insertOrThrow("sms", null, ContentValues().apply {
            put("_id", id)
            put(Telephony.Sms.TYPE, type)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.CREATOR, creator)
            put(Telephony.Sms.ERROR_CODE, errorCode)
        })
    }

    /** A minimal SQLite-backed `mms` (pdu) provider applying selections like the platform's. */
    class SqliteMmsProvider : ContentProvider() {
        val db: SQLiteDatabase = SQLiteDatabase.create(null).apply {
            execSQL("CREATE TABLE pdu (_id INTEGER PRIMARY KEY, msg_box INTEGER, creator TEXT, date INTEGER)")
        }

        fun insert(id: Long, box: Int, date: Long, creator: String = ApplicationProvider.getApplicationContext<Context>().packageName) {
            db.insertOrThrow("pdu", null, ContentValues().apply {
                put("_id", id)
                put(Telephony.Mms.MESSAGE_BOX, box)
                put(Telephony.Mms.DATE, date)
                put(Telephony.Mms.CREATOR, creator)
            })
        }

        fun box(id: Long): Int? = db.rawQuery("SELECT msg_box FROM pdu WHERE _id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }

        override fun onCreate(): Boolean = true

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = ContentUris.parseId(uri)
            val where = if (id < 0) selection else DatabaseUtils.concatenateWhere("_id = $id", selection)
            return db.update("pdu", values, where, selectionArgs)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private fun result(id: Long, resultCode: Int) = recordSendResult(
        context,
        id.toInt(),
        ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
        SENTSMS_ACTION,
        "test",
        resultCode,
    )
}
