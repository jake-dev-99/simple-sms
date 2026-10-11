package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
    private var now = 1_000_000L
    private val ok = Activity.RESULT_OK
    private val failure = SmsManager.RESULT_ERROR_GENERIC_FAILURE

    /** A ledger over the same files, as a new app process would build it. */
    private fun ledger() = SmsPartLedger(directory) { now }

    private fun start(parts: Int, uri: String = "content://sms/41") = ledger().start(uri, 41, parts)

    @Test
    fun singlePart_settlesOnItsOnlyReport() {
        val token = start(parts = 1)
        assertEquals(SentPartProgress.Settled(ok), ledger().recordSent(token, 0, 1, ok))
    }

    @Test
    fun multipart_waitsForEveryPart() {
        val token = start(parts = 3)
        assertEquals(SentPartProgress.AwaitingParts, ledger().recordSent(token, 0, 3, ok))
        assertEquals(SentPartProgress.AwaitingParts, ledger().recordSent(token, 2, 3, ok))
        assertEquals(SentPartProgress.Settled(ok), ledger().recordSent(token, 1, 3, ok))
    }

    @Test
    fun multipart_successThenFailure_settlesAfterTheLastPart_asFailed() {
        val token = start(parts = 2)
        assertEquals(SentPartProgress.AwaitingParts, ledger().recordSent(token, 0, 2, ok))
        assertEquals(SentPartProgress.Settled(failure), ledger().recordSent(token, 1, 2, failure))
    }

    @Test
    fun multipart_failureThenSuccess_settlesAfterTheLastPart_asFailed() {
        // The bug: the first part's report used to decide the whole message.
        val token = start(parts = 2)
        assertEquals(SentPartProgress.AwaitingParts, ledger().recordSent(token, 0, 2, failure))
        assertEquals(SentPartProgress.Settled(failure), ledger().recordSent(token, 1, 2, ok))
    }

    @Test
    fun aRepeatedPart_isIgnored() {
        val token = start(parts = 2)
        ledger().recordSent(token, 0, 2, ok)
        assertEquals(SentPartProgress.DuplicatePart, ledger().recordSent(token, 0, 2, failure))
    }

    @Test
    fun aFailedSend_isForgotten_soLaterReportsAreIgnored() {
        val token = start(parts = 1)
        ledger().recordSent(token, 0, 1, failure)

        assertEquals(SentPartProgress.UnknownSend, ledger().recordSent(token, 0, 1, ok))
        assertNull(ledger().recordDelivery(token, 0, 1, Telephony.Sms.STATUS_COMPLETE))
    }

    @Test
    fun deliveryStatus_isPendingUntilEveryPartIsComplete() {
        val token = start(parts = 2)
        ledger().recordSent(token, 0, 2, ok)
        ledger().recordSent(token, 1, 2, ok)

        assertEquals(Telephony.Sms.STATUS_PENDING, ledger().recordDelivery(token, 0, 2, Telephony.Sms.STATUS_COMPLETE))
        assertEquals(Telephony.Sms.STATUS_COMPLETE, ledger().recordDelivery(token, 1, 2, Telephony.Sms.STATUS_COMPLETE))
        assertEquals("delivered: nothing more to wait for", SentPartProgress.UnknownSend, ledger().recordSent(token, 0, 2, ok))
    }

    @Test
    fun deliveryStatus_anyFailedPart_failsTheMessage() {
        val token = start(parts = 2)
        ledger().recordSent(token, 0, 2, ok)
        ledger().recordSent(token, 1, 2, ok)

        assertEquals(Telephony.Sms.STATUS_FAILED, ledger().recordDelivery(token, 1, 2, Telephony.Sms.STATUS_FAILED))
    }

    @Test
    fun aLaterReportForAPart_replacesItsEarlierOne() {
        val token = start(parts = 1)
        ledger().recordSent(token, 0, 1, ok)

        assertEquals(Telephony.Sms.STATUS_PENDING, ledger().recordDelivery(token, 0, 1, Telephony.Sms.STATUS_PENDING))
        assertEquals(Telephony.Sms.STATUS_COMPLETE, ledger().recordDelivery(token, 0, 1, Telephony.Sms.STATUS_COMPLETE))
    }

    @Test
    fun deliveryBeforeEverySentResult_stillSettlesTheSendAfterwards() {
        val token = start(parts = 1)
        assertEquals(Telephony.Sms.STATUS_COMPLETE, ledger().recordDelivery(token, 0, 1, Telephony.Sms.STATUS_COMPLETE))
        assertEquals(SentPartProgress.Settled(ok), ledger().recordSent(token, 0, 1, ok))
    }

    @Test
    fun aNewSendToTheSameRow_forgetsTheEarlierSend() {
        val earlier = start(parts = 2)
        start(parts = 1)

        assertEquals(SentPartProgress.UnknownSend, ledger().recordSent(earlier, 0, 2, ok))
    }

    @Test
    fun aSendAwaitingDeliveryReports_isForgottenAfterTheWindow() {
        val token = start(parts = 1)
        ledger().recordSent(token, 0, 1, ok)

        now += DELIVERY_REPORT_WAIT_MS + 1
        start(parts = 1, uri = "content://sms/99") // any later send prunes

        assertNull(ledger().recordDelivery(token, 0, 1, Telephony.Sms.STATUS_COMPLETE))
    }

    @Test
    fun aPartThatDoesNotFitTheSend_isRejected() {
        val token = start(parts = 2)
        assertThrows(IllegalArgumentException::class.java) { ledger().recordSent(token, 2, 2, ok) }
        assertThrows(IllegalArgumentException::class.java) { ledger().recordSent(token, 0, 3, ok) }
    }

    @Test
    fun aTokenThatCouldLeaveTheLedger_namesNoSend() {
        assertEquals(SentPartProgress.UnknownSend, ledger().recordSent("../escape", 0, 1, ok))
    }

    @Test
    fun anUndecodableRecord_isRemoved() {
        val token = start(parts = 1)
        File(directory, "$token.json").writeText("{ not json")

        assertEquals(SentPartProgress.UnknownSend, ledger().recordSent(token, 0, 1, ok))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
}
