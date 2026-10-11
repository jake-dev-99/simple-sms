@file:Suppress("UNCHECKED_CAST")

package io.simplezen.simple_sms.messaging

import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import io.simplezen.simple_sms.mms.sending.Message
import io.simplezen.simple_sms.mms.sending.Settings
import io.simplezen.simple_sms.mms.sending.Transaction
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.simplezen.simple_sms.messaging.MmsDatabaseWriter.insertSms
import java.io.File

// Define these constants here or import them
const val SENTSMS_ACTION = "com.simplezen.simple_sms.SMS_SENT"
const val SENTMMS_ACTION = "com.simplezen.simple_sms.MMS_SENT"
const val DELIVEREDSMS_ACTION = "com.simplezen.simple_sms.SMS_DELIVERED"
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
// The two codes below settle a send AFTER the carrier reported its result.
// Their details carry that outcome (`sent`) so Dart can tell an accepted send
// from a failed one and never blindly resend.
// The message row was gone when read back to build the ack (UNFY-160).
const val ERR_MESSAGE_ROW_MISSING = "MESSAGE_ROW_MISSING"
// The provider failed while writing the status or reading the row back
// (UNFY-282).
const val ERR_SEND_ACK_FAILED = "SEND_ACK_FAILED"

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
 * The carrier's result for one outbound send, as settled from its SENT
 * broadcast(s). [eventType] disambiguates [resultCode], whose enum differs
 * between SMS and MMS broadcasts; [sent] is false only when the carrier
 * reported a failure.
 */
internal data class CarrierOutcome(
    val eventType: String,
    val resultCode: Int,
    val statusString: String,
    val sent: Boolean,
)

/**
 * Raised on the send-status broadcast path when a send whose carrier
 * [outcome] is already known cannot be acked with its message row. The
 * pending send is settled with an error, never acked as a success carrying
 * only the URI.
 */
internal sealed class SendAckException(
    val messageUri: Uri,
    val outcome: CarrierOutcome,
    override val message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    /** The stable Dart error code; every subclass settles with its own. */
    abstract val code: String

    /** The Dart-facing details: the carrier outcome plus any [extraDetails]. */
    fun ackDetails(): Map<String, Any?> = mapOf(
        "messageUri" to messageUri.toString(),
        "eventType" to outcome.eventType,
        "resultCode" to outcome.resultCode,
        "statusString" to outcome.statusString,
        "sent" to outcome.sent,
    ) + extraDetails()

    protected open fun extraDetails(): Map<String, Any?> = emptyMap()
}

/**
 * The message row was absent on read-back — deleted between the status
 * `update` and the re-read. Mirrors the missing-just-persisted-row check in
 * [InboundMmsPersister] (UNFY-160). A provider that returns no cursor also
 * lands here until `simple_query` can report it separately (UNFY-284).
 */
internal class MessageRowMissingException(messageUri: Uri, outcome: CarrierOutcome) :
    SendAckException(
        messageUri,
        outcome,
        "Message row missing on read-back after ${outcome.eventType}: $messageUri",
    ) {
    override val code = ERR_MESSAGE_ROW_MISSING
}

/**
 * The ack could not be built — the provider threw while writing the send
 * status or reading the row back, or the row could not be encoded
 * (UNFY-282). Distinct from the initiation codes: the carrier has already
 * handled this send.
 */
internal class SendAckFailedException(
    messageUri: Uri,
    outcome: CarrierOutcome,
    val failure: Throwable,
) : SendAckException(
    messageUri,
    outcome,
    "Could not acknowledge ${outcome.eventType} for $messageUri: ${failure.message}",
    failure,
) {
    override val code = ERR_SEND_ACK_FAILED

    override fun extraDetails(): Map<String, Any?> =
        mapOf("cause" to (failure.message ?: failure.javaClass.name))
}

/**
 * Collects the per-part SENT results of one outbound send. A multipart SMS
 * fires one SENT broadcast per part (the documented
 * `SmsManager.sendMultipartTextMessage` contract). The send counts as sent
 * only once every part has reported success; the first failing part settles
 * it immediately as failed, since the outcome is then known (UNFY-283).
 */
internal class SentPartsAggregator {
    private var expected = 1
    private var reported = 0

    /** Sets how many SENT reports to wait for; call before the send starts. */
    fun expectParts(count: Int) {
        require(count >= 1) { "A send has at least one part, got $count" }
        check(reported == 0) { "Part count changed after $reported report(s)" }
        expected = count
    }

    /**
     * Records one part's result; returns the settled outcome on the first
     * failed part or once every part has succeeded, else null. A settled send
     * is no longer tracked, so extra reports never reach here.
     */
    fun record(part: CarrierOutcome): CarrierOutcome? {
        reported++
        return if (!part.sent || reported >= expected) part else null
    }
}

