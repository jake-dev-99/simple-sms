@file:Suppress("UNCHECKED_CAST")

package io.simplezen.simple_sms.messaging

import android.app.Activity
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.annotation.VisibleForTesting
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
// No SENT result arrived within [SEND_RESULT_TIMEOUT_MS] (e.g. the radio
// process crashed mid-send): the outcome is unknown, so `sent` is null
// (UNFY-299).
const val ERR_SEND_RESULT_TIMEOUT = "SEND_RESULT_TIMEOUT"

/**
 * A pending send got no SENT result within [timeoutMs]. Whether the message
 * went out is unknown; its row is marked failed (UNFY-299).
 */
internal class SendResultTimeoutException(val messageUri: Uri, val timeoutMs: Long) :
    Exception("No SENT result for $messageUri within ${timeoutMs / 1000}s; whether it was sent is unknown")

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
    is SendResultTimeoutException -> Triple(
        ERR_SEND_RESULT_TIMEOUT,
        e.message.orEmpty(),
        mapOf("messageUri" to e.messageUri.toString(), "sent" to null, "timeoutMs" to e.timeoutMs),
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

/**
 * One part of an outbound SMS, as its status intents carry it (UNFY-180).
 *
 * @param token the send's [SmsPartLedger] token.
 * @param rowId the message row's `_id`.
 * @param index the part's zero-based index.
 * @param count how many parts the send has.
 */
internal data class SmsPartRef(val token: String, val rowId: Long, val index: Int, val count: Int) {
    /**
     * Whether this part's own result is the message's: a one-part SMS. Used
     * when [SmsPartLedger] is unavailable and the parts cannot be added up.
     */
    val standsForMessage: Boolean get() = count == 1
}

private const val EXTRA_PART_TOKEN = "partToken"
private const val EXTRA_PART_ROW_ID = "partRowId"
private const val EXTRA_PART_INDEX = "partIndex"
private const val EXTRA_PART_COUNT = "partCount"

/**
 * The part a status intent reports on, or `null` for an intent built before
 * parts were tracked (UNFY-180), which carries none.
 */
internal fun Intent.smsPartRef(): SmsPartRef? {
    val token = getStringExtra(EXTRA_PART_TOKEN) ?: return null
    return SmsPartRef(
        token,
        getLongExtra(EXTRA_PART_ROW_ID, -1L),
        getIntExtra(EXTRA_PART_INDEX, -1),
        getIntExtra(EXTRA_PART_COUNT, -1),
    )
}

/** The SENT and DELIVERED status broadcasts for one part of an outbound SMS. */
internal class SmsStatusIntents(val sent: PendingIntent, val delivered: PendingIntent)

/**
 * Builds the status broadcasts for one [part] of the SMS row at [messageUri].
 * Each part gets its own pair, told apart by their identifier, so every
 * part's result reaches [SmsPartLedger], which settles the row from all of
 * them (UNFY-180).
 *
 * Both target the manifest-declared [OutboundMessagingReceiver], so they are
 * recorded even after the app process has gone (UNFY-293, UNFY-299).
 *
 * - SENT is routed through [SendResultRouter]. It needs no fill-in and stays
 *   immutable.
 * - Delivery reports get their own [DELIVEREDSMS_ACTION] so they are never
 *   read as send results. That intent is mutable so the platform can
 *   attach the report (`pdu`, `format` extras); the row travels as the intent
 *   data, which a fill-in cannot replace.
 *
 * A reused row id never inherits an earlier send's results: they carry that
 * send's token, which [SmsPartLedger.start] forgets, or, from a send made
 * before parts were tracked, no token at all ([isStaleUntrackedSmsResult]).
 */
internal fun smsStatusIntents(context: Context, messageId: Int, messageUri: Uri, part: SmsPartRef): SmsStatusIntents {
    fun Intent.forPart(): Intent = setIdentifier("${part.token}:${part.index}")
        .putExtra(EXTRA_PART_TOKEN, part.token)
        .putExtra(EXTRA_PART_ROW_ID, part.rowId)
        .putExtra(EXTRA_PART_INDEX, part.index)
        .putExtra(EXTRA_PART_COUNT, part.count)
        .setComponent(ComponentName(context, OutboundMessagingReceiver::class.java))
    val sent = Intent(SENTSMS_ACTION)
        .putExtra("messageID", messageId)
        .putExtra("uri", messageUri.toString())
        .forPart()
    val delivered = Intent(DELIVEREDSMS_ACTION)
        .setData(messageUri)
        .forPart()
    // Mutable is the default before Android 12 (S), which has no flag for it.
    val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    return SmsStatusIntents(
        sent = PendingIntent.getBroadcast(context, messageId, sent, PendingIntent.FLAG_IMMUTABLE),
        delivered = PendingIntent.getBroadcast(context, messageId, delivered, mutable),
    )
}

/** The write that moves an SMS (TYPE) or MMS (MESSAGE_BOX) row to the failed box. */
internal fun failedBoxValues(isMms: Boolean): ContentValues = ContentValues().apply {
    if (isMms) {
        put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_FAILED)
    } else {
        put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
    }
}

