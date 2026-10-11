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
import android.telephony.SmsManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import io.simplezen.simple_sms.mms.sending.Message
import io.simplezen.simple_sms.mms.sending.Settings
import io.simplezen.simple_sms.mms.sending.Transaction
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.simplezen.simple_sms.messaging.MmsDatabaseWriter.insertSms
import io.simplezen.simple_sms.queries.Query
import io.simplezen.simple_sms.queries.QueryObj
import java.io.File
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
     * failed part or once every part has succeeded, else null. The owner stops
     * recording once a send settles, so extra reports never reach here.
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
 * Builds the status broadcasts for the SMS row at [messageUri]. Delivery
 * reports get their own [DELIVEREDSMS_ACTION] so they are never read as send
 * results (UNFY-283). Every part of a multipart send shares both intents; the
 * receiver counts the SENT reports per part.
 */
internal fun smsStatusIntents(context: Context, messageId: Int, messageUri: Uri): SmsStatusIntents {
    fun broadcast(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            messageId,
            Intent(action)
                .putExtra("messageID", messageId)
                .putExtra("uri", messageUri.toString())
                .setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
    return SmsStatusIntents(sent = broadcast(SENTSMS_ACTION), delivered = broadcast(DELIVEREDSMS_ACTION))
}

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

class OutboundMessagingHandler() : Service(), MethodChannel.MethodCallHandler {
    private lateinit var context: Context // Use application context for receivers
    private var messageStatusReceiver: OutboundMessagingReceiver? = null
    @VisibleForTesting
    internal val channelResultMap = ConcurrentHashMap<Uri, MessageRequestDetails>()

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
                // channelResultMap[messageUri].flutterResult.
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
                    requestDetails.messageUri?.let { uri ->
                        channelResultMap.remove(uri)
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
                        "messageUri" to requestDetails.messageUri?.toString(),
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

                        onStatusBroadcast(messageId, messageUri, eventType, statusString, resultCode)
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

    /**
     * Handles one send-status broadcast: persists the carrier result on the
     * message row, then — once the SENT parts of a send this handler owns
     * settle its outcome — sends that send's Flutter reply exactly once.
     *
     * Failures here happen after the carrier has handled the send, so they
     * settle the pending send as [ERR_SEND_ACK_FAILED] instead of escaping the
     * receiver, which would crash the process and leave the Dart future
     * unsettled (UNFY-282). The reply itself is sent once, outside any catch.
     */
    private fun onStatusBroadcast(
            messageId: Int,
            messageUri: Uri,
            eventType: String,
            statusString: String,
            resultCode: Int,
    ) {
        val isSentEvent = eventType == SENTSMS_ACTION || eventType == SENTMMS_ACTION
        val sendFailed = isSentEvent && resultCode != Activity.RESULT_OK
        // Only SENT broadcasts settle a send. Delivery reports, and broadcasts
        // for sends this handler doesn't own, just persist their status.
        val settled =
                if (isSentEvent) {
                    claimSettledSend(
                            messageUri,
                            CarrierOutcome(eventType, resultCode, statusString, sent = !sendFailed),
                    )
                } else {
                    null
                }
        val persistFailure: Exception? =
                try {
                    persistStatus(messageId, messageUri, eventType, statusString, resultCode, sendFailed)
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Failed writing $eventType status for $messageUri", e)
                    e
                }
        if (settled == null) return
        val ack =
                persistFailure?.let { Ack.Failed(SendAckFailedException(messageUri, settled.outcome, it)) }
                        ?: try {
                            ackFromRow(settled.outcome, messageId, messageUri)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed acknowledging $eventType for $messageUri", e)
                            Ack.Failed(SendAckFailedException(messageUri, settled.outcome, e))
                        }
        when (ack) {
            is Ack.Recorded -> settled.result.success(ack.payload)
            is Ack.Failed -> {
                val (code, message, details) = mapSendException(ack.error)
                settled.result.error(code, message, details)
            }
        }
    }

    /** A pending send claimed for settlement, with its settled carrier outcome. */
    private class SettledSend(val result: MethodChannel.Result, val outcome: CarrierOutcome)

    /** The single reply a settled send receives. */
    private sealed interface Ack {
        /** The encoded message row. */
        class Recorded(val payload: String) : Ack

        class Failed(val error: SendAckException) : Ack
    }

    /**
     * Records [part] against the send pending on [messageUri], if this handler
     * owns one, and claims it once its outcome is settled. Returns null while
     * parts are outstanding or when nothing is pending here.
     */
    private fun claimSettledSend(messageUri: Uri, part: CarrierOutcome): SettledSend? {
        val pending = channelResultMap[messageUri] ?: return null
        val outcome = pending.sentParts.record(part) ?: return null
        channelResultMap.remove(messageUri, pending)
        return SettledSend(pending.flutterResult, outcome)
    }

    /**
     * Honor the send result (UNFY-178). A failed *send* (resultCode !=
     * RESULT_OK on a SENT broadcast) must move the row to the failed box so
     * the consumer derives delivered=false — previously this wrote
     * STATUS_COMPLETE unconditionally, so a failed send (e.g.
     * MMS_ERROR_IO_ERROR) showed as sent. SMS uses the TYPE column, MMS uses
     * MESSAGE_BOX — different columns. Each multipart SMS part writes its own
     * result; a failed part's TYPE survives later successful parts, which only
     * write STATUS. NB: provider-write semantics — verify on a real device
     * (esp. Samsung MMS msg_box) before relying on it.
     */
    private fun persistStatus(
            messageId: Int,
            messageUri: Uri,
            eventType: String,
            statusString: String,
            resultCode: Int,
            sendFailed: Boolean,
    ) {
        val statusUpdate =
                ContentValues().apply {
                    when {
                        sendFailed && eventType == SENTMMS_ACTION ->
                                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_FAILED)
                        sendFailed -> put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
                        eventType == SENTMMS_ACTION -> {
                            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_SENT)
                            put(Telephony.Mms.STATUS, Telephony.Sms.STATUS_COMPLETE)
                            put(Telephony.Mms.READ, 1)
                            put(Telephony.Mms.SEEN, 1)
                            put(Telephony.Mms.DATE_SENT, System.currentTimeMillis() / 1000L)
                        }
                        else ->
                                put(
                                        Telephony.Sms.Sent.STATUS,
                                        Telephony.TextBasedSmsColumns.STATUS_COMPLETE,
                                )
                    }
                }
        context.contentResolver.update(messageUri, statusUpdate, null, null)
        if (sendFailed) {
            Log.w(
                    TAG,
                    "Send FAILED for messageId=$messageId ($eventType, " +
                            "resultCode=$resultCode, $statusString) — marked failed.",
            )
        }
    }

    /**
     * Builds the ack for a settled send from the just-updated message row, or
     * [ERR_MESSAGE_ROW_MISSING] when the row is gone (UNFY-160).
     */
    private fun ackFromRow(outcome: CarrierOutcome, messageId: Int, messageUri: Uri): Ack {
        // Read the just-updated row back through simple_query (Rule 1).
        // Message rows (content://sms/N, content://mms/N) are INTEGER/TEXT
        // only — no BLOB columns — so ContentQuery's BLOB-coalescing is
        // moot here (verified vs the AOSP sms/pdu schema + codec; UNFY-156).
        val row =
                Query(context).query(QueryObj(contentUri = messageUri.toString())).firstOrNull()
        if (row == null) {
            val missing = MessageRowMissingException(messageUri, outcome)
            Log.w(TAG, "${missing.message} (resultCode=${outcome.resultCode}, ${outcome.statusString})")
            return Ack.Failed(missing)
        }
        val finalMessage: MutableMap<String, Any?> = row.toMutableMap()
        finalMessage["uri"] = messageUri.toString()
        if (outcome.eventType == SENTMMS_ACTION) {
            finalMessage["parts"] =
                    Query(context)
                            .query(QueryObj(Telephony.Mms.Part.getPartUriForMessage(messageId.toString()).toString()))
            finalMessage["recipients"] =
                    Query(context)
                            .query(QueryObj(Telephony.Mms.Addr.getAddrUriForMessage(messageId.toString()).toString()))
        }
        return Ack.Recorded(AnySerializer.encodeToString(finalMessage))
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
            val msgId = ContentUris.parseId(newUri).toInt()
            requestDetails.messageUri = newUri
            channelResultMap[newUri] = requestDetails

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
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
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

            val outboundIntent = Intent(SENTMMS_ACTION).setPackage(context.packageName)
            val sendTransaction = Transaction(context, sendSettings)
            sendTransaction.setExplicitBroadcastForSentMms(outboundIntent)
            sendTransaction.setMmsPersistedCallback { uri ->
                requestDetails.messageUri = uri
                channelResultMap[uri] = requestDetails
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
