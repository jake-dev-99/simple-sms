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
                val messageId = call.arguments as? String
                if (messageId == null) {
                    result.error("INVALID_ARGUMENT", "Message ID required", null)
                    return
                }
                try {
                    val success = deleteMessage(messageId)
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

    private fun deleteMessage(messageId: String): Boolean {
        // Try SMS first
        val smsDeleted = context.contentResolver.delete(
            Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, messageId),
            null,
            null
        )
        if (smsDeleted > 0) {
            Log.d(TAG, "Deleted SMS message $messageId")
            return true
        }

        // Try MMS
        val mmsDeleted = context.contentResolver.delete(
            Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, messageId),
            null,
            null
        )
        if (mmsDeleted > 0) {
            Log.d(TAG, "Deleted MMS message $messageId")
            return true
        }

        Log.w(TAG, "Message $messageId not found in SMS or MMS tables")
        return false
    }
}