/** A synchronous initiation error must settle any row already persisted. */
internal fun markPersistedSendFailed(context: Context, uri: Uri) {
    val values = when (uri.authority) {
        Telephony.Mms.CONTENT_URI.authority -> failedBoxValues(isMms = true)
        Telephony.Sms.CONTENT_URI.authority -> failedBoxValues(isMms = false)
        else -> throw IllegalArgumentException("Unexpected message URI: $uri")
    }
    check(context.contentResolver.update(uri, values, null, null) == 1) {
        "Failed to mark persisted message as failed: $uri"
    }
}

/** SMS types of a row still on its way out. */
internal val SMS_OUTBOX_TYPES = listOf(Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED)

/** MMS boxes of a row still on its way out. */
internal val MMS_OUTBOX_BOXES = listOf(Telephony.Mms.MESSAGE_BOX_OUTBOX)

/**
 * `ERROR_CODE` stamped on an SMS row failed because no SENT result came (the
 * result timeout or the stale-outbox sweep), not because the carrier reported
 * a failure. A late carrier success may still move such a row to sent.
 */
internal const val NO_SENT_RESULT_ERROR_CODE = -2

/**
 * Rows a successful SMS SENT result moves to the sent box: those still on
 * their way out, and those failed only for lack of a result
 * ([NO_SENT_RESULT_ERROR_CODE]), since the message did go out after all. A
 * row a carrier failure marked failed, or an inbound row that reused a
 * deleted message's id, is never overwritten (UNFY-299). A tracked multipart
 * SMS reaches here once, for the whole message ([SmsPartLedger]); a part
 * sent before parts were tracked reaches here on its own.
 */
internal val SENT_RESULT_SELECTION =
    "${Telephony.Sms.TYPE} IN (${SMS_OUTBOX_TYPES.joinToString { "?" }}) OR " +
        "(${Telephony.Sms.TYPE} = ? AND ${Telephony.Sms.ERROR_CODE} = ?)"
internal val SENT_RESULT_ARGS = (
    SMS_OUTBOX_TYPES + listOf(Telephony.Sms.MESSAGE_TYPE_FAILED, NO_SENT_RESULT_ERROR_CODE)
).map { it.toString() }.toTypedArray()

/** The failed-box write for a row whose SENT result never came. */
internal fun noResultFailedValues(isMms: Boolean): ContentValues = failedBoxValues(isMms).apply {
    if (!isMms) put(Telephony.Sms.ERROR_CODE, NO_SENT_RESULT_ERROR_CODE)
}

/** `column IN (?, ...)` with its arguments. */
private fun inSelection(column: String, values: List<Int>): Pair<String, Array<String>> =
    "$column IN (${values.joinToString { "?" }})" to values.map { it.toString() }.toTypedArray()

/**
 * Records a SENT result on its row (UNFY-178). SMS uses the TYPE column, MMS
 * uses MESSAGE_BOX:
 * - a failure moves the row to the failed box, so the consumer derives
 *   delivered=false;
 * - a success moves it from the outbox to the sent box. An SMS is inserted
 *   in the outbox and only moves once the carrier accepted it (UNFY-299);
 *   see [SENT_RESULT_SELECTION].
 * STATUS is a delivery status, which a send result never writes (UNFY-293):
 * an SMS's is left for the delivery report, and an MMS's `st` holds
 * X-Mms-Status values that only a delivery indication supplies.
 * NB: provider-write semantics — verify on a real device (esp. Samsung MMS
 * msg_box) before relying on it.
 */
