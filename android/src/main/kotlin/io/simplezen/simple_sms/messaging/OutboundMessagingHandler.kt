package io.simplezen.simple_sms.messaging

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.Locale

/**
 * The respond-via-message service the default-SMS-app role requires. A phone
 * app starts it with [TelephonyManager.ACTION_RESPOND_VIA_MESSAGE] when the
 * user declines a call with a quick-reply text. Like the inbound SMS and MMS
 * receivers, it hands each valid request to Dart
 * (`AndroidMessaging.respondViaMessageCallback`) and is done; Dart sends it
 * (UNFY-319). Outbound sends otherwise go through [OutboundSender] on the
 * messaging channel.
 */
class OutboundMessagingHandler : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val parsed = parseRespondViaMessage(intent)) {
            is RespondViaMessageParse.Rejected ->
                Log.e(TAG, "Ignoring respond-via-message intent: ${parsed.reason}")
            is RespondViaMessageParse.Accepted -> deliver(
                this,
                METHOD,
                mutableMapOf(
                    "recipients" to parsed.request.recipients,
                    "body" to parsed.request.body,
                    "subscriptionId" to parsed.request.subscriptionId,
                ),
            )
        }
        stopSelf(startId)
        // A quick reply is time-bound; replaying it after a process kill
        // would send a stale text the user never re-confirmed.
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "OutboundMessagingHandler"
        private const val METHOD = "receiveRespondViaMessage"

        /** Delivers to Dart; tests replace it. */
        @VisibleForTesting
        internal var deliver: (Context, String, MutableMap<String, Any?>) -> Unit =
            BackgroundEngineManager::sendToFlutter
    }
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
