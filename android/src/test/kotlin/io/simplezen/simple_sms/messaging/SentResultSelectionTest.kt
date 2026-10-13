package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import io.simplezen.simple_sms.messaging.DeliveryReportSelectionTest.SqliteSmsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun carrierFailureAfterATimeout_isNotRecoveredByALaterSuccess() {
        // Timed out (marker), then a real failed part, then a successful part.
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_FAILED, errorCode = NO_SENT_RESULT_ERROR_CODE)

        result(1, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        result(1, Activity.RESULT_OK)

        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, provider.type(1))
        assertEquals(SmsManager.RESULT_ERROR_GENERIC_FAILURE, provider.errorCode(1))
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
    fun failure_neverOverwritesAnInboundOrDraftRow_orACarrierFailure() {
        // Rows that reused a deleted message's id; a row a carrier failure
        // already marked failed (UNFY-335).
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_INBOX)
        insert(id = 2, type = Telephony.Sms.MESSAGE_TYPE_DRAFT)
        insert(id = 3, type = Telephony.Sms.MESSAGE_TYPE_FAILED, errorCode = SmsManager.RESULT_ERROR_NO_SERVICE)

        assertEquals(RowWriteOutcome.NOT_APPLICABLE, result(1, SmsManager.RESULT_ERROR_GENERIC_FAILURE))
        assertEquals(RowWriteOutcome.NOT_APPLICABLE, result(2, SmsManager.RESULT_ERROR_GENERIC_FAILURE))
        assertEquals(RowWriteOutcome.NOT_APPLICABLE, result(3, SmsManager.RESULT_ERROR_GENERIC_FAILURE))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_INBOX, provider.type(1))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_DRAFT, provider.type(2))
        assertEquals(SmsManager.RESULT_ERROR_NO_SERVICE, provider.errorCode(3))
    }

    @Test
    fun mmsResults_moveOnlyAnOutboxRow_orOneFailedForLackOfAResult() {
        mms.insert(id = 1, box = Telephony.Mms.MESSAGE_BOX_OUTBOX, date = 0)
        mms.insert(id = 2, box = Telephony.Mms.MESSAGE_BOX_FAILED, date = 0) // timed out, then the success came
        mms.insert(id = 3, box = Telephony.Mms.MESSAGE_BOX_INBOX, date = 0) // reused a deleted message's id
        mms.insert(id = 4, box = Telephony.Mms.MESSAGE_BOX_OUTBOX, date = 0)
        mms.insert(id = 5, box = Telephony.Mms.MESSAGE_BOX_INBOX, date = 0)

        mmsResult(1, Activity.RESULT_OK)
        mmsResult(2, Activity.RESULT_OK)
        mmsResult(3, Activity.RESULT_OK)
        mmsResult(4, SmsManager.MMS_ERROR_IO_ERROR)
        mmsResult(5, SmsManager.MMS_ERROR_IO_ERROR)

        assertEquals(Telephony.Mms.MESSAGE_BOX_SENT, mms.box(1))
        assertEquals(Telephony.Mms.MESSAGE_BOX_SENT, mms.box(2))
        assertEquals(Telephony.Mms.MESSAGE_BOX_INBOX, mms.box(3))
        assertEquals(Telephony.Mms.MESSAGE_BOX_FAILED, mms.box(4))
        assertEquals(Telephony.Mms.MESSAGE_BOX_INBOX, mms.box(5))
    }

    @Test
    fun mmsSuccess_recordsTheMmscMessageIdWithItsResult() {
        // The id links the MMS's delivery report to its row (UNFY-110).
        mms.insert(id = 1, box = Telephony.Mms.MESSAGE_BOX_OUTBOX, date = 0)
        mms.insert(id = 2, box = Telephony.Mms.MESSAGE_BOX_OUTBOX, date = 0)
        mms.insert(id = 3, box = Telephony.Mms.MESSAGE_BOX_OUTBOX, date = 0)
        mms.insert(id = 4, box = Telephony.Mms.MESSAGE_BOX_INBOX, date = 0)

        mmsResult(1, Activity.RESULT_OK, mmsMessageId = "mid-1")
        mmsResult(2, Activity.RESULT_OK, mmsMessageId = null)
        mmsResult(3, SmsManager.MMS_ERROR_IO_ERROR, mmsMessageId = "mid-3")
        mmsResult(4, Activity.RESULT_OK, mmsMessageId = "mid-4")

        assertEquals("mid-1", mms.messageId(1))
        assertEquals(Telephony.Mms.MESSAGE_BOX_SENT, mms.box(2))
        assertNull(mms.messageId(2))
        assertNull("a failed send records no id", mms.messageId(3))
        assertNull("a row the guard spares takes no id", mms.messageId(4))
    }

    @Test
    fun aSendResultWrite_carriesTheMessageIdOnlyForASuccessfulMms() {
        val mmsUri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, 1)
        assertEquals(
            mapOf(Telephony.Mms.MESSAGE_ID to "mid-1"),
            sendResultWrite(mmsUri, SENTMMS_ACTION, Activity.RESULT_OK, "mid-1").textValues,
        )
        assertTrue(sendResultWrite(mmsUri, SENTMMS_ACTION, SmsManager.MMS_ERROR_IO_ERROR, "mid-1").textValues.isEmpty())
        assertTrue(sendResultWrite(smsUri(1), SENTSMS_ACTION, Activity.RESULT_OK, "mid-1").textValues.isEmpty())
    }

    @Test
    fun aResultTheProviderIgnores_isOwed_andLandsOnceTheAppMayWriteAgain() {
        // The app lost the default-SMS role or its WRITE_SMS app-op (UNFY-335).
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, date = 1_000)
        provider.ignoreWrites = true

        assertEquals(RowWriteOutcome.IGNORED, result(1, Activity.RESULT_OK))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(1))
        val owed = OwedRowWrites.forApp(context)
        assertEquals(0, owed.retry(context))
        assertEquals("still ignored, still owed", setOf(smsUri(1)), owed.rows())

        provider.ignoreWrites = false
        assertEquals(1, owed.retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(1))
        assertTrue(owed.rows().isEmpty())
    }

    @Test
    fun aResultWriteThatFails_isOwed_andDroppedOnceItNoLongerApplies() {
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, date = 1_000)
        provider.rowWriteFailure = IllegalStateException("provider down")

        assertThrows(IllegalStateException::class.java) { result(1, Activity.RESULT_OK) }
        val owed = OwedRowWrites.forApp(context)
        assertEquals(setOf(smsUri(1)), owed.rows())

        // Meanwhile a new inbound message reused the deleted row's id.
        provider.rowWriteFailure = null
        provider.db.execSQL("UPDATE sms SET type = ${Telephony.Sms.MESSAGE_TYPE_INBOX} WHERE _id = 1")
        assertEquals(0, owed.retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_INBOX, provider.type(1))
        assertTrue(owed.rows().isEmpty())
    }

    @Test
    fun aResult_onlyEverWritesItsOwnRow() {
        // The provider may join a guard to its row with a bare AND (AOSP
        // MmsProvider does), so a guard must hold up on its own.
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        insert(id = 2, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        insert(id = 3, type = Telephony.Sms.MESSAGE_TYPE_SENT)

        result(1, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(2))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(3))

        insert(id = 4, type = Telephony.Sms.MESSAGE_TYPE_FAILED, errorCode = NO_SENT_RESULT_ERROR_CODE)
        insert(id = 5, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        result(2, Activity.RESULT_OK)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(2))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, provider.type(4))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(5))
    }

    @Test
    fun aDeliveryReport_forARowWhoseSendResultIsOwed_waitsForIt() {
        // Failed for lack of a result, though its result came and is owed:
        // the delivery status needs the row sent first (UNFY-335).
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_FAILED, date = 1_000, errorCode = NO_SENT_RESULT_ERROR_CODE)
        val owed = OwedRowWrites.forApp(context)
        owed.owe(sendResultWrite(smsUri(1), SENTSMS_ACTION, Activity.RESULT_OK))

        assertEquals(
            RowWriteOutcome.NOT_APPLICABLE,
            persistSmsDeliveryStatus(context, smsUri(1), Telephony.Sms.STATUS_COMPLETE),
        )
        assertEquals(2, owed.retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(1))
        assertEquals(Telephony.Sms.STATUS_COMPLETE, provider.status(1))
    }

    @Test
    fun aWriteThatFails_forARowThatIsGone_isNotKept() {
        provider.rowWriteFailure = IllegalStateException("provider down")

        assertThrows(IllegalStateException::class.java) { result(1, Activity.RESULT_OK) }

        assertTrue(OwedRowWrites.forApp(context).rows().isEmpty())
    }

    @Test
    fun anOwedWrite_neverLandsOnARowThatReusedItsId() {
        // Deleted while its result was owed; this app's next send, or the app
        // that holds the default-SMS role now, reused its id (UNFY-335).
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, date = 1_000)
        provider.ignoreWrites = true
        assertEquals(RowWriteOutcome.IGNORED, result(1, SmsManager.RESULT_ERROR_GENERIC_FAILURE))
        provider.ignoreWrites = false
        provider.db.execSQL("DELETE FROM sms WHERE _id = 1")
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, date = 2_000)

        assertEquals(0, OwedRowWrites.forApp(context).retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(1))
        assertTrue(OwedRowWrites.forApp(context).rows().isEmpty())
    }

    @Test
    fun aSweep_recordsAnOwedResult_ratherThanFailingItsRow() {
        val overdue = System.currentTimeMillis() - STALE_OUTBOX_AGE_MS - 1
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, date = overdue) // its success is owed
        insert(id = 2, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, date = overdue) // its result never came
        OwedRowWrites.forApp(context).owe(sendResultWrite(smsUri(1), SENTSMS_ACTION, Activity.RESULT_OK))

        OutboxSweeper.sweepOnce(context, pending = emptySet())

        assertEquals("sent, not failed for lack of a result", Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(1))
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, provider.type(2))
    }

    @Test
    fun aRowWhoseOwedWriteStillFails_isSwept_andStillRecordedOnceItLands() {
        // The sweep's bound holds: no row shows as sending for days (UNFY-299).
        val overdue = System.currentTimeMillis() - STALE_OUTBOX_AGE_MS - 1
        insert(id = 1, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, date = overdue)
        OwedRowWrites.forApp(context).owe(sendResultWrite(smsUri(1), SENTSMS_ACTION, Activity.RESULT_OK))
        provider.rowWriteFailure = IllegalStateException("provider down")

        OutboxSweeper.sweepOnce(context, pending = emptySet())

        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, provider.type(1))
        assertEquals(NO_SENT_RESULT_ERROR_CODE, provider.errorCode(1))

        provider.rowWriteFailure = null
        OutboxSweeper.sweepOnce(context, pending = emptySet())

        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(1))
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
            execSQL(
                "CREATE TABLE pdu (_id INTEGER PRIMARY KEY, msg_box INTEGER, creator TEXT, date INTEGER, " +
                    "read INTEGER, seen INTEGER, date_sent INTEGER, m_id TEXT)",
            )
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

        fun messageId(id: Long): String? = db.rawQuery("SELECT m_id FROM pdu WHERE _id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }

        override fun onCreate(): Boolean = true

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            db.update("pdu", values, rowWhere(uri, selection), selectionArgs)

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = db.query("pdu", projection, rowWhere(uri, selection), selectionArgs, null, null, sortOrder)

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private fun smsUri(id: Long): Uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)

    private fun mmsResult(id: Long, resultCode: Int, mmsMessageId: String? = null) = recordSendResult(
        context,
        id.toInt(),
        ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id),
        SENTMMS_ACTION,
        "test",
        resultCode,
        mmsMessageId,
    )

    private fun result(id: Long, resultCode: Int) = recordSendResult(
        context,
        id.toInt(),
        ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
        SENTSMS_ACTION,
        "test",
        resultCode,
        null,
    )
}
