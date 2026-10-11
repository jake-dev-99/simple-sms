package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Multipart SENT aggregation golden (UNFY-283). A multipart SMS fires one SENT
 * broadcast per part; the send counts as sent only once every part succeeded,
 * and settles as failed on the first failed part.
 *
 * Plain JUnit: [SentPartsAggregator] and [CarrierOutcome] are pure Kotlin.
 */
class SentPartsAggregatorTest {

    private fun ok() = CarrierOutcome(SENTSMS_ACTION, Activity.RESULT_OK, "SENT_SUCCESS", sent = true)

    private fun failed(code: Int, status: String) =
        CarrierOutcome(SENTSMS_ACTION, code, status, sent = false)

    @Test
    fun singlePart_settlesOnItsOnlyReport() {
        val parts = SentPartsAggregator()
        assertEquals(ok(), parts.record(ok()))
    }

    @Test
    fun multipart_waitsForEveryPart() {
        val parts = SentPartsAggregator().apply { expectParts(3) }
        assertNull(parts.record(ok()))
        assertNull(parts.record(ok()))
        assertEquals(ok(), parts.record(ok()))
    }

    @Test
    fun multipart_laterFailure_settlesAtThatFailure() {
        // The bug: part 1 succeeding used to settle the whole send as sent.
        val parts = SentPartsAggregator().apply { expectParts(3) }
        val failure = failed(SmsManager.RESULT_ERROR_GENERIC_FAILURE, "ERROR_GENERIC_FAILURE")
        assertNull(parts.record(ok()))
        assertEquals(failure, parts.record(failure))
    }

    @Test
    fun multipart_firstPartFailure_settlesImmediately() {
        val parts = SentPartsAggregator().apply { expectParts(2) }
        val first = failed(SmsManager.RESULT_ERROR_NO_SERVICE, "ERROR_NO_SERVICE")
        assertEquals(first, parts.record(first))
    }

    @Test
    fun partCount_mustBePositive_andFixedBeforeReports() {
        assertThrows(IllegalArgumentException::class.java) { SentPartsAggregator().expectParts(0) }
        val parts = SentPartsAggregator().apply { expectParts(2) }
        parts.record(ok())
        assertThrows(IllegalStateException::class.java) { parts.expectParts(3) }
    }
}