internal fun recordSendResult(
    context: Context,
    messageId: Int,
    messageUri: Uri,
    eventType: String,
    statusString: String,
    resultCode: Int,
) {
    val sendFailed = resultCode != Activity.RESULT_OK
    val resolver = context.contentResolver
    when {
        sendFailed -> resolver.update(
            messageUri,
            failedBoxValues(isMms = eventType == SENTMMS_ACTION).apply {
                // A carrier failure is final: its own (positive) error code
                // replaces any no-result marker, so a later success (an
                // untracked part) can't move the row to sent.
                if (eventType != SENTMMS_ACTION) put(Telephony.Sms.ERROR_CODE, resultCode)
            },
            null,
            null,
        )
        eventType == SENTMMS_ACTION -> resolver.update(
            messageUri,
            ContentValues().apply {
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_SENT)
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.DATE_SENT, System.currentTimeMillis() / 1000L)
            },
            null,
            null,
        )
        else -> resolver.update(
            messageUri,
            ContentValues().apply {
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.ERROR_CODE, 0)
            },
            SENT_RESULT_SELECTION,
            SENT_RESULT_ARGS,
        )
    }
    if (sendFailed) {
        Log.w(
            TAG,
            "Send FAILED for messageId=$messageId ($eventType, " +
                "resultCode=$resultCode, $statusString) — marked failed.",
        )
    }
}

/**
 * Marks the row at [uri] failed for lack of a SENT result
 * ([noResultFailedValues]), only while it is still on its way out, so a row a
 * result already settled is left alone. Returns the number of rows updated.
 */
internal fun markOutboxRowFailed(context: Context, uri: Uri): Int {
    val isMms = uri.authority == Telephony.Mms.CONTENT_URI.authority
    val (selection, args) =
        if (isMms) inSelection(Telephony.Mms.MESSAGE_BOX, MMS_OUTBOX_BOXES)
        else inSelection(Telephony.Sms.TYPE, SMS_OUTBOX_TYPES)
    return context.contentResolver.update(uri, noResultFailedValues(isMms), selection, args)
}

/** How long a send may wait for its SENT result before it settles as [ERR_SEND_RESULT_TIMEOUT]. */
internal const val SEND_RESULT_TIMEOUT_MS = 10 * 60 * 1000L

/**
 * How long an outbox message may wait for its SENT result before it counts as
 * lost. A result normally arrives within seconds; a reboot or a radio-process
 * crash mid-send loses it for good.
 */
internal const val STALE_OUTBOX_AGE_MS = 10 * 60 * 1000L

/**
 * Marks this app's SMS (outbox or queued) and MMS outbox rows failed once their SENT result is
 * [STALE_OUTBOX_AGE_MS] overdue, so a send whose result was lost never shows
 * as sending forever (UNFY-299); [OutboxSweeper] decides when. Only rows this
 * app created are touched (an MMS whose provider doesn't stamp the creator is
 * left alone), and never one a live handler still awaits ([owned], see
 * [SendResultRouter.pendingSends]). Returns the number of rows marked failed.
 */
internal fun markStaleOutboxFailed(
    context: Context,
    owned: Collection<Uri>,
    now: Long = System.currentTimeMillis(),
): Int {
    val cutoffMs = now - STALE_OUTBOX_AGE_MS
    // SMS dates are in milliseconds, MMS dates in seconds.
    return staleOutboxUpdate(
        context,
        Telephony.Sms.CONTENT_URI,
        noResultFailedValues(isMms = false),
        Telephony.Sms.TYPE,
        SMS_OUTBOX_TYPES,
        Telephony.Sms.CREATOR,
        cutoff = cutoffMs,
        owned = owned,
    ) + staleOutboxUpdate(
        context,
        Telephony.Mms.CONTENT_URI,
        noResultFailedValues(isMms = true),
        Telephony.Mms.MESSAGE_BOX,
        MMS_OUTBOX_BOXES,
        Telephony.Mms.CREATOR,
        cutoff = cutoffMs / 1000L,
        owned = owned,
    )
}

