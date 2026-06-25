package io.simplezen.simple_sms.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins channel -> table resolution for the per-message write surface (UNFY-213).
 *
 * The provider read contract is channel-qualified, so mark-read / delete must
 * target the one correct table by channel and never fall back to an SMS-first
 * guess (which marked or deleted the wrong message when an SMS and an MMS
 * shared an `_id`). An absent or unrecognized channel resolves to `null` so the
 * handler surfaces an error instead of silently defaulting to a table.
 *
 * Plain JUnit: [messageTableFor] is pure Kotlin with no Android dependencies
 * (the Android-bound [contentUri] extension is intentionally not exercised
 * here — it is integration-gated, not unit-tested).
 */
class MessageWriteTargetTest {
    @Test
    fun sms_resolvesToSmsTable() {
        assertEquals(MessageTable.SMS, messageTableFor("sms"))
    }

    @Test
    fun mms_resolvesToMmsTable() {
        assertEquals(MessageTable.MMS, messageTableFor("mms"))
    }

    @Test
    fun nullChannel_resolvesToNull() {
        assertNull(messageTableFor(null))
    }

    @Test
    fun unknownChannel_resolvesToNull_noSilentDefault() {
        // A future channel (e.g. RCS) must not silently resolve to SMS/MMS —
        // the handler errors rather than writing the wrong table.
        assertNull(messageTableFor("rcs"))
    }
}
