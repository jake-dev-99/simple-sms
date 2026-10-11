package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins [SmsPartLedger] (UNFY-180): a multipart SMS settles only once every
 * part reported its SENT result, failed if any part failed; its delivery
 * status comes from every part's report; and the parts add up across ledger
 * instances, as across app processes.
 *
 * Robolectric for `android.util.AtomicFile`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsPartLedgerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.filesDir, "sms_part_ledger_test")
    private var now = System.currentTimeMillis()
    private val ok = Activity.RESULT_OK
    private val failure = SmsManager.RESULT_ERROR_GENERIC_FAILURE

    /** A ledger over the same files, as a new app process would build it. */
    private fun ledger() = SmsPartLedger(directory) { now }

    private fun start(parts: Int, rowId: Long = 41L): List<SmsPartRef> = ledger().start(rowId, parts)

    @Test
    fun singlePart_settlesOnItsOnlyReport() {
        val parts = start(parts = 1)
        assertEquals(SentPartProgress.Settled(ok), ledger().recordSent(parts[0], ok))
    }

    @Test
    fun multipart_waitsForEveryPart() {
        val parts = start(parts = 3)
        assertEquals(SentPartProgress.AwaitingParts, ledger().recordSent(parts[0], ok))
        assertEquals(SentPartProgress.AwaitingParts, ledger().recordSent(parts[2], ok))
        assertEquals(SentPartProgress.Settled(ok), ledger().recordSent(parts[1], ok))
    }

    @Test
    fun multipart_successThenFailure_settlesAfterTheLastPart_asFailed() {
        val parts = start(parts = 2)
        assertEquals(SentPartProgress.AwaitingParts, ledger().recordSent(parts[0], ok))
        assertEquals(SentPartProgress.Settled(failure), ledger().recordSent(parts[1], failure))
    }

    @Test
    fun multipart_failureThenSuccess_settlesAfterTheLastPart_asFailed() {
        // The bug: the first part's report used to decide the whole message.
        val parts = start(parts = 2)
        assertEquals(SentPartProgress.AwaitingParts, ledger().recordSent(parts[0], failure))
        assertEquals(SentPartProgress.Settled(failure), ledger().recordSent(parts[1], ok))
    }

    @Test
    fun aRepeatedPart_isIgnored() {
        val parts = start(parts = 2)
        ledger().recordSent(parts[0], ok)
        assertEquals(SentPartProgress.DuplicatePart, ledger().recordSent(parts[0], failure))
    }

    @Test
    fun aFailedSend_isForgotten_soLaterReportsAreIgnored() {
        val parts = start(parts = 1)
        ledger().recordSent(parts[0], failure)

        assertEquals(SentPartProgress.UnknownSend, ledger().recordSent(parts[0], ok))
        assertNull(ledger().recordDelivery(parts[0], Telephony.Sms.STATUS_COMPLETE, SmsMessage.FORMAT_3GPP))
    }

    @Test
    fun deliveryStatus_isPendingUntilEveryPartIsComplete() {
        val parts = start(parts = 2)
        ledger().recordSent(parts[0], ok)
        ledger().recordSent(parts[1], ok)

        assertEquals(Telephony.Sms.STATUS_PENDING, ledger().recordDelivery(parts[0], Telephony.Sms.STATUS_COMPLETE, SmsMessage.FORMAT_3GPP))
        assertEquals(Telephony.Sms.STATUS_COMPLETE, ledger().recordDelivery(parts[1], Telephony.Sms.STATUS_COMPLETE, SmsMessage.FORMAT_3GPP))
        assertEquals("delivered: nothing more to wait for", SentPartProgress.UnknownSend, ledger().recordSent(parts[0], ok))
    }

    @Test
    fun deliveryStatus_anyFailedPart_failsTheMessage() {
        val parts = start(parts = 2)
        ledger().recordSent(parts[0], ok)
        ledger().recordSent(parts[1], ok)

        assertEquals(Telephony.Sms.STATUS_FAILED, ledger().recordDelivery(parts[1], Telephony.Sms.STATUS_FAILED, SmsMessage.FORMAT_3GPP))
    }

    @Test
    fun aLaterReportForAPart_replacesItsEarlierOne() {
        val parts = start(parts = 1)
        ledger().recordSent(parts[0], ok)

        assertEquals(Telephony.Sms.STATUS_PENDING, ledger().recordDelivery(parts[0], Telephony.Sms.STATUS_PENDING, SmsMessage.FORMAT_3GPP))
        assertEquals(Telephony.Sms.STATUS_COMPLETE, ledger().recordDelivery(parts[0], Telephony.Sms.STATUS_COMPLETE, SmsMessage.FORMAT_3GPP))
    }

    @Test
    fun deliveryBeforeEverySentResult_stillSettlesTheSendAfterwards() {
        val parts = start(parts = 1)
        assertEquals(Telephony.Sms.STATUS_COMPLETE, ledger().recordDelivery(parts[0], Telephony.Sms.STATUS_COMPLETE, SmsMessage.FORMAT_3GPP))
        assertEquals(SentPartProgress.Settled(ok), ledger().recordSent(parts[0], ok))
    }

    @Test
    fun aNewSendToTheSameRow_forgetsTheEarlierSend() {
        val earlier = start(parts = 2)
        start(parts = 1)

        assertEquals(SentPartProgress.UnknownSend, ledger().recordSent(earlier[0], ok))
    }

    @Test
    fun aSendAwaitingDeliveryReports_isForgottenAfterTheWindow() {
        val parts = start(parts = 1)
        ledger().recordSent(parts[0], ok)

        now += DELIVERY_REPORT_WAIT_MS + 60_000 // past the record's last write
        start(parts = 1, rowId = 99L) // any later send prunes

        assertNull(ledger().recordDelivery(parts[0], Telephony.Sms.STATUS_COMPLETE, SmsMessage.FORMAT_3GPP))
    }

    @Test
    fun aRow_isTracked_whileItsSendsResultsMayStillCome() {
        val parts = start(parts = 1, rowId = 41L)

        assertTrue(ledger().tracks(41L))
        assertFalse("another row", ledger().tracks(43L))
        ledger().recordSent(parts[0], ok)
        assertTrue("its delivery report may still come", ledger().tracks(41L))
        ledger().recordDelivery(parts[0], Telephony.Sms.STATUS_COMPLETE, SmsMessage.FORMAT_3GPP)
        assertFalse("finished", ledger().tracks(41L))
    }

    @Test
    fun aSendThatNeverStarted_leavesItsRowUntracked() {
        val parts = start(parts = 2, rowId = 41L)
        ledger().forget(parts[0])
        assertFalse(ledger().tracks(41L))
    }

    @Test
    fun aPartThatDoesNotFitTheSend_isRejected() {
        val part = start(parts = 2)[0]
        assertThrows(IllegalArgumentException::class.java) { ledger().recordSent(part.copy(index = 2), ok) }
        assertThrows(IllegalArgumentException::class.java) { ledger().recordSent(part.copy(count = 3), ok) }
    }

    @Test
    fun aTokenThatCouldLeaveTheLedger_namesNoSend() {
        assertEquals(SentPartProgress.UnknownSend, ledger().recordSent(SmsPartRef("../escape", 41L, 0, 1), ok))
    }

    @Test
    fun anUndecodableRecord_isRemoved() {
        val parts = start(parts = 1)
        File(File(directory, "41"), "${parts[0].token}.json").writeText("{ not json")

        assertEquals(SentPartProgress.UnknownSend, ledger().recordSent(parts[0], ok))
        assertTrue(File(directory, "41").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aLastPart3gpp2Report_standsForTheWholeMessage() {
        // A 3GPP2 multipart SMS asks for a report on its last part only.
        val parts = start(parts = 3)
        parts.forEach { ledger().recordSent(it, ok) }

        assertEquals(
            Telephony.Sms.STATUS_COMPLETE,
            ledger().recordDelivery(parts[2], Telephony.Sms.STATUS_COMPLETE, SmsMessage.FORMAT_3GPP2),
        )
    }

    @Test
    fun anEarlierPart3gpp2Report_isThatPartsOnly() {
        val parts = start(parts = 3)
        parts.forEach { ledger().recordSent(it, ok) }

        assertEquals(
            Telephony.Sms.STATUS_PENDING,
            ledger().recordDelivery(parts[0], Telephony.Sms.STATUS_COMPLETE, SmsMessage.FORMAT_3GPP2),
        )
    }

    @Test
    fun aLastPart3gppReport_isThatPartsOnly() {
        val parts = start(parts = 2)
        parts.forEach { ledger().recordSent(it, ok) }

        assertEquals(
            Telephony.Sms.STATUS_PENDING,
            ledger().recordDelivery(parts[1], Telephony.Sms.STATUS_COMPLETE, SmsMessage.FORMAT_3GPP),
        )
    }

    @Test
    fun anUnreadableRecord_isKept_andFailsLoudly() {
        val parts = start(parts = 1)
        val record = File(File(directory, "41"), "${parts[0].token}.json")
        record.delete()
        record.mkdirs() // a directory cannot be read as a file

        assertThrows(java.io.IOException::class.java) { ledger().recordSent(parts[0], ok) }
        assertTrue(record.exists())
        record.delete()
    }
}