/** One provider's half of [markStaleOutboxFailed]. */
private fun staleOutboxUpdate(
    context: Context,
    table: Uri,
    failed: ContentValues,
    boxColumn: String,
    outboxBoxes: List<Int>,
    creatorColumn: String,
    cutoff: Long,
    owned: Collection<Uri>,
): Int {
    val ownedIds = owned
        .filter { it.authority == table.authority }
        .mapNotNull { it.lastPathSegment?.toLongOrNull()?.toString() }
    val (boxSelection, boxArgs) = inSelection(boxColumn, outboxBoxes)
    val selection = buildString {
        append("$boxSelection AND $creatorColumn = ? AND date < ?")
        if (ownedIds.isNotEmpty()) append(" AND _id NOT IN (${ownedIds.joinToString { "?" }})")
    }
    val args = boxArgs + arrayOf(context.packageName, cutoff.toString()) + ownedIds
    return context.contentResolver.update(table, failed, selection, args)
}

class OutboundMessagingHandler() : Service(), MethodChannel.MethodCallHandler {
    private lateinit var context: Context // Use application context for receivers
    @VisibleForTesting
    internal val channelResultMap = ConcurrentHashMap<Uri, MessageRequestDetails>()

    /** The rows of the sends this handler still awaits a result for. */
    internal val pendingSends: Set<Uri> get() = channelResultMap.keys.toSet()

    /** Fires [onResultTimeout] for sends whose SENT result never comes. */
    private val resultTimeouts = Handler(Looper.getMainLooper())

    /**
     * Registers [details] as the send pending on [uri] and starts its result
     * timeout: with no SENT result within [SEND_RESULT_TIMEOUT_MS] of device
     * uptime (a radio crash mid-send, while this handler lives on), the send
     * settles as [ERR_SEND_RESULT_TIMEOUT] (UNFY-299).
     */
    @VisibleForTesting
    internal fun awaitResult(uri: Uri, details: MessageRequestDetails) {
        channelResultMap[uri] = details
        resultTimeouts.postDelayed({ onResultTimeout(uri, details) }, details, SEND_RESULT_TIMEOUT_MS)
    }

    /** Stops awaiting [details] on [uri]; true when it was still pending. */
    private fun forgetSend(uri: Uri, details: MessageRequestDetails): Boolean {
        resultTimeouts.removeCallbacksAndMessages(details)
        return channelResultMap.remove(uri, details)
    }

    /**
     * Settles a send whose SENT result never came: marks its row failed while
     * it is still on its way out, and replies once with
     * [ERR_SEND_RESULT_TIMEOUT]. A result that arrives later is unowned and
     * recorded by [SendResultRouter]: a failure keeps the row failed, a
     * success moves it to sent (see [SENT_RESULT_SELECTION]).
     */
    private fun onResultTimeout(uri: Uri, details: MessageRequestDetails) {
        if (!forgetSend(uri, details)) return
        try {
            markOutboxRowFailed(context, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed marking timed-out send $uri failed", e)
        }
        val (code, message, errorDetails) = mapSendException(SendResultTimeoutException(uri, SEND_RESULT_TIMEOUT_MS))
        Log.w(TAG, message)
        details.flutterResult.error(code, message, errorDetails)
    }

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
    )

