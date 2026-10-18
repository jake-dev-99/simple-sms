package io.simplezen.simple_sms.messaging

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import io.flutter.plugin.common.MethodChannel
import java.util.Locale

/**
 * The respond-via-message service the default-SMS-app role requires. A phone
 * app starts it with [TelephonyManager.ACTION_RESPOND_VIA_MESSAGE] when the
 * user declines a call with a quick-reply text. Like the inbound receivers,
 * it does the durable work natively, before any Flutter engine runs: it sends
 * the text through [OutboundSender], which stores the row first, then stops.
 * Dart hears of a quick reply only if it fails
 * ([announceQuickReplyFailure], UNFY-319).
 */
class OutboundMessagingHandler : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val parsed = parseRespondViaMessage(intent)) {
            is RespondViaMessageParse.Rejected ->
                Log.e(TAG, "Ignoring respond-via-message intent: ${parsed.reason}")
            is RespondViaMessageParse.Accepted -> send(applicationContext, parsed.request)
        }
        // The send needs this service no longer: its result reaches
        // OutboundMessagingReceiver even after the process has gone.
        stopSelf(startId)
        // A quick reply is time-bound; replaying it after a process kill
        // would send a stale text the user never re-confirmed.
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "OutboundMessagingHandler"

        /** Starts sending a quick reply; tests replace it. */
        @VisibleForTesting
        internal var send: (Context, RespondViaMessageRequest) -> Unit = ::startQuickReply

        /** Delivers to Dart; tests replace it. */
        @VisibleForTesting
        internal var deliver: (Context, String, MutableMap<String, Any?>) -> Unit =
            BackgroundEngineManager::sendToFlutter

        /**
         * Sends [request] through a sender of its own. Nobody awaits a quick
         * reply's result, so the sender is released once the send has
         * started; a send that could not start is announced to Dart at once.
         */
        private fun startQuickReply(context: Context, request: RespondViaMessageRequest) {
            val sender = OutboundSender(context)
            try {
                sender.sendQuickReply(request, object : MethodChannel.Result {
                    override fun success(result: Any?) = Unit

                    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                        Log.e(TAG, "Quick reply did not start: $errorCode $errorMessage")
                        announceQuickReplyFailure(context, request)
                    }

                    override fun notImplemented() =
                        error("notImplemented", "sendQuickReply has no reply for notImplemented", null)
                })
            } finally {
                sender.release()
            }
        }
    }
}

/** Tells Dart that [request] did not send (`receiveRespondViaMessageFailure`). */
internal fun announceQuickReplyFailure(context: Context, request: RespondViaMessageRequest) {
    OutboundMessagingHandler.deliver(
        context,
        "receiveRespondViaMessageFailure",
        mutableMapOf(
            "recipients" to request.recipients,
            "body" to request.body,
            "subscriptionId" to request.subscriptionId,
        ),
    )
}

private const val EXTRA_QUICK_REPLY_RECIPIENTS = "quickReplyRecipients"
private const val EXTRA_QUICK_REPLY_BODY = "quickReplyBody"
private const val EXTRA_QUICK_REPLY_SUBSCRIPTION = "quickReplySubscription"

/**
 * Marks a status intent as a quick reply's, carrying [request] so the result
 * receiver can announce a failure without reading the row back.
 */
internal fun Intent.putQuickReply(request: RespondViaMessageRequest): Intent =
    putStringArrayListExtra(EXTRA_QUICK_REPLY_RECIPIENTS, ArrayList(request.recipients))
        .putExtra(EXTRA_QUICK_REPLY_BODY, request.body)
        .apply { request.subscriptionId?.let { putExtra(EXTRA_QUICK_REPLY_SUBSCRIPTION, it) } }

/** The quick reply a status intent reports on ([putQuickReply]), or null for any other send. */
internal fun Intent.quickReply(): RespondViaMessageRequest? {
    val recipients = getStringArrayListExtra(EXTRA_QUICK_REPLY_RECIPIENTS) ?: return null
    val body = getStringExtra(EXTRA_QUICK_REPLY_BODY) ?: return null
    val subscriptionId =
        if (hasExtra(EXTRA_QUICK_REPLY_SUBSCRIPTION)) getIntExtra(EXTRA_QUICK_REPLY_SUBSCRIPTION, 0) else null
    return RespondViaMessageRequest(recipients, body, subscriptionId)
}

/** What a respond-via-message intent asks for, already validated. */
internal data class RespondViaMessageRequest(
    val recipients: List<String>,
    val body: String,
    val subscriptionId: Int?,
)

/** A respond-via-message intent that is [Accepted] as a request, or [Rejected] with the failed check. */
internal sealed interface RespondViaMessageParse {
    data class Accepted(val request: RespondViaMessageRequest) : RespondViaMessageParse
    data class Rejected(val reason: String) : RespondViaMessageParse
}

private val RESPOND_VIA_MESSAGE_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")

// `PhoneConstants.SUBSCRIPTION_KEY`, the key AOSP Telecom and AOSP Messaging
// put the SIM under. Hidden API, so it can't be referenced.
private const val EXTRA_SUBSCRIPTION = "subscription"

private val RECIPIENT_SEPARATORS = charArrayOf(',', ';')

/** Validates [intent] as a quick reply; [RespondViaMessageParse.Rejected] names the failed check. */
internal fun parseRespondViaMessage(intent: Intent?): RespondViaMessageParse {
    if (intent == null || intent.action != TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
        return RespondViaMessageParse.Rejected("unexpected action")
    }
    val uri = intent.data
    if (uri == null || uri.scheme?.lowercase(Locale.ROOT) !in RESPOND_VIA_MESSAGE_SCHEMES) {
        return RespondViaMessageParse.Rejected("unsupported data scheme")
    }
    // The scheme-specific part is already decoded; cutting at '?' drops a
    // `?body=` query that rides along with the recipients.
    val recipients = uri.schemeSpecificPart.orEmpty()
        .substringBefore('?')
        .split(*RECIPIENT_SEPARATORS)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (recipients.isEmpty()) return RespondViaMessageParse.Rejected("no recipient")
    val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
    if (body.isNullOrEmpty()) return RespondViaMessageParse.Rejected("no text")
    return RespondViaMessageParse.Accepted(
        RespondViaMessageRequest(recipients, body, subscriptionOf(intent)),
    )
}

/** The SIM the caller asked for, or null when it named none or an invalid one. */
private fun subscriptionOf(intent: Intent): Int? {
    val key = if (intent.hasExtra(EXTRA_SUBSCRIPTION)) {
        EXTRA_SUBSCRIPTION
    } else {
        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX
    }
    return intent.getIntExtra(key, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        .takeIf(SubscriptionManager::isValidSubscriptionId)
}
