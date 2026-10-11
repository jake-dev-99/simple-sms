package io.simplezen.simple_sms.messaging

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs [DELIVERY_REPORT_SELECTION] as real SQL against an `sms` table, the way
 * the SMS provider applies a selection to a row URI (`_id = N AND (selection)`),
 * so the guard's text — not just its arguments — is what is tested (UNFY-293).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeliveryReportSelectionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: SqliteSmsProvider

    @Before
    fun setUp() {
        provider = Robolectric.buildContentProvider(SqliteSmsProvider::class.java).create("sms").get()
    }

    @Test
    fun outboundRowWithoutAFailure_takesTheStatus() {
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_SENT, status = Telephony.Sms.STATUS_PENDING)
        insert(id = 2, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, status = null)

        assertEquals(1, report(1, Telephony.Sms.STATUS_COMPLETE))
        assertEquals(1, report(2, Telephony.Sms.STATUS_FAILED))
        assertEquals(Telephony.Sms.STATUS_COMPLETE, provider.status(1))
        assertEquals(Telephony.Sms.STATUS_FAILED, provider.status(2))
    }

    @Test
    fun aRecordedFailure_isNeverOverwritten() {
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_SENT, status = Telephony.Sms.STATUS_FAILED)

        assertEquals(0, report(1, Telephony.Sms.STATUS_COMPLETE))
        assertEquals(Telephony.Sms.STATUS_FAILED, provider.status(1))
    }

    @Test
    fun inboundOrSendFailedRows_areNeverStamped() {
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = Telephony.Sms.STATUS_NONE)
        insert(id = 2, type = Telephony.Sms.MESSAGE_TYPE_FAILED, status = Telephony.Sms.STATUS_PENDING)

        assertEquals(0, report(1, Telephony.Sms.STATUS_COMPLETE))
        assertEquals(0, report(2, Telephony.Sms.STATUS_COMPLETE))
        assertEquals(Telephony.Sms.STATUS_NONE, provider.status(1))
        assertEquals(Telephony.Sms.STATUS_PENDING, provider.status(2))
    }

    private fun insert(id: Long, type: Int, status: Int?) {
        provider.db.insertOrThrow("sms", null, ContentValues().apply {
            put("_id", id)
            put(Telephony.Sms.TYPE, type)
            if (status == null) putNull(Telephony.Sms.STATUS) else put(Telephony.Sms.STATUS, status)
        })
    }

    private fun report(id: Long, status: Int): Int =
        persistSmsDeliveryStatus(context, ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), status)

    /** A minimal SQLite-backed `sms` provider applying selections like the platform's. */
    class SqliteSmsProvider : ContentProvider() {
        val db: SQLiteDatabase = SQLiteDatabase.create(null).apply {
            execSQL(
                "CREATE TABLE sms (_id INTEGER PRIMARY KEY, type INTEGER, status INTEGER DEFAULT -1, " +
                    "creator TEXT, date INTEGER)",
            )
        }

        fun status(id: Long): Int? = db.rawQuery("SELECT status FROM sms WHERE _id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }

        fun type(id: Long): Int? = db.rawQuery("SELECT type FROM sms WHERE _id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }

        override fun onCreate(): Boolean = true

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = ContentUris.parseId(uri)
            val where = if (id < 0) selection else DatabaseUtils.concatenateWhere("_id = $id", selection)
            return db.update("sms", values, where, selectionArgs)
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
}