    // Constructor used by Flutter plugin registration
    constructor(context: Context) : this() {
        this.context = context.applicationContext
        SendResultRouter.register(this)
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
                        forgetSend(uri, requestDetails)
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
        SendResultRouter.register(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        SendResultRouter.unregister(this)
        // Clean up any pending results to avoid memory leaks if service is destroyed unexpectedly
        resultTimeouts.removeCallbacksAndMessages(null)
        channelResultMap.clear()
    }

    /**
     * Explicit teardown for handlers constructed via the secondary
     * [constructor(Context)] rather than spun up as a Service. Android
     * never fires [onDestroy] on those, so its [SendResultRouter]
     * registration would otherwise leak forever.
     *
     * Called from `SimpleSmsPlugin.releaseHandler` when the messenger
     * this handler was registered on goes away (plugin detach,
     * background engine teardown).
     */
    fun release() {
        SendResultRouter.unregister(this)
        resultTimeouts.removeCallbacksAndMessages(null)
        channelResultMap.clear()
    }

    /**
     * Takes the settled SENT outcome of a send this handler owns; returns
     * false, doing nothing, when it owns none ([SendResultRouter] then records
     * it). Records it on the row ([recordSendResult]) and sends the Flutter
     * reply exactly once. A multipart SMS arrives here once every part has
     * reported ([SmsPartLedger]), so `send()` reflects the whole message.
     *
     * Failures here happen after the carrier has handled the send, so they
     * settle the pending send as [ERR_SEND_ACK_FAILED] instead of escaping the
     * receiver, which would crash the process and leave the Dart future
     * unsettled (UNFY-282). The reply itself is sent once, outside any catch.
     */
    internal fun onSendResult(
            messageId: Int,
            messageUri: Uri,
            eventType: String,
            statusString: String,
            resultCode: Int,
    ): Boolean {
        val pending = channelResultMap[messageUri] ?: return false
        val outcome = CarrierOutcome(eventType, resultCode, statusString, sent = resultCode == Activity.RESULT_OK)
        // Recorded while still owned, so a concurrent stale-outbox sweep, which
        // skips owned sends, can't fail the row between the two.
        val persistFailure: Exception? =
                try {
                    recordSendResult(context, messageId, messageUri, eventType, statusString, resultCode)
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Failed writing $eventType status for $messageUri", e)
                    e
                }
        forgetSend(messageUri, pending)
        val ack =
                persistFailure?.let { Ack.Failed(SendAckFailedException(messageUri, outcome, it)) }
                        ?: try {
                            ackFromRow(outcome, messageId, messageUri)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed acknowledging $eventType for $messageUri", e)
                            Ack.Failed(SendAckFailedException(messageUri, outcome, e))
                        }
        when (ack) {
            is Ack.Recorded -> pending.flutterResult.success(ack.payload)
            is Ack.Failed -> {
                val (code, message, details) = mapSendException(ack.error)
                pending.flutterResult.error(code, message, details)
            }
        }
        return true
    }

    /** The single reply a settled send receives. */
    private sealed interface Ack {
        /** The encoded message row. */
        class Recorded(val payload: String) : Ack

        class Failed(val error: SendAckException) : Ack
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
            val newUri = insertSms(context, requestDetails)
            val msgId = ContentUris.parseId(newUri).toInt()
            requestDetails.messageUri = newUri

            val rawDest = requestDetails.addresses.first()
            // formatNumber may return null for non-E.164-parseable
            // inputs (shortcodes, alphanumeric). Fall back to the raw
            // input — SmsManager.sendTextMessage accepts shortcodes
            // and other carrier-specific destinations directly.
            val destAddress = formatNumber(context, rawDest) ?: rawDest

            // Each part gets its own SENT and DELIVERED intent; the ledger
            // settles the row, and this send, once every part has reported
            // (UNFY-180). Its record is written before the radio sees a
            // part, or the send does not start.
            //
            // Doing this in our own send path (instead of routing to
            // Klinker's Transaction.sendNewMessage for long bodies)
            // ensures the sent-result PendingIntent broadcasts
            // SENTSMS_ACTION to [OutboundMessagingReceiver]. Klinker's
            // sentPI uses `<package>.SMS_SENT` (a different action),
            // so going through Klinker for long bodies left the
            // message stuck at "Sending…" with no status callback.
            val parts = smsManager.divideMessage(requestDetails.body)
            val partCount = maxOf(parts.size, 1)
            val ledger = SmsPartLedger.forApp(context)
            val partRefs = ledger.start(msgId.toLong(), partCount)
            try {
                val statusIntents = partRefs.map { smsStatusIntents(context, msgId, newUri, it) }
                awaitResult(newUri, requestDetails)
                if (parts.size <= 1) {
                    smsManager.sendTextMessage(
                            destAddress,
                            null, // originating address
                            requestDetails.body,
                            statusIntents.single().sent,
                            statusIntents.single().delivered,
                            msgId.toLong()
                    )
                } else {
                    smsManager.sendMultipartTextMessage(
                            destAddress,
                            null, // originating address
                            parts,
                            ArrayList(statusIntents.map { it.sent }),
                            ArrayList(statusIntents.map { it.delivered }),
                            msgId.toLong()
                    )
                }
            } catch (e: Exception) {
                // No part reached the radio; onMethodCall fails the row.
                ledger.forget(partRefs.first())
                throw e
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

            // Targets the manifest receiver, like the SMS intents (UNFY-299).
            val outboundIntent = Intent(SENTMMS_ACTION)
                    .setComponent(ComponentName(context, OutboundMessagingReceiver::class.java))
            val sendTransaction = Transaction(context, sendSettings)
            sendTransaction.setExplicitBroadcastForSentMms(outboundIntent)
            sendTransaction.setMmsPersistedCallback { uri ->
                requestDetails.messageUri = uri
                awaitResult(uri, requestDetails)
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
