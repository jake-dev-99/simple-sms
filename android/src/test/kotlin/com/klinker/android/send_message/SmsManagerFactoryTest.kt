package com.klinker.android.send_message

import android.telephony.SmsManager
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the [SmsManagerFactory] contract: it never returns null — the
 * default-subscription path returns the system default, and the explicit-id
 * path falls back to the default if the subscription-specific manager can't be
 * resolved. (Thin wrapper over `SmsManager` statics; the resolution itself is
 * exercised under Robolectric's shadow.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsManagerFactoryTest {

    @Test
    fun defaultSubscriptionId_returnsNonNullManager() {
        assertNotNull(SmsManagerFactory.createSmsManager(Settings.DEFAULT_SUBSCRIPTION_ID))
    }

    @Test
    fun fromSettings_defaultSubscription_returnsNonNullManager() {
        assertNotNull(SmsManagerFactory.createSmsManager(Settings()))
    }

    @Test
    fun explicitSubscriptionId_returnsNonNullManager() {
        val mgr: SmsManager = SmsManagerFactory.createSmsManager(2)
        assertNotNull(mgr)
    }
}
