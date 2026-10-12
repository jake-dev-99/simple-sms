package io.simplezen.simple_sms.messaging

import android.content.Context
import android.net.Uri
import android.app.Activity
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import io.simplezen.simple_sms.messaging.DeliveryReportSelectionTest.SqliteSmsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins [OwedRowWrites] (UNFY-335): what it keeps of the status writes that
 * did not land, and when it gives one up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OwedRowWritesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.filesDir, "owed_row_writes_test")
    private var now = System.currentTimeMillis()
    private lateinit var provider: SqliteSmsProvider

    /** A store over the same files, as a new app process would build it. */
    private fun owed() = OwedRowWrites(directory) { now }

    @Before
    fun setUp() {
        provider = Robolectric.buildContentProvider(SqliteSmsProvider::class.java).create("sms").get()
        provider.db.execSQL("INSERT INTO sms (_id, type) VALUES (1, ${Telephony.Sms.MESSAGE_TYPE_OUTBOX})")
        provider.ignoreWrites = true
    }

    @Test
    fun aLaterWriteOfTheSameColumns_replacesTheEarlierOne() {
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_FAILED))
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        provider.ignoreWrites = false

        assertEquals(1, owed().retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(1))
    }

    @Test
    fun aRowOwesOneResultWrite_whateverColumnsEachSets() {
        // An MMS failure sets the box alone, a success more columns.
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_FAILED))
        owed().owe(
            statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT).let {
                it.copy(values = it.values + (Telephony.Sms.ERROR_CODE to 0L))
            },
        )

        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test
    fun anOwedFailure_isNeverReplacedByALaterResult() {
        // A part sent before parts were tracked failed, then another part
        // was sent: the failure is final.
        owed().owe(sendResultWrite(Uri.parse(smsUri(1)), SENTSMS_ACTION, SmsManager.RESULT_ERROR_NO_SERVICE))
        owed().owe(sendResultWrite(Uri.parse(smsUri(1)), SENTSMS_ACTION, Activity.RESULT_OK))
        provider.ignoreWrites = false

        assertEquals(1, owed().retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, provider.type(1))
        assertEquals(SmsManager.RESULT_ERROR_NO_SERVICE, provider.errorCode(1))
    }

    @Test
    fun aRowsSendResult_landsBeforeItsDeliveryStatus() {
        // Failed meanwhile for lack of a result; the delivery status needs
        // the row sent again first.
        provider.db.execSQL(
            "UPDATE sms SET type = ${Telephony.Sms.MESSAGE_TYPE_FAILED}, " +
                "error_code = $NO_SENT_RESULT_ERROR_CODE WHERE _id = 1",
        )
        owed().owe(
            RowWrite(
                smsUri(1),
                RowWriteKind.DELIVERY_STATUS,
                mapOf(Telephony.Sms.STATUS to Telephony.Sms.STATUS_COMPLETE.toLong()),
                DELIVERY_REPORT_SELECTION,
                DELIVERY_REPORT_ARGS.toList(),
            ),
        )
        owed().owe(sendResultWrite(Uri.parse(smsUri(1)), SENTSMS_ACTION, Activity.RESULT_OK))
        provider.ignoreWrites = false

        assertEquals(2, owed().retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(1))
        assertEquals(Telephony.Sms.STATUS_COMPLETE, provider.status(1))
    }

    @Test
    fun aResultWriteAndADeliveryWrite_areKeptSideBySide() {
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        owed().owe(deliveryWrite(1, Telephony.Sms.STATUS_COMPLETE))
        provider.ignoreWrites = false

        assertEquals(2, owed().retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, provider.type(1))
        assertEquals(Telephony.Sms.STATUS_COMPLETE, provider.status(1))
    }

    @Test
    fun aWriteOwedForTheWholeWindow_isGivenUp() {
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        now += DELIVERY_REPORT_WAIT_MS + 1
        provider.ignoreWrites = false

        assertEquals(0, owed().retry(context))

        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, provider.type(1))
        assertTrue(owed().rows().isEmpty())
    }

    @Test
    fun anUndecodableWrite_isDropped() {
        owed().owe(statusWrite(1, Telephony.Sms.MESSAGE_TYPE_SENT))
        directory.listFiles()!!.single().writeText("{ not json")

        assertTrue(owed().rows().isEmpty())
        assertEquals(0, owed().retry(context))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun statusWrite(id: Long, type: Int) = RowWrite(
        smsUri(id),
        RowWriteKind.SEND_RESULT,
        mapOf(Telephony.Sms.TYPE to type.toLong()),
        "${Telephony.Sms.TYPE} = ?",
        listOf(Telephony.Sms.MESSAGE_TYPE_OUTBOX.toString()),
    )

    private fun deliveryWrite(id: Long, status: Int) = RowWrite(
        smsUri(id),
        RowWriteKind.DELIVERY_STATUS,
        mapOf(Telephony.Sms.STATUS to status.toLong()),
        "${Telephony.Sms.STATUS} != ?",
        listOf(Telephony.Sms.STATUS_FAILED.toString()),
    )

    private fun smsUri(id: Long): String = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString()).toString()
}
