package io.simplezen.simple_sms.device

import android.content.Context
import android.provider.Telephony.Mms
import androidx.core.net.toUri
import io.simplezen.simple_sms.BinaryData
import io.simplezen.simple_sms.messaging.OutboundMessagingHandler
import io.flutter.Log
import io.simplezen.simple_sms.SimpleSmsPlugin
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import io.flutter.plugin.common.JSONMethodCodec
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall

// SargentPigeon
class DeviceActions(val context: Context) : MethodChannel.MethodCallHandler {

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "sendMessage" -> {
                result.notImplemented()
            }
            else ->  result.notImplemented()
        }
    }
     fun getSendStatus(messageId: String): String {
        throw Exception("Not implemented")
    }

     fun checkPermissions(permissions: List<String>): Map<String, Boolean> {
        return SimpleSmsPlugin.Companion.checkPermissions(context, permissions.toTypedArray())
    }

     fun requestPermissions(permissions: List<String>): Map<String, Boolean> {
        return SimpleSmsPlugin.Companion.requestPermissions(permissions.toTypedArray())
    }

     fun sendNotification(): Boolean {
        TODO("Not yet implemented")
    }

     fun checkRole(role: String): Boolean {
        return if (role.isEmpty()) {
            true
        } else {
            SimpleSmsPlugin.Companion.checkRole(context, role)
        }
    }

     fun requestRole(role: String): Boolean {
        return if (role.isEmpty()) {
            true
        } else {
            SimpleSmsPlugin.Companion.requestRole(role)
        }
    }


    // New method to load MMS attachment content
     fun loadMmsAttachment(contentUri: String): BinaryData? {
        try {
            val uri = contentUri.toUri()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val outputStream = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }
                Log.d("DeviceActions", "Successfully loaded MMS attachment: $contentUri")
                return BinaryData(outputStream.toByteArray().map { it.toLong() })
            }
        } catch (e: Exception) {
            Log.e("DeviceActions", "Error loading MMS attachment: ${e.message}")
            e.printStackTrace()
        }
        return null
    }

    // New method to save MMS attachment to a temporary file
     fun saveMmsAttachmentToFile(contentUri: String): String? {
        try {
            val uri = contentUri.toUri()
            val fileName = "mms_${System.currentTimeMillis()}"

            // Get MIME type to determine file extension
            var mimeType = "application/octet-stream"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val mimeTypeIndex = cursor.getColumnIndex(Mms.Part.CONTENT_TYPE)
                    if (mimeTypeIndex != -1) {
                        mimeType = cursor.getString(mimeTypeIndex) ?: mimeType
                    }
                }
            }

            // Determine file extension based on MIME type
            val extension = when {
                mimeType.startsWith("image/") -> ".jpg"
                mimeType.startsWith("video/") -> ".mp4"
                mimeType.startsWith("audio/") -> ".mp3"
                else -> ".bin"
            }

            // Create temp file
            val tempFile = File.createTempFile(fileName, extension, context.cacheDir)

            // Copy content to temp file
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    val buffer = ByteArray(1024)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                    }
                }
            }

            Log.d("DeviceActions", "Saved MMS attachment to: ${tempFile.absolutePath}")
            return tempFile.absolutePath
        } catch (e: Exception) {
            Log.e("DeviceActions", "Error saving MMS attachment: ${e.message}")
            e.printStackTrace()
        }
        return null
    }
}