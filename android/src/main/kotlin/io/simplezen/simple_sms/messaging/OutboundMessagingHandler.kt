@file:Suppress("UNCHECKED_CAST")

package io.simplezen.simple_sms.messaging

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
                    Log.d("OutboundMessagingHandler", "Device missing FEATURE_TELEPHONY")
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

                // Store details with the Flutter result callback.
                //
                // Routing:
                //   - Has attachments OR multiple recipients     → MMS
                //   - Single recipient + text-only (any length)  → SMS (multipart-aware)
                //
                // Pre-fix the second branch routed `body.length >= 160`
                // through MMS too. That dispatched to Klinker
                // `Transaction.sendNewMessage`, which then internally
                // demoted text-only single-recipient sends back to SMS
                // — using Klinker's OWN sentPI action
                // (`<package>.SMS_SENT`), not our
                // `SENTSMS_ACTION = "com.simplezen.simple_sms.SMS_SENT"`.
                // The SMS sent successfully but our
                // [OutboundMessagingReceiver] never heard the result,
                // so the message stayed stuck on "Sending…" forever.
                // Plus Klinker's pre-prep wrote orphan MMS rows to
                // the provider for sends that ultimately went out as
                // SMS (visible as empty `MMS-NNN` rows in the
                // converter's "persisted EMPTY" warnings).
                //
                // [sendSms] now uses `divideMessage` + the multipart
                // SmsManager API for bodies that exceed a single
                // segment, so long single-recipient text bypasses
                // Klinker entirely and our receiver fires correctly.
                val sendResult =
                        if (requestDetails.attachmentPaths != null &&
                                        requestDetails.attachmentPaths.isNotEmpty()
                        ) {
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

    /**
     * Explicit teardown for handlers constructed via the secondary
     * [constructor(Context)] rather than spun up as a Service. Android
     * never fires [onDestroy] on those, so the receiver we register in
     * [setupSmsReceiver] would otherwise leak forever.
     *
     * Called from `SimpleSmsPlugin.releaseHandler` when the messenger
     * this handler was registered on goes away (plugin detach,
     * background engine teardown).
     */
    fun release() {
        unregisterSmsReceiver()
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
            Log.d("OutboundMessagingHandler", "Sending SMS to ${requestDetails.addresses.size} recipient(s)")
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

            val sentRequestCode = msgId.hashCode()
            val outboundPendingIntent =
                    PendingIntent.getBroadcast(
                            context,
                            sentRequestCode,
                            outboundIntent,
                            PendingIntent.FLAG_IMMUTABLE
                    )

            val rawDest = requestDetails.addresses.first()
            // formatNumber may return null for non-E.164-parseable
            // inputs (shortcodes, alphanumeric). Fall back to the raw
            // input — SmsManager.sendTextMessage accepts shortcodes
            // and other carrier-specific destinations directly.
            val destAddress = formatNumber(context, rawDest) ?: rawDest

            // Use SmsManager's segmenter to decide whether the body
            // fits in a single SMS or needs multipart concatenation.
            // For bodies that fit (≤ 160 7-bit / 70 UCS-2 chars) we
            // call the single-message API; for longer bodies we use
            // sendMultipartTextMessage with one PendingIntent per
            // part (all pointing at the same broadcast — the receiver
            // tolerates duplicate fires).
            //
            // Doing this in our own send path (instead of routing to
            // Klinker's Transaction.sendNewMessage for long bodies)
            // ensures the sent-result PendingIntent broadcasts via
            // SENTSMS_ACTION, which is what
            // [OutboundMessagingReceiver] listens for. Klinker's
            // sentPI uses `<package>.SMS_SENT` (a different action),
            // so going through Klinker for long bodies left the
            // message stuck at "Sending…" with no status callback.
            val parts = smsManager.divideMessage(requestDetails.body)
            if (parts.size <= 1) {
                smsManager.sendTextMessage(
                        destAddress,
                        null, // originating address
                        requestDetails.body,
                        outboundPendingIntent,
                        outboundPendingIntent,
                        msgId.toLong()
                )
            } else {
                val sentPendingIntents = ArrayList<PendingIntent>(parts.size).apply {
                    repeat(parts.size) { add(outboundPendingIntent) }
                }
                val deliveredPendingIntents = ArrayList<PendingIntent>(parts.size).apply {
                    repeat(parts.size) { add(outboundPendingIntent) }
                }
                smsManager.sendMultipartTextMessage(
                        destAddress,
                        null, // originating address
                        parts,
                        sentPendingIntents,
                        deliveredPendingIntents,
                        msgId.toLong()
                )
            }
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
                val file = File(attachment)
                val bytes = try {
                    file.readBytes()
                } catch (e: Exception) {
                    Log.e("OutboundMessagingHandler", "Failed to read attachment $attachment: ${e.message}", e)
                    byteArrayOf()
                }
                val name = file.name
                parts.add(
                        MmsPart(
                                seq = i,
                                mimeType = mimeType ?: "application/octet-stream",
                                filename = name,
                                contentLocation = name,
                                text = "",
                                size = bytes.size.toLong(),
                                contentId = "",
                                contentDisposition = "",
                                name = name,
                                charset = 106,
                                data = bytes
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
                            priority = 0,
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

            // Preserve raw values when formatNumber can't normalize
            // (shortcodes, alphanumeric senders). Downstream comparisons
            // tolerate both forms via PhoneNumberUtils.areSamePhoneNumber.
            val cleanedRecipients = requestDetails.addresses.map { formatNumber(context, it) ?: it }

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
                    val file = File(attachment)
                    val mimeType = getMimeType(attachment) ?: "application/octet-stream"

                    if (mimeType.startsWith("image/")) {
                        val uri = Uri.fromFile(file)
                        val contentResolver = context.contentResolver
                        val bitmap =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    val source = ImageDecoder.createSource(contentResolver, uri)
                                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                        decoder.isMutableRequired = true
                                    }
                                } else {
                                    MediaStore.Images.Media.getBitmap(contentResolver, uri)
                                }
                        message.addImage(bitmap)
                    } else {
                        message.addMedia(file.readBytes(), mimeType, file.name)
                    }
                } catch (e: Exception) {
                    Log.e("OutboundMessagingHandler", "Failed to load attachment $attachment: ${e.message}", e)
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

            val sendTransaction = Transaction(context, sendSettings)
            sendTransaction.setExplicitBroadcastForSentMms(outboundIntent)
            sendTransaction.sendNewMessage(message, requestDetails.threadId)
            return true
        } catch (e: Exception) {
            Log.e("OutboundMessagingHandler", "Error initiating MMS send: ${e.message}", e)
            return false
        }
    }
}
