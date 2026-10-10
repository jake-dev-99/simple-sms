package io.simplezen.simple_sms.mms.sending

import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Purpose: catch a sender overriding the selected SIM's carrier settings.
 *
 * @return Unit; assertions fail if platform-owned values are replaced.
 * @throws AssertionError when carrier settings or the user's group choice change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformMmsOverridesTest {
    /**
     * Purpose: preserve group sending without replacing carrier transport values.
     * @return Unit.
     * @throws AssertionError when a carrier value is overridden.
     */
    @Test
    fun groupSending_leavesCarrierTransportSettingsToAndroid() {
        val overrides = Settings().apply { group = true }.toMmsConfigOverrides()

        assertTrue(overrides.getBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED))
        assertEquals(setOf(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED), overrides.keySet())
        assertFalse(overrides.containsKey(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE))
        assertFalse(overrides.containsKey(SmsManager.MMS_CONFIG_HTTP_PARAMS))
    }

    /**
     * Purpose: preserve individual-recipient MMS when group sending is disabled.
     * @return Unit.
     * @throws AssertionError when the user's group choice is lost.
     */
    @Test
    fun individualSending_preservesDisabledGroupOption() {
        val overrides = Settings().apply { group = false }.toMmsConfigOverrides()

        assertTrue(overrides.containsKey(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED))
        assertFalse(overrides.getBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED))
        assertEquals(setOf(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED), overrides.keySet())
    }
}
