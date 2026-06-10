package io.simplezen.simple_sms.messaging

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the WAP-push action routing ([classifyWapPushAction] / [WapAction]) for
 * [InboundMmsHandler] (UNFY-161).
 *
 * Both `WAP_PUSH_DELIVER` and `WAP_PUSH_RECEIVED` are registered in the manifest
 * and handled deliberately: DELIVER is the default-app download+persist path;
 * RECEIVED is a public broadcast deliberately ignored as a de-dup. Before this
 * fix, RECEIVED fell into the `else` branch and logged `W/"unexpected action"`
 * on every inbound MMS — these tests lock in that it is now classified as an
 * expected no-op, not an error.
 *
 * Plain JUnit: the classifier is pure Kotlin with no Android dependencies.
 *
 * The action strings here are intentionally the **raw** wire values (not the
 * `Telephony.Sms.Intents.WAP_PUSH_*_ACTION` constants the classifier switches
 * on): this pins that those constants still resolve to the literal strings the
 * manifest `<intent-filter>`s and the OS broadcast actually carry, so a platform
 * constant value drift would surface as a test failure rather than silently.
 */
class WapPushActionRoutingTest {

    @Test
    fun deliver_routesToDeliver() {
        assertEquals(
            WapAction.Deliver,
            classifyWapPushAction("android.provider.Telephony.WAP_PUSH_DELIVER"),
        )
    }

    @Test
    fun received_isDeliberatelyIgnored_notUnexpected() {
        // The regression this fix targets: RECEIVED must NOT fall into the
        // Unexpected/warn branch — it is an expected, de-duped no-op.
        assertEquals(
            WapAction.ReceivedIgnored,
            classifyWapPushAction("android.provider.Telephony.WAP_PUSH_RECEIVED"),
        )
    }

    @Test
    fun unknownAction_isUnexpected() {
        assertEquals(
            WapAction.Unexpected,
            classifyWapPushAction("android.intent.action.BOOT_COMPLETED"),
        )
    }

    @Test
    fun nullAction_isUnexpected() {
        // BroadcastReceiver intents can carry a null action; it must classify as
        // Unexpected rather than crash.
        assertEquals(WapAction.Unexpected, classifyWapPushAction(null))
    }
}
