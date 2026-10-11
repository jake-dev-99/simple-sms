package io.simplezen.simple_sms.messaging

import android.content.Intent
import android.telephony.SubscriptionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UNFY-306: an inbound SMS row records the SIM it arrived on, so a reply can
 * go out on the same line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InboundSubscriptionIdTest {

    private fun deliver() = Intent("android.provider.Telephony.SMS_DELIVER")

    @Test
    fun readsThePlatformSubscriptionIndex() {
        val intent = deliver().putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 2)

        assertEquals(2, inboundSubscriptionId(intent))
    }

    @Test
    fun readsTheLegacySubscriptionExtra() {
        val intent = deliver().putExtra("subscription", 3)

        assertEquals(3, inboundSubscriptionId(intent))
    }

    @Test
    fun prefersThePlatformIndexOverTheLegacyExtra() {
        val intent = deliver()
            .putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 2)
            .putExtra("subscription", 3)

        assertEquals(2, inboundSubscriptionId(intent))
    }

    @Test
    fun treatsAMissingOrInvalidIdAsUnknown() {
        assertNull(inboundSubscriptionId(deliver()))
        assertNull(
            inboundSubscriptionId(
                deliver().putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1),
            ),
        )
    }
}
