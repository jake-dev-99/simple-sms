package com.klinker.android.send_message

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Base receiver for message send-status updates. First-party Kotlin port of the
 * vendored Klinker `StatusUpdatedReceiver`; behaviour preserved.
 *
 * `onReceive` is final and dispatches both hooks on a background thread (the
 * vendored contract): the implementer hook [onMessageStatusUpdated] first, then
 * the internal-database update [updateInInternalDatabase], each given the
 * captured result code.
 */
abstract class StatusUpdatedReceiver : BroadcastReceiver() {

    /** Updates the status of the message in the internal database. */
    abstract fun updateInInternalDatabase(context: Context, intent: Intent, receiverResultCode: Int)

    /** Lets the implementer update the status of the message in their database. */
    abstract fun onMessageStatusUpdated(context: Context, intent: Intent, receiverResultCode: Int)

    final override fun onReceive(context: Context, intent: Intent) {
        val code = resultCode
        Thread {
            onMessageStatusUpdated(context, intent, code)
            updateInInternalDatabase(context, intent, code)
        }.start()
    }
}
