package io.simplezen.simple_sms.device

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.provider.Telephony
import android.provider.Telephony.Mms
import androidx.core.net.toUri
import io.flutter.Log
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.simplezen.simple_sms.queries.Query
import io.simplezen.simple_sms.queries.QueryObj
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class DeviceActions(val context: Context) : MethodChannel.MethodCallHandler {
    companion object {
        private const val TAG = "DeviceActions"
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "markMessageAsRead" -> {
                val args = call.arguments as? Map<*, *>
                val messageId = args?.get("messageId") as? String
                val table = messageTableFor(args?.get("channel") as? String)
                if (messageId == null || table == null) {
                    result.error(
                        "INVALID_ARGUMENT",
                        "markMessageAsRead requires messageId + channel (sms|mms); " +
                            "got messageId=$messageId channel=${args?.get("channel")}",
                        null,
                    )
                    return
                }
                try {
                    result.success(markMessageAsRead(messageId, table))
                } catch (e: Exception) {
                    result.error(
                        "MARK_READ_FAILED",
                        "Failed to mark message read: ${e.message}",
                        e.stackTraceToString(),
                    )
                }
            }
            "markConversationAsRead" -> {
                val conversationId = call.arguments as? String
                if (conversationId == null) {
                    result.error("INVALID_ARGUMENT", "Conversation ID required", null)
                    return
                }
                try {
                    result.success(markConversationAsRead(conversationId))
                } catch (e: Exception) {
                    result.error(
                        "MARK_READ_FAILED",
                        "Failed to mark conversation read: ${e.message}",
                        e.stackTraceToString(),
                    )
                }
            }
            "launchAddContact" -> {
                val args = call.arguments as? Map<*, *>
                val phoneNumber = args?.get("phoneNumber") as? String
                val name = args?.get("name") as? String
                result.success(launchAddContact(phoneNumber, name))
            }
            else -> result.notImplemented()
        }
    }

    /**
     * Mark one message read in the table named by [table]. The native `_id` is
     * unique only within its own table, so the channel-derived [table] targets
     * the right message directly — no SMS-first fallback (UNFY-213).
     */
    private fun markMessageAsRead(messageId: String, table: MessageTable): Boolean {
        val values = ContentValues().apply {
            when (table) {
                MessageTable.SMS -> {
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.SEEN, 1)
                }
                MessageTable.MMS -> {
                    put(Telephony.Mms.READ, 1)
                    put(Telephony.Mms.SEEN, 1)
                }
            }
        }
        val idColumn = when (table) {
            MessageTable.SMS -> Telephony.Sms._ID
            MessageTable.MMS -> Telephony.Mms._ID
        }
        val updated = context.contentResolver.update(
            table.contentUri(),
            values,
            "$idColumn = ?",
            arrayOf(messageId)
        )
        return updated > 0
    }

    private fun markConversationAsRead(conversationId: String): Boolean {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        val smsUpdated = context.contentResolver.update(
            Telephony.Sms.CONTENT_URI,
            values,
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
            arrayOf(conversationId)
        )

        val mmsValues = ContentValues().apply {
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
        }
        val mmsUpdated = context.contentResolver.update(
            Telephony.Mms.CONTENT_URI,
            mmsValues,
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0",
            arrayOf(conversationId)
        )
        return (smsUpdated + mmsUpdated) > 0
    }

    private fun launchAddContact(phoneNumber: String?, name: String?): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                type = ContactsContract.Contacts.CONTENT_TYPE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                phoneNumber?.let {
                    putExtra(ContactsContract.Intents.Insert.PHONE, it)
                }
                name?.let {
                    putExtra(ContactsContract.Intents.Insert.NAME, it)
                }
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch add contact: ${e.message}")
            false
        }
    }

    fun loadMmsAttachment(contentUri: String): ByteArray? {
        try {
            val uri = contentUri.toUri()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val outputStream = ByteArrayOutputStream()
                inputStream.copyTo(outputStream)
                return outputStream.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading MMS attachment: ${e.message}")
        }
        return null
    }

    fun saveMmsAttachmentToFile(contentUri: String): String? {
        try {
            val uri = contentUri.toUri()
            val fileName = "mms_${System.currentTimeMillis()}"

            var mimeType = "application/octet-stream"
            // Content-type probe routes through simple_query (Rule 1). AGENTS.md is
            // explicit: the bytes stream (openInputStream) is the exception, "not
            // the content-type probe".
            val ctRows = Query(context).query(
                QueryObj(
                    contentUri = uri.toString(),
                    projection = listOf(Mms.Part.CONTENT_TYPE),
                ),
            )
            (ctRows.firstOrNull()?.get(Mms.Part.CONTENT_TYPE) as? String)?.let { mimeType = it }

            val extension = when {
                mimeType.startsWith("image/") -> ".jpg"
                mimeType.startsWith("video/") -> ".mp4"
                mimeType.startsWith("audio/") -> ".mp3"
                else -> ".bin"
            }

            val tempFile = File.createTempFile(fileName, extension, context.cacheDir)

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            return tempFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error saving MMS attachment: ${e.message}")
        }
        return null
    }
}