/**
 * Maps an exception thrown from [sendSms] / [sendMms], or raised by the
 * send-status ack path, into the typed `(code, message, details)` triple
 * consumed by [MethodChannel.Result.error].
 *
 * Single source of truth for the outbound exception → Dart error
 * contract — referenced by the [onMethodCall] catch block and the
 * send-status receiver. Add new exception types here when introducing new
 * error codes; do not inline new mappings at the call site.
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
    is SendAckException -> Triple(e.code, e.message, e.ackDetails())
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

/** The SENT and DELIVERED status broadcasts for one outbound SMS row. */
internal class SmsStatusIntents(val sent: PendingIntent, val delivered: PendingIntent)

/**
 * Builds the status broadcasts for the SMS row at [messageUri]. Every part of
 * a multipart send shares both intents; the receiver counts the SENT reports
 * per part (UNFY-283).
 *
 * Both target the manifest-declared [OutboundMessagingReceiver], so Android
 * delivers them even when the app process is cached or has gone — as after an
 * inline notification reply from a headless engine (UNFY-306).
 *
 * - SENT records the result on the row and settles the pending send (see
 *   [OutboundSendResults]). It needs no fill-in and stays immutable.
 * - Delivery reports get their own [DELIVEREDSMS_ACTION] so they are never
 *   read as send results (UNFY-293). That intent is mutable so the platform
 *   can attach the report (`pdu`, `format` extras); the row travels as the
 *   intent data, which a fill-in cannot replace.
 *
 * FLAG_CANCEL_CURRENT: a reused row id must not inherit an earlier send's
 * still-pending intents, whose late reports would land on the new row.
 */
internal fun smsStatusIntents(context: Context, messageId: Int, messageUri: Uri): SmsStatusIntents {
    val sent = statusReceiverIntent(context, SENTSMS_ACTION)
        .putExtra("messageID", messageId)
        .putExtra("uri", messageUri.toString())
    val delivered = statusReceiverIntent(context, DELIVEREDSMS_ACTION).setData(messageUri)
    val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    return SmsStatusIntents(
        sent = PendingIntent.getBroadcast(
            context, messageId, sent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        ),
        delivered = PendingIntent.getBroadcast(
            context, messageId, delivered, mutable or PendingIntent.FLAG_CANCEL_CURRENT,
        ),
    )
}

/**
 * A status broadcast for [action], aimed at the manifest-declared
 * [OutboundMessagingReceiver]. It declares no intent-filter, so every status
 * intent must name it explicitly; Android then delivers the broadcast even
 * when the app process is cached or has gone (UNFY-293, UNFY-306).
 */
internal fun statusReceiverIntent(context: Context, action: String): Intent =
    Intent(action).setComponent(ComponentName(context, OutboundMessagingReceiver::class.java))

/** A synchronous initiation error must settle any row already persisted. */
internal fun markPersistedSendFailed(context: Context, uri: Uri) {
    val values = ContentValues().apply {
        when (uri.authority) {
            Telephony.Mms.CONTENT_URI.authority ->
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_FAILED)
            Telephony.Sms.CONTENT_URI.authority ->
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
            else -> throw IllegalArgumentException("Unexpected message URI: $uri")
        }
    }
    check(context.contentResolver.update(uri, values, null, null) == 1) {
        "Failed to mark persisted message as failed: $uri"
    }
}

/**
 * Starts outbound sends for the messaging channel. It registers no receiver:
 * the sends it starts are tracked in [OutboundSendResults], which the
 * manifest-declared [OutboundMessagingReceiver] settles, so each engine holds
 * its own handler.
 */
class OutboundMessagingHandler() : Service(), MethodChannel.MethodCallHandler {
    private lateinit var context: Context // Application context

    /**
     * One pending outbound send. A plain class, not a data class: it carries
     * mutable per-send state, and the pending-send map relies on identity.
     */
    class MessageRequestDetails(
            val threadId: Long,
            val addresses: List<String>,
            val body: String,
            val subscriptionId: Int? = null,
            val attachmentPaths: List<String>? = null,
            val flutterResult: MethodChannel.Result,
            var messageUri: Uri? = null,
    ) {
        /** Settles this send from its SENT part reports (UNFY-283). */
        internal val sentParts = SentPartsAggregator()
    }

