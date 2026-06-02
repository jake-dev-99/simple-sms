package io.simplezen.simple_sms.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Phase 1 · L4 — outbound exception → Dart error-contract golden (UNFY-147).
 *
 * `mapSendException` is the single source of truth for how a send-path
 * throwable becomes the `(code, message, details)` triple that
 * `MethodChannel.Result.error` hands to Dart as a `PlatformException`. The
 * Flutter layer switches on these codes, so a silent change here is a
 * cross-language breakage. This pins the mapping for every branch.
 *
 * Note: the SMS-vs-MMS routing and multi-SIM `subscriptionId` selection are
 * inline in `onMethodCall` (entangled with static SmsManager / Telephony /
 * the Flutter Result), so they can't be unit-tested without a production
 * testability extraction — which Phase 1's "net only, no production changes"
 * rule forbids. That extraction + its golden are deferred to the Phase-3
 * outbound port (recorded on UNFY-147).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboundErrorMappingTest {

    @Test
    fun attachmentUnreadable_mapsTo_attachmentCode_withDetails() {
        val cause = IOException("disk gone")
        val (code, message, details) = mapSendException(
            AttachmentUnreadableException("/sdcard/p.jpg", cause),
        )
        assertEquals(ERR_ATTACHMENT_UNREADABLE, code)
        assertTrue("message names the path", message.contains("/sdcard/p.jpg"))
        // details carry the path + underlying cause for the Dart side.
        assertTrue("details should be a map", details is Map<*, *>)
        val map = details as Map<*, *>
        assertEquals("/sdcard/p.jpg", map["attachmentPath"])
        assertEquals("disk gone", map["cause"])
    }

    @Test
    fun securityException_mapsTo_permissionDenied_noDetails() {
        val (code, message, details) = mapSendException(SecurityException("no SEND_SMS"))
        assertEquals(ERR_PERMISSION_DENIED, code)
        assertTrue(message.contains("no SEND_SMS"))
        assertNull(details)
    }

    @Test
    fun illegalArgument_mapsTo_invalidMessage_noDetails() {
        val (code, _, details) = mapSendException(IllegalArgumentException("empty recipient"))
        assertEquals(ERR_INVALID_MESSAGE, code)
        assertNull(details)
    }

    @Test
    fun unknownThrowable_mapsTo_genericSendInitiation() {
        // Any unmodelled throwable falls through to the generic code rather
        // than leaking an uncategorized error to Dart.
        val (code, _, details) = mapSendException(RuntimeException("kaboom"))
        assertEquals(ERR_SEND_INITIATION, code)
        assertNull(details)
    }
}
