@file:Suppress("UNCHECKED_CAST")

package io.simplezen.simple_sms.messaging

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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

// ─── Stable PlatformException error codes ─────────────────────────────
// Dart-side callers switch on these strings via PlatformException.code.
// Codes must remain stable across versions; add new codes by appending.
//
// Pre-fix (silent-failure sweep PR): the send path returned a single
// generic `SEND_INITIATION_ERROR` for every failure mode, including
// unreadable attachments — which previously sent EMPTY bytes to the
// recipient instead of failing. Granular codes let Dart surface
// actionable error UI and decide retry semantics per failure class.
const val ERR_NO_TELEPHONY = "NO_TELEPHONY"
const val ERR_ATTACHMENT_UNREADABLE = "ATTACHMENT_UNREADABLE"
const val ERR_INVALID_MESSAGE = "INVALID_MESSAGE"
const val ERR_PERMISSION_DENIED = "PERMISSION_DENIED"
const val ERR_SEND_INITIATION = "SEND_INITIATION_ERROR" // generic fallback

/**
 * Thrown when an outbound MMS attachment file cannot be read. Surfaces
 * to Dart as PlatformException(code = [ERR_ATTACHMENT_UNREADABLE]) so
 * the UI can show "couldn't attach this photo" instead of sending an
 * empty-bytes attachment over the wire.
 */
class AttachmentUnreadableException(
    val attachmentPath: String,
    cause: Throwable
) : RuntimeException("Cannot read attachment '$attachmentPath': ${cause.message}", cause)

/**
 * Maps an exception thrown from [sendSms] / [sendMms] into the typed
 * `(code, message, details)` triple consumed by [MethodChannel.Result.error].
 *
 * Single source of truth for the outbound exception → Dart error
 * contract — referenced by the [onMethodCall] catch block. Add new
 * exception types here when introducing new error codes; do not
 * inline new mappings at the call site.
 */
internal fun mapSendException(e: Throwable): Triple<String, String, Any?> = when (e) {
    is AttachmentUnreadableException -> Triple(
        ERR_ATTACHMENT_UNREADABLE,
        "Cannot read attachment: ${e.attachmentPath}",
        mapOf(
            "attachmentPath" to e.attachmentPath,
            "cause" to (e.cause?.message ?: e.message ?: "unknown")
        )
    )
    is SecurityException -> Triple(
        ERR_PERMISSION_DENIED,
        "Send blocked by missing permission: ${e.message}",
        null
    )
    is IllegalArgumentException -> Triple(
        ERR_INVALID_MESSAGE,
        "Send rejected as invalid: ${e.message}",
        null
    )
    else -> Triple(
        ERR_SEND_INITIATION,
        "Failed to initiate SMS send: ${e.message}",
        null
    )
}

/** Whether an outbound message is dispatched as SMS or MMS. */
internal enum class OutboundRoute { SMS, MMS }

/**
 * Decide the dispatch route for an outbound message.
 *
 * MMS when the message carries attachments OR has multiple recipients;
 * otherwise SMS. Single-recipient text-only sends (any length) go SMS —
 * [sendSms] is multipart-aware via `divideMessage`, so long bodies stay on the
 * SMS path and our [OutboundMessagingReceiver] hears the result.
 *
 * (Pre-fix, `body.length >= 160` was also routed through MMS, which Klinker
 * then internally demoted back to SMS using its OWN sent-intent action rather
 * than our `SENTSMS_ACTION` — so the result broadcast was never heard and the
 * message hung on "Sending…", plus orphan empty MMS rows were written. Keeping
 * the SMS/MMS decision here length-independent is what fixed that.)
 */
internal fun routeMessage(hasAttachments: Boolean, recipientCount: Int): OutboundRoute =
    if (hasAttachments || recipientCount > 1) OutboundRoute.MMS else OutboundRoute.SMS

/**
 * The subscription id to send on (Android S+): the caller's explicit choice
 * when provided, else the system default-SMS subscription via [default].
 */