    // Constructor used by Flutter plugin registration
    constructor(context: Context) : this() {
        this.context = context.applicationContext
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
                // Building the request is inside the catch too: a bad argument,
                // an unresolvable recipient set or a revoked permission there
                // also maps to its ERR_* code.
                //
                // Successful sends are ACKed asynchronously by the
                // OutboundMessagingReceiver broadcast handler, which settles
                // the send this handler tracked in OutboundSendResults.
                var requestDetails: MessageRequestDetails? = null
                try {
                    val message = call.arguments as Map<String, Any?>
                    val recipients = message["recipients"] as List<String>
                    val details =
                            MessageRequestDetails(
                                    threadId =
                                            Telephony.Threads.getOrCreateThreadId(
                                                    context,
                                                    recipients.toSet(),
                                            ),
                                    addresses = recipients,
                                    body = message["body"] as String,
                                    subscriptionId = message["subscriptionId"] as Int?,
                                    attachmentPaths = message["attachmentPaths"] as List<String>?,
                                    flutterResult = result,
                            )
                    requestDetails = details

                    val smsManager: SmsManager =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val subIdToUse =
                                        selectSubscriptionId(
                                                details.subscriptionId,
                                                SmsManager::getDefaultSmsSubscriptionId,
                                        )
                                context.getSystemService(SmsManager::class.java)
                                        .createForSubscriptionId(subIdToUse)
                            } else {
                                @Suppress("DEPRECATION") SmsManager.getDefault()
                            }

                    val route =
                            routeMessage(
                                    hasAttachments =
                                            !details.attachmentPaths.isNullOrEmpty(),
                                    recipientCount = details.addresses.size,
                            )
                    when (route) {
                        OutboundRoute.MMS ->
                                sendMms(smsManager = smsManager, requestDetails = details)
                        OutboundRoute.SMS ->
                                sendSms(smsManager = smsManager, requestDetails = details)
                    }
                } catch (e: Exception) {
                    val persistedUri = requestDetails?.messageUri
                    persistedUri?.let { uri ->
                        OutboundSendResults.untrack(uri)
                        try {
                            markPersistedSendFailed(context, uri)
                        } catch (stateError: Exception) {
                            Log.e(TAG, "Failed to settle message after send initiation error: $uri", stateError)
                            e.addSuppressed(stateError)
                        }
                    }
                    val (code, message, details) = mapSendException(e)
                    val errorDetails = if (e.suppressed.isEmpty()) details else mapOf(
                        "sendDetails" to details,
                        "messageUri" to persistedUri?.toString(),
                        "providerStateErrors" to e.suppressed.map { it.message },
                    )
                    result.error(code, message, errorDetails)
                }
            }
            else -> result.notImplemented()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // If the service can be created independently of the constructor taking context
        // ensure context is initialized.
        if (!::context.isInitialized) {
            this.context = applicationContext
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
    private fun sendSms(
            smsManager: SmsManager,
            requestDetails: MessageRequestDetails,
    ) {
        try {
            Log.d("OutboundMessagingHandler", "Sending SMS to ${requestDetails.addresses.size} recipient(s)")
            val newSms = insertSms(context, requestDetails)
            val newUri = newSms["uri"] as Uri
            val msgId = ContentUris.parseId(newUri).toInt()
            requestDetails.messageUri = newUri
            OutboundSendResults.track(this, newUri, requestDetails)

            val statusIntents = smsStatusIntents(context, msgId, newUri)

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
            // part (all pointing at the same broadcast). The pending
            // send settles once every part reports success, or on the
            // first failed part (UNFY-283).
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
            requestDetails.sentParts.expectParts(maxOf(parts.size, 1))
            if (parts.size <= 1) {
                smsManager.sendTextMessage(
                        destAddress,
                        null, // originating address
                        requestDetails.body,
                        statusIntents.sent,
                        statusIntents.delivered,
                        msgId.toLong()
                )
            } else {
                val sentPendingIntents = ArrayList<PendingIntent>(parts.size).apply {
                    repeat(parts.size) { add(statusIntents.sent) }
                }
                val deliveredPendingIntents = ArrayList<PendingIntent>(parts.size).apply {
                    repeat(parts.size) { add(statusIntents.delivered) }
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
    private fun sendMms(smsManager: SmsManager, requestDetails: MessageRequestDetails) {
        try {
            // Persist the composed PDU once in Transaction. The result callback
            // is bound below to that exact row, including its wire attachments.
            val sendSettings = Settings()
            sendSettings.useSystemSending = true
            sendSettings.setSubscriptionId(smsManager.subscriptionId)

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

            val outboundIntent = statusReceiverIntent(context, SENTMMS_ACTION)
            val sendTransaction = Transaction(context, sendSettings)
            sendTransaction.setExplicitBroadcastForSentMms(outboundIntent)
            sendTransaction.setMmsPersistedCallback { uri ->
                requestDetails.messageUri = uri
                OutboundSendResults.track(this, uri, requestDetails)
            }
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
