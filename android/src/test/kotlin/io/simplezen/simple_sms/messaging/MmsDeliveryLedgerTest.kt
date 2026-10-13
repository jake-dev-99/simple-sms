package io.simplezen.simple_sms.messaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.simplezen.simple_sms.mms.codec.PduHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins [MmsDeliveryLedger] (UNFY-110): a sent MMS is delivered only once
 * every recipient reported it delivered, failed only once every recipient
 * reported a failure, without a verdict when some got it and some never
 * will, and its reports add up across ledger instances, as across app
 * processes.
 *
 * Robolectric for `android.util.AtomicFile`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MmsDeliveryLedgerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.filesDir, "mms_delivery_ledger_test")
    private var now = System.currentTimeMillis()
    private val retrieved = PduHeaders.STATUS_RETRIEVED
    private val forwarded = PduHeaders.STATUS_FORWARDED
    private val rejected = PduHeaders.STATUS_REJECTED
    private val expired = PduHeaders.STATUS_EXPIRED

    /** A ledger over the same files, as a new app process would build it. */
    private fun ledger() = MmsDeliveryLedger(directory) { now }

    private fun record(recipient: String, status: Int, recipients: Int, rowId: Long = 7L, messageId: String = "mid-1") =
        ledger().record(rowId, messageId, recipient, status, recipients)

    @Before
    fun clean() {
        directory.deleteRecursively()
    }

    @Test
    fun oneRecipient_settlesOnItsReport() {
        assertEquals(retrieved, record("a", retrieved, recipients = 1))
    }

    @Test
    fun group_isDeliveredOnlyOnceEveryRecipientIs() {
        assertNull(record("a", retrieved, recipients = 3))
        assertNull(record("b", forwarded, recipients = 3))
        assertEquals(retrieved, record("c", retrieved, recipients = 3))
    }

    @Test
    fun group_failsOnlyOnceNoRecipientWillGetIt_withTheFirstFailure() {
        assertNull(record("a", rejected, recipients = 2))
        assertEquals(rejected, record("b", expired, recipients = 2))
    }

    @Test
    fun group_someDeliveredSomeFailed_hasNoVerdict() {
        // Not failed for those who got it: a resend would reach them twice.
        assertNull(record("a", retrieved, recipients = 2))
        assertNull(record("b", rejected, recipients = 2))
    }

    @Test
    fun aRecipientsRepeatedReport_neitherCountsTwiceNorReplacesItsVerdict() {
        assertNull(record("a", retrieved, recipients = 2))
        assertNull(record("a", retrieved, recipients = 2))
        assertNull(record("a", rejected, recipients = 2))
        assertEquals(retrieved, record("b", retrieved, recipients = 2))
    }

    @Test
    fun anotherMessageIdOnTheRow_startsItOver() {
        // The row was reused by a new message, with a new SendConf id.
        assertNull(record("a", rejected, recipients = 2, messageId = "old"))
        assertNull(record("a", retrieved, recipients = 2, messageId = "new"))
        assertEquals(retrieved, record("b", retrieved, recipients = 2, messageId = "new"))
    }

    @Test
    fun aRepeatedReport_neitherRewritesTheRecordNorExtendsItsWindow() {
        assertNull(record("a", retrieved, recipients = 2))
        val file = File(directory, "7.json")
        file.setLastModified(now - 1_000)
        val written = file.lastModified()

        assertNull(record("a", retrieved, recipients = 2))

        assertEquals(written, file.lastModified())
    }

    @Test
    fun rowsAreKeptApart() {
        assertNull(record("a", retrieved, recipients = 2, rowId = 1))
        assertNull(record("b", retrieved, recipients = 2, rowId = 2))
    }

    @Test
    fun aRecordIsDroppedAfterTheWindow() {
        assertNull(record("a", retrieved, recipients = 2))
        // Pruning compares file times with the ledger's clock.
        File(directory, "7.json").setLastModified(now)
        now += MMS_DELIVERY_REPORT_WAIT_MS + 1
        assertNull(record("b", retrieved, recipients = 2))
    }

    @Test
    fun anUndecodableRecord_isRemovedAndTheRowStartsOver() {
        directory.mkdirs()
        File(directory, "7.json").writeText("not json")
        assertNull(record("a", retrieved, recipients = 2))
        assertEquals(retrieved, record("b", retrieved, recipients = 2))
    }

    @Test
    fun aNonVerdictOrNoRecipients_isRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            record("a", PduHeaders.STATUS_DEFERRED, recipients = 1)
        }
        assertThrows(IllegalArgumentException::class.java) { record("a", retrieved, recipients = 0) }
    }

    @Test
    fun record_rejectsAFirstFailureItsVerdictsDoNotHold() {
        assertThrows(IllegalArgumentException::class.java) {
            MmsDeliveryRecord("mid", mapOf("a" to retrieved), firstFailedStatus = rejected)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MmsDeliveryRecord("mid", mapOf("a" to rejected), firstFailedStatus = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MmsDeliveryRecord("mid", mapOf("a" to PduHeaders.STATUS_DEFERRED))
        }
    }
}
