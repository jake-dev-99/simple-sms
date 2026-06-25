package io.simplezen.simple_sms.device

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class DestructiveActions(val context: Context) : MethodChannel.MethodCallHandler {
    companion object {
        private const val TAG = "DestructiveActions"
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "deleteThread" -> {
                val threadId = call.arguments as? String
                if (threadId == null) {
                    result.error("INVALID_ARGUMENT", "Thread ID required", null)
                    return
                }
                try {
                    val success = deleteThread(threadId)
                    result.success(success)
                } catch (e: Exception) {
                    result.error("DELETE_FAILED", "Failed to delete thread: ${e.message}", null)
                }
            }
            "deleteMessage" -> {
                val args = call.arguments as? Map<*, *>
                val messageId = args?.get("messageId") as? String
                val table = messageTableFor(args?.get("channel") as? String)
                if (messageId == null || table == null) {
                    result.error(
                        "INVALID_ARGUMENT",
                        "deleteMessage requires messageId + channel (sms|mms); " +
                            "got messageId=$messageId channel=${args?.get("channel")}",
                        null,
                    )
                    return
                }
                try {
                    val success = deleteMessage(messageId, table)
                    result.success(success)
                } catch (e: Exception) {
                    result.error("DELETE_FAILED", "Failed to delete message: ${e.message}", null)
                }
            }
            else -> result.notImplemented()
        }
    }

    private fun deleteThread(threadId: String): Boolean {
        // Delete all SMS in the thread
        val smsDeleted = context.contentResolver.delete(
            Telephony.Sms.CONTENT_URI,
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId)
        )
        // Delete all MMS in the thread
        val mmsDeleted = context.contentResolver.delete(
            Telephony.Mms.CONTENT_URI,
            "${Telephony.Mms.THREAD_ID} = ?",
            arrayOf(threadId)
        )
        Log.d(TAG, "Deleted thread $threadId: $smsDeleted SMS, $mmsDeleted MMS")
        return (smsDeleted + mmsDeleted) > 0
    }

    /**
     * Delete one message from the table named by [table]. The native `_id` is
     * unique only within its own table, so the channel-derived [table] targets
     * the right message directly — no SMS-first fallback (UNFY-213).
     */
    private fun deleteMessage(messageId: String, table: MessageTable): Boolean {
        val deleted = context.contentResolver.delete(
            Uri.withAppendedPath(table.contentUri(), messageId),
            null,
            null
        )
        if (deleted > 0) {
            Log.d(TAG, "Deleted ${table.name} message $messageId")
            return true
        }
        Log.w(TAG, "Message $messageId not found in ${table.name} table")
        return false
    }
}
