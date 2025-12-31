@file:Suppress("UNCHECKED_CAST")

package io.simplezen.simple_sms.messaging

import android.R.attr.priority
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.provider.Telephony
import android.provider.Telephony.BaseMmsColumns.MESSAGE_BOX_INBOX
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.SendReq
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.simplezen.simple_sms.messaging.MmsDatabaseWriter.insertSms
import io.simplezen.simple_sms.models.MmsObject
import io.simplezen.simple_sms.models.MmsPart
import io.simplezen.simple_sms.queries.Query
import io.simplezen.simple_sms.queries.QueryObj
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Define these constants here or import them
const val SENTSMS_ACTION = "com.simplezen.simple_sms.SMS_SENT"
const val SENTMMS_ACTION = "com.simplezen.simple_sms.MMS_SENT"
const val DELIVEREDSMS_ACTION = "com.simplezen.simple_sms.SMS_DELIVERED"
const val DELIVEREDMMS_ACTION = "com.simplezen.simple_sms.MMS_DELIVERED"
const val TAG = "OutboundMessagingHandler" // For logs

class OutboundMessagingHandler() : Service(), MethodChannel.MethodCallHandler {
    private lateinit var context: Context // Use application context for receivers
    private var messageStatusReceiver: OutboundMessagingReceiver? = null
    private val channelResultMap = ConcurrentHashMap<Int, MessageRequestDetails>()

    data class MessageRequestDetails(
            val threadId: Long,
            val addresses: List<String>,
            val body: String,
            val subscriptionId: Int? = null,
            val attachmentPaths: List<String>? = null,
            var sentPendingIntent: PendingIntent?,
            var deliveredPendingIntent: PendingIntent?,
            val flutterResult: MethodChannel.Result
    )

    companion object {
        var msgId = UUID.randomUUID().mostSignificantBits.toInt()
    }

    // Constructor used by Flutter plugin registration
    constructor(context: Context) : this() {
        this.context = context.applicationContext
        setupSmsReceiver()
    }

    @Suppress("UNCHECKED_CAST")
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "sendMessage" -> {
                if (!context.packageManager.hasSystemFeature(
                                PackageManager.FEATURE_TELEPHONY_MESSAGING
                        )
                ) {
                    Log.d("SimsQuery", "Device missing FEATURE_TELEPHONY")
                    result.error(
                            "0x0",
                            "FEATURE_TELEPHONY",
                            "Error: Device does not have a SIM card"
                    )
                }

                // Store details with the Flutter result callback
                val message = call.arguments as Map<String, Any?>
                val threadId =
                        Telephony.Threads.getOrCreateThreadId(
                                context,
                                (message["recipients"] as List<String>).toSet()
                        )
                val requestDetails =
                        MessageRequestDetails(
                                threadId = threadId,
                                addresses = message["recipients"] as List<String>,
                                body = message["body"] as String,
                                subscriptionId = message["subscriptionId"] as Int?,
                                attachmentPaths = message["attachmentPaths"] as List<String>?,
                                sentPendingIntent = null,
                                deliveredPendingIntent = null,
                                flutterResult = result
                        )

                val smsManager: SmsManager =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val subIdToUse =
                                    requestDetails.subscriptionId
                                            ?: SmsManager.getDefaultSmsSubscriptionId()
                            context.getSystemService(SmsManager::class.java)
                                    .createForSubscriptionId(subIdToUse)
                        } else {
                            @Suppress("DEPRECATION") SmsManager.getDefault()
                        }

