package io.simplezen.simple_sms.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Phase 3 (outbound port) — pins the SMS-vs-MMS routing and multi-SIM
 * subscription-id selection extracted from `OutboundMessagingHandler.onMethodCall`
 * into the pure functions [routeMessage] / [selectSubscriptionId]. These were
 * previously inline and untestable (entangled with static SmsManager/Telephony
 * + the Flutter Result); pinning them is the outbound net for the live
 * Transaction.java trim that follows (deferred L4 piece, UNFY-147 / UNFY-120).
 *
 * Plain JUnit (no Robolectric): both functions are pure Kotlin with no Android
 * dependencies, and they live alongside `const val` error codes that inline at
 * compile time — so loading them needs no Android runtime.
 */
class OutboundRoutingTest {

    @Test
    fun singleRecipient_textOnly_routesSms() {
        assertEquals(OutboundRoute.SMS, routeMessage(hasAttachments = false, recipientCount = 1))
    }

    @Test
    fun multipleRecipients_routeMms_evenWithoutAttachments() {
        assertEquals(OutboundRoute.MMS, routeMessage(hasAttachments = false, recipientCount = 2))
    }

    @Test
    fun attachments_routeMms_evenForSingleRecipient() {
        assertEquals(OutboundRoute.MMS, routeMessage(hasAttachments = true, recipientCount = 1))
    }

    @Test
    fun attachments_andMultipleRecipients_routeMms() {
        assertEquals(OutboundRoute.MMS, routeMessage(hasAttachments = true, recipientCount = 5))
    }

    // Boundary: an empty recipient list (recipientCount = 0) is reachable
    // (addresses is built unchecked from the Flutter args). Pin it so a future
    // edit to the `> 1` predicate can't silently flip the empty-list route.
    @Test
    fun noRecipients_textOnly_routesSms() {
        assertEquals(OutboundRoute.SMS, routeMessage(hasAttachments = false, recipientCount = 0))
    }

    @Test
    fun noRecipients_withAttachments_routeMms() {
        assertEquals(OutboundRoute.MMS, routeMessage(hasAttachments = true, recipientCount = 0))
    }

    @Test
    fun selectSubscriptionId_prefersExplicit_andDoesNotConsultDefault() {
        var defaultConsulted = false
        val sub = selectSubscriptionId(explicit = 7) {
            defaultConsulted = true
            99
        }
        assertEquals(7, sub)
        assertFalse(
            "default-SMS subscription must not be queried when the caller is explicit",
            defaultConsulted,
        )
    }

    @Test
    fun selectSubscriptionId_fallsBackToDefault_whenNull() {
        assertEquals(99, selectSubscriptionId(explicit = null) { 99 })
    }
}