internal fun selectSubscriptionId(explicit: Int?, default: () -> Int): Int =
    explicit ?: default()

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
                    Log.d(TAG, "Device missing FEATURE_TELEPHONY_MESSAGING")
                    result.error(
                            ERR_NO_TELEPHONY,
                            "Device does not support telephony messaging (no SIM, tablet, or emulator).",
                            null
                    )
                    // Pre-fix this fell through and continued to construct
                    // the SMS/MMS request, which then crashed deeper in
                    // SmsManager.getDefault() on tablets without telephony.
                    return
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
                                    selectSubscriptionId(
                                            requestDetails.subscriptionId,
                                            SmsManager::getDefaultSmsSubscriptionId,
                                    )
                            context.getSystemService(SmsManager::class.java)
                                    .createForSubscriptionId(subIdToUse)
                        } else {
                            @Suppress("DEPRECATION") SmsManager.getDefault()
                        }

                // SMS-vs-MMS routing lives in [routeMessage] (the long-body
                // rationale — the body.length >= 160 / Klinker SENTSMS_ACTION
                // hang + orphan-MMS-rows bug — is documented there).
                //
                // Send-path error routing: sendSms / sendMms throw on failure
                // (no Boolean return — exceptions are the sole failure channel).
                // The single catch + mapSendException helper translates each
                // typed exception into the matching ERR_* code. Add new error
                // codes by extending mapSendException, NOT by adding catch arms
                // here.
                //
                // Successful sends are ACKed asynchronously by the
                // OutboundMessagingReceiver broadcast handler, which reads
                // channelResultMap[msgId].flutterResult.
                try {
                    val route =
                            routeMessage(
                                    hasAttachments =
                                            !requestDetails.attachmentPaths.isNullOrEmpty(),
                                    recipientCount = requestDetails.addresses.size,
                            )
                    when (route) {
                        OutboundRoute.MMS ->
                                sendMms(smsManager = smsManager, requestDetails = requestDetails)
                        OutboundRoute.SMS ->
                                sendSms(smsManager = smsManager, requestDetails = requestDetails)
                    }
                } catch (e: Exception) {
                    channelResultMap.remove(msgId)
                    val (code, message, details) = mapSendException(e)
                    result.error(code, message, details)
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

                        // This callback is from OutboundMessagingReceiver.
                        //
                        // Honor the send result (UNFY-178). A failed *send*
                        // (resultCode != RESULT_OK on a SENT broadcast) must move the
                        // row to the failed box so the consumer derives delivered=false
                        // — previously this wrote STATUS_COMPLETE unconditionally, so a
                        // failed send (e.g. MMS_ERROR_IO_ERROR) showed as sent. SMS uses
                        // the TYPE column, MMS uses MESSAGE_BOX — different columns.
                        // NB: provider-write semantics — verify on a real device
                        // (esp. Samsung MMS msg_box) before relying on it.
                        val isSentEvent =
                                eventType == SENTSMS_ACTION || eventType == SENTMMS_ACTION
                        val sendFailed = isSentEvent && resultCode != Activity.RESULT_OK
                        val statusUpdate =
                                ContentValues().apply {
                                    when {
                                        sendFailed && eventType == SENTMMS_ACTION ->
                                                put(
                                                        Telephony.Mms.MESSAGE_BOX,
                                                        Telephony.Mms.MESSAGE_BOX_FAILED
                                                )
                                        sendFailed ->
                                                put(
                                                        Telephony.Sms.TYPE,
                                                        Telephony.Sms.MESSAGE_TYPE_FAILED
                                                )
                                        eventType == SENTMMS_ACTION ->
                                                put(
                                                        Telephony.Mms.Sent.STATUS,
                                                        Telephony.TextBasedSmsColumns.STATUS_COMPLETE
                                                )
                                        else ->
                                                put(
                                                        Telephony.Sms.Sent.STATUS,
                                                        Telephony.TextBasedSmsColumns.STATUS_COMPLETE
                                                )
                                    }
                                }
                        context.contentResolver.update(messageUri, statusUpdate, null, null)
                        if (sendFailed) {
                            Log.w(
                                    "OutboundMessagingHandler",
                                    "Send FAILED for messageId=$messageId ($eventType, " +
                                            "resultCode=$resultCode, $statusString) — marked failed.",
                            )
                        }

                        // Read the just-updated row back through simple_query (Rule 1).
                        // The manual FIELD_TYPE_* walk this replaces was an inlined
                        // re-implementation of ContentQuery.drainRows; the
                        // parts/recipients reads just below already route through Query().
                        // Message rows (content://sms/N, content://mms/N) are INTEGER/TEXT
                        // only — no BLOB columns — so ContentQuery's BLOB-coalescing is
                        // moot here (verified vs the AOSP sms/pdu schema + codec; UNFY-156).
                        val finalMessage: MutableMap<String, Any?> =
                                Query(context)
                                        .query(QueryObj(contentUri = messageUri.toString()))
                                        .firstOrNull()
                                        ?.toMutableMap()
                                        ?: mutableMapOf()

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

    /**
     * Initiates an SMS send. Returns nothing — failures propagate via
     * typed exceptions (see [mapSendException]) and successes ACK
     * asynchronously through the [OutboundMessagingReceiver] broadcast.
     * Pre-fix this returned `Boolean` but the boolean was always `true`
     * on the success path, so it was a vestigial second error channel
     * that duplicated the exception-based one.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun sendSms(
            smsManager: SmsManager,
            requestDetails: MessageRequestDetails,
    ) {
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
        } catch (e: SecurityException) {
            // Permission revoked between request and send (rare race).
            Log.e(TAG, "SMS send blocked by missing permission: ${e.message}", e)
            throw e
        } catch (e: IllegalArgumentException) {
            // Malformed message (empty body + no recipients, etc.).
            Log.e(TAG, "SMS send rejected as invalid: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            // Unexpected failure — log + rethrow so onMethodCall can
            // surface the actual error code instead of swallowing as a
            // generic SEND_INITIATION_ERROR with no detail.
            Log.e(TAG, "Error initiating SMS send: ${e.message}", e)
            throw e
        }
    }

    /**
     * Initiates an MMS send. See [sendSms] for the return-via-exception
     * contract; the same applies here.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun sendMms(smsManager: SmsManager, requestDetails: MessageRequestDetails) {
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
                // CRITICAL: never substitute empty bytes here. A failed
                // read must abort the send so the recipient never sees
                // a zero-byte attachment. The throw propagates to the
                // sendMms outer catch which maps it to a typed
                // PlatformException for the Dart UI.
                val bytes = try {
                    file.readBytes()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read attachment $attachment for DB record: ${e.message}", e)
                    throw AttachmentUnreadableException(attachment, e)
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
                        // `to`/`date` are now read-only `val` getters on the Kotlin
                        // MultimediaMessagePdu base, so set them via the methods (the
                        // getX/setX pair the Java class exposed) rather than property
                        // assignment — behaviour-identical (setTo is SendReq's own).
                        setTo(encoded)
                        setDate(System.currentTimeMillis() / 1000L)
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
                    // CRITICAL: this is the wire-transmit loop — bytes added
                    // here are what the recipient actually receives. A
                    // silent skip used to mean "MMS transmits without this
                    // attachment, sender sees 'sent', recipient sees a
                    // missing photo." Throw so the send aborts atomically.
                    Log.e(TAG, "Failed to load attachment $attachment for transmission: ${e.message}", e)
                    throw AttachmentUnreadableException(attachment, e)
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
        } catch (e: AttachmentUnreadableException) {
            // The send is aborted before any wire activity — recipient
            // never sees a partial/empty attachment. Rethrow to
            // onMethodCall which surfaces ERR_ATTACHMENT_UNREADABLE.
            Log.e(TAG, "MMS send aborted: ${e.message}", e)
            throw e
        } catch (e: SecurityException) {
            Log.e(TAG, "MMS send blocked by missing permission: ${e.message}", e)
            throw e
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "MMS send rejected as invalid: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating MMS send: ${e.message}", e)
            throw e
        }
    }
}