                // Store details with the Flutter result callback
                val sendResult =
                        if (requestDetails.attachmentPaths != null &&
                                        requestDetails.attachmentPaths.isNotEmpty()
                        ) {
                            sendMms(smsManager = smsManager, requestDetails = requestDetails)
                        } else if (requestDetails.body.length >= 160) {
                            sendMms(smsManager = smsManager, requestDetails = requestDetails)
                        } else if (requestDetails.addresses.size > 1) {
                            sendMms(smsManager = smsManager, requestDetails = requestDetails)
                        } else {
                            sendSms(smsManager = smsManager, requestDetails = requestDetails)
                        }
                if (!sendResult) {
                    channelResultMap.remove(msgId) // Clean up stored result
                    result.error("SEND_INITIATION_ERROR", "Failed to initiate SMS send.", null)
                } else {
                    // A successful result is handled by the broadcast receiver
                }
            }
            else -> result.notImplemented()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // If the service can be created independently of the constructor taking context
        // ensure context is initialized and receiver is setup.
        if (!::context.isInitialized) {
            this.context = applicationContext
        }
        setupSmsReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterSmsReceiver()
        // Clean up any pending results to avoid memory leaks if service is destroyed unexpectedly
        channelResultMap.clear()
    }

    private fun setupSmsReceiver() {
        if (messageStatusReceiver == null) {
            Log.d("OutboundMessagingHandler", "Setting up OutboundMessagingReceiver.")
            messageStatusReceiver =
                    OutboundMessagingReceiver {
                            messageId,
                            messageUri,
                            eventType,
                            statusString,
                            resultCode ->

                        // This callback is from OutboundMessagingReceiver
                        val statusUpdate =
                                ContentValues().apply {
                                    if (eventType == SENTMMS_ACTION) {
                                        put(
                                                Telephony.Mms.Sent.STATUS,
                                                Telephony.TextBasedSmsColumns.STATUS_COMPLETE
                                        )
                                    } else {
                                        put(
                                                Telephony.Sms.Sent.STATUS,
                                                Telephony.TextBasedSmsColumns.STATUS_COMPLETE
                                        )
                                    }
                                }
                        context.contentResolver.update(messageUri, statusUpdate, null, null)

                        val cursor: Cursor? =
                                context.contentResolver.query(messageUri, null, null, null, null)
                        val finalMessage = mutableMapOf<String, Any?>()
                        cursor?.use {
                            if (it.moveToFirst()) {
                                val row = HashMap<String, Any?>()
                                for (index in 0 until cursor.columnCount) {
                                    val columnName = cursor.getColumnName(index)
                                    val columnType = cursor.getType(index)
                                    when (columnType) {
                                        Cursor.FIELD_TYPE_NULL -> row[columnName] = null
                                        Cursor.FIELD_TYPE_INTEGER ->
                                                row[columnName] = cursor.getLong(index)
                                        Cursor.FIELD_TYPE_FLOAT ->
                                                row[columnName] = cursor.getFloat(index)
                                        Cursor.FIELD_TYPE_STRING ->
                                                row[columnName] = cursor.getString(index)
                                        Cursor.FIELD_TYPE_BLOB ->
                                                row[columnName] = cursor.getBlob(index)
                                        else -> {
                                            throw Exception("Unknown column type: $columnType")
                                        }
                                    }
                                    finalMessage[columnName] = row[columnName]
                                }
                            }
                        }
                        cursor?.close()

                        finalMessage["uri"] = messageUri.toString()
                        if (eventType == SENTMMS_ACTION) {
                            val partQueryObj =
                                    QueryObj(
                                            Telephony.Mms.Part.getPartUriForMessage(
                                                            messageId.toString()
                                                    )
                                                    .toString(),
                                    )
                            finalMessage["parts"] = Query(context).query(partQueryObj)

                            val addrQueryObj =
                                    QueryObj(
                                            Telephony.Mms.Addr.getAddrUriForMessage(
                                                            messageId.toString()
                                                    )
                                                    .toString(),
                                    )
                            finalMessage["recipients"] = Query(context).query(addrQueryObj)
                        }

                        val finalMessageStr = AnySerializer.encodeToString(finalMessage)
                        val flutterResult = channelResultMap[messageId]?.flutterResult
                        channelResultMap.remove(messageId) // Clean up stored result
                        flutterResult?.success(finalMessageStr)
                    }

            val intentFilter =
                    IntentFilter().apply {
                        addAction(SENTSMS_ACTION)
                        addAction(SENTMMS_ACTION)
                        addAction(DELIVEREDSMS_ACTION)
                        addAction(DELIVEREDMMS_ACTION)
                    }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                        messageStatusReceiver,
                        intentFilter,
                        Context.RECEIVER_EXPORTED
                )
            } else {
                ContextCompat.registerReceiver(
                        context,
                        messageStatusReceiver,
                        intentFilter,
                        ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }
            Log.d(
                    "OutboundMessagingHandler",
                    "OutboundMessagingReceiver registered for actions: $SENTSMS_ACTION, $DELIVEREDSMS_ACTION"
            )
        }
    }

    private fun unregisterSmsReceiver() {
        messageStatusReceiver?.let {
            try {
                context.unregisterReceiver(it)
                Log.d("OutboundMessagingHandler", "OutboundMessagingReceiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w(
                        "OutboundMessagingHandler",
                        "Receiver not registered or already unregistered: ${e.message}"
                )
            }
            messageStatusReceiver = null
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun sendSms(
            smsManager: SmsManager,
            requestDetails: MessageRequestDetails,
    ): Boolean {
        try {
            Log.d(
                    "OutboundMessagingHandler",
                    "Sending SMS to ${requestDetails.addresses}. Body: \"${requestDetails.body}\""
            )
            val newSms = insertSms(context, requestDetails)
            val newUri = newSms["uri"] as Uri
            msgId = ContentUris.parseId(newUri).toInt()
            channelResultMap[msgId] = requestDetails

            val outboundIntent =
                    Intent(SENTSMS_ACTION)
                            .apply {
                                putExtra("messageID", msgId)
                                putExtra("uri", newUri.toString())
                            }
                            .also { it.`package` = context.packageName }

        val sentIntentFilter = IntentFilter(SENTSMS_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(
            messageStatusReceiver,
            sentIntentFilter,
            Context.RECEIVER_NOT_EXPORTED
        )
        } else {
        context.registerReceiver(
            messageStatusReceiver,
            sentIntentFilter
        )
        }

            val sentRequestCode = msgId.hashCode()
            val outboundPendingIntent =
                    PendingIntent.getBroadcast(
                            context,
                            sentRequestCode,
                            outboundIntent,
                            PendingIntent.FLAG_IMMUTABLE
                    )

            smsManager.sendTextMessage(
                    formatNumber(context, requestDetails.addresses.first()),
                    null, // originating address
                    requestDetails.body,
                    outboundPendingIntent,
                    outboundPendingIntent,
                    msgId.toLong()
            )
        } catch (e: Exception) {
            Log.e("OutboundMessagingHandler", "Error initiating SMS send: ${e.message}", e)
            return false
        }
        return true
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun sendMms(smsManager: SmsManager, requestDetails: MessageRequestDetails): Boolean {
        try {
            // Create a message with attachments
            var i = 0

            val parts = mutableListOf<MmsPart>()
            // Build Message Parts
            val bodyParts = smsManager.divideMessage(requestDetails.body)
            for (bodyPart in bodyParts) {
                parts.add(
                        MmsPart(
                                seq = i,
                                mimeType = "text/plain",
                                filename = "$i.txt",
                                contentLocation = "$i.txt",
                                text = bodyPart,
                                size = bodyPart.length.toLong(),
                                contentId = "",
                                contentDisposition = "",
                                name = "",
                                charset = 106,
                                data = byteArrayOf()
                        )
                )
                i++
            }

            // Build Attachment Parts
            val attachments = requestDetails.attachmentPaths ?: emptyList<String>()
            for (attachment in attachments) {
                val mimeType = getMimeType(attachment)
                parts.add(
                        MmsPart(
                                seq = i,
                                mimeType = mimeType ?: "text/plain",
                                filename = "$i.txt",
                                contentLocation = "$i.txt",
                                text = "",
                                contentId = "",
                                contentDisposition = "",
                                name = "",
                                charset = 106,
                                data = byteArrayOf()
                        )
                )
                i++
            }

            // Build MMS
            val threadId = requestDetails.threadId
            val mms =
                    MmsObject(
                            contentLocation = EncodedStringValue(""),
                            status = Telephony.Sms.STATUS_PENDING,
                            read = 0,
                            seen = 1,
                            date = System.currentTimeMillis(),
                            messageBox = MESSAGE_BOX_INBOX,
                            messageSize = 0,
                            priority = priority,
                            subscriptionId = smsManager.subscriptionId,
                            textOnly = if (attachments.isEmpty()) 1 else 0,
                            threadId = threadId
                    )

            val newMms = MmsDatabaseWriter.insertMms(context, mms).toMutableMap()
            msgId = newMms["_id"].toString().toLong().toInt()
            channelResultMap[msgId] = requestDetails

            // Build AddressesParts
            val newAddrs: List<Map<String, Any?>> =
                    MmsDatabaseWriter.insertMmsAddrs(
                            context,
                            msgId.toLong(),
                            setOf(),
                            requestDetails.addresses.toSet(),
                            "",
                            106
                    )
            val newParts: List<Map<String, Any?>> =
                    MmsDatabaseWriter.insertMmsParts(context, msgId.toLong(), parts)

            val cleanedRecipients = requestDetails.addresses.map { formatNumber(context, it) }

            newMms["parts"] = newParts
            newMms["addrs"] = newAddrs

            val sendReq =
                    SendReq().apply {
                        // Address headers – *one* EncodedStringValue per recipient
                        val encoded =
                                cleanedRecipients.map { EncodedStringValue(it) }.toTypedArray()
                        to = encoded
                        date = (System.currentTimeMillis() / 1000L).toLong()
                    }

            val sendSettings = Settings()
            sendSettings.useSystemSending = true

            val message: Message =
                    Message(
                            requestDetails.body,
                            requestDetails.addresses.toTypedArray(),
                    )

            for (attachment in requestDetails.attachmentPaths ?: emptyList<String>()) {
                try {

                    // Convert URI string to Uri object
                    val uri = Uri.fromFile(File(attachment))

                    // Get content resolver
                    val contentResolver = context.contentResolver

                    // Load bitmap from URI
                    val bitmap =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                // For API 28 and above
                                val source = ImageDecoder.createSource(contentResolver, uri)
                                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                    decoder.isMutableRequired = true
                                }
                            } else {
                                // For older versions
                                MediaStore.Images.Media.getBitmap(contentResolver, uri)
                            }

                    message.addImage(bitmap)
                } catch (e: Exception) {
                    Log.e("OutboundMessagingHandler", "Failed to load image: ${e.message}", e)
                }
            }

            val outboundIntent =
                    Intent(SENTMMS_ACTION)
                            .apply {
                                putExtra("messageID", msgId)
                                putExtra("message", AnySerializer.encodeToString(newMms))
                                putExtra("parts", AnySerializer.encodeToString(newParts))
                                putExtra("addrs", AnySerializer.encodeToString(newAddrs))
                                putExtra("uri", "${Telephony.Mms.CONTENT_URI}/$msgId")
                            }
                            .also { it.`package` = context.packageName }
            context.registerReceiver(
                    messageStatusReceiver,
                    IntentFilter(SENTMMS_ACTION),
            )

            val sendTransaction = Transaction(context, sendSettings)
            sendTransaction.setExplicitBroadcastForSentMms(outboundIntent)
            sendTransaction.sendNewMessage(message, requestDetails.threadId)
            return true
        } catch (e: Exception) {
            Log.e("OutboundMessagingHandler", "Error initiating MMS send: ${e.message}", e)
            return false
        }
        return true
    }
}
