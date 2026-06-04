package com.klinker.android.send_message

import android.telephony.SmsManager
import android.util.Log

/**
 * Resolves the [SmsManager] to send with — the subscription-specific one when a
 * non-default subscription id is configured, otherwise the system default.
 * First-party Kotlin port of the vendored Klinker `SmsManagerFactory.java`;
 * behaviour preserved.
 */
object SmsManagerFactory {
    private const val TAG = "SmsManagerFactory"

    @JvmStatic
    fun createSmsManager(settings: Settings): SmsManager =
        createSmsManager(settings.getSubscriptionId())

    @JvmStatic
    fun createSmsManager(subscriptionId: Int): SmsManager {
        if (subscriptionId != Settings.DEFAULT_SUBSCRIPTION_ID) {
            val manager: SmsManager? = try {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } catch (e: Exception) {
                // Was e.printStackTrace() in the vendored source; surface via
                // the logger. Non-fatal — we fall back to the default below.
                Log.e(TAG, "Failed to resolve SmsManager for subscription $subscriptionId", e)
                null
            }
            return manager ?: SmsManager.getDefault()
        }
        return SmsManager.getDefault()
    }
}
