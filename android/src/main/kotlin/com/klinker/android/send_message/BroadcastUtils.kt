package com.klinker.android.send_message

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Helper for Android O+ explicit broadcasts: the platform no longer delivers
 * implicit broadcasts to manifest-registered receivers, so the target
 * receiver's class + package must be stamped onto the intent. First-party
 * Kotlin port of the vendored Klinker `BroadcastUtils.java`; behaviour
 * preserved.
 *
 * Receivers are matched by `taskAffinity` (the vendored convention abuses
 * `android:taskAffinity` on `<receiver>` entries as an action tag), so a
 * receiver whose `taskAffinity` equals [action] is selected.
 */
object BroadcastUtils {
    private const val TAG = "BroadcastUtils"

    @JvmStatic
    fun sendExplicitBroadcast(context: Context, intent: Intent, action: String) {
        addClassName(context, intent, action)
        intent.action = action
        context.sendBroadcast(intent)
    }

    @JvmStatic
    fun addClassName(context: Context, intent: Intent, action: String) {
        val pm = context.packageManager
        try {
            val packageInfo =
                pm.getPackageInfo(context.packageName, PackageManager.GET_RECEIVERS)
            // `receivers` is null when the app declares none; the vendored code
            // iterated unconditionally (NPE → caught), so an empty/none result
            // is a no-op here too.
            for (receiver in packageInfo.receivers.orEmpty()) {
                if (receiver.taskAffinity == action) {
                    intent.setClassName(receiver.packageName, receiver.name)
                }
            }
        } catch (e: Exception) {
            // Was `e.printStackTrace()` in the vendored source; surface via the
            // logger instead of stderr. Non-fatal — the broadcast still goes out
            // package-scoped below, just not pinned to a specific receiver class.
            Log.e(TAG, "Failed to resolve explicit receiver for action $action", e)
        }
        intent.setPackage(context.packageName)
    }
}
