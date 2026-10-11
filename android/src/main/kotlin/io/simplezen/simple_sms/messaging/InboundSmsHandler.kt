
package io.simplezen.simple_sms.messaging

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.provider.Telephony.Threads.getOrCreateThreadId
import android.telephony.SubscriptionManager
import android.util.Log
import io.simplezen.simple_sms.queries.Query
import io.simplezen.simple_sms.queries.QueryObj

/**
 * The subscription id of the SIM an SMS_DELIVER [intent] arrived on, or null
 * when it carries no valid (non-negative) one. Android sets
 * [SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX] on API 30+, and the legacy
 * `subscription` extra — which the MMS path also reads — throughout; the
 * legacy one is used when the new one is missing or invalid.
 */
internal fun inboundSubscriptionId(intent: Intent): Int? {
    val index =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1)
        } else {
            -1
        }
    if (index >= 0) return index
    return intent.getIntExtra("subscription", -1).takeIf { it >= 0 }
}

// Inbound SMS Messages
class InboundSmsHandler : BroadcastReceiver() {
    @OptIn(ExperimentalStdlibApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_DELIVER") {
            Log.w("InboundSmsHandler", "Received unexpected action: ${intent.action}")
            return
        }

        Log.d("InboundSmsHandler", "Received SMS broadcast")

        try {
            // Retrieve SMS messages from the Intent
            val intentMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (intentMessages.isNullOrEmpty()) {
                Log.w("InboundSmsHandler", " <<< No messages in intent")
                return
            }

            // Multi-part SMS: concatenate all message parts into a single body
            val firstMessage = intentMessages[0]
            val fullBody = intentMessages.joinToString("") { it.displayMessageBody ?: "" }

            Log.d("InboundSmsHandler", " <<< Received ${intentMessages.size} part(s)")

            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, firstMessage.displayOriginatingAddress)
                put(Telephony.Sms.BODY, fullBody)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.DATE_SENT, firstMessage.timestampMillis)
                put(Telephony.Sms.SERVICE_CENTER, firstMessage.serviceCenterAddress)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.STATUS, firstMessage.status)
                put(Telephony.Sms.SUBJECT, firstMessage.pseudoSubject)
                put(
                    Telephony.Sms.THREAD_ID,
                    getOrCreateThreadId(context, firstMessage.originatingAddress)
                )
                put(Telephony.Sms.REPLY_PATH_PRESENT, firstMessage.isReplyPathPresent)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                // The SIM it arrived on, so a reply can go out on the same
                // line (UNFY-306) and the attribution chip names it.
                val subscriptionId = inboundSubscriptionId(intent)
                if (subscriptionId != null) {
                    put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
                } else {
                    Log.w("InboundSmsHandler", "SMS_DELIVER without a subscription id; row keeps the provider default")
                }
            }
            val newUri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            if (newUri == null) {
                Log.e("InboundSmsHandler", "Failed to insert SMS into database")
                return
            }

            val queryResult = Query(context).query(
                QueryObj(contentUri = newUri.toString())
            )
            if (queryResult.isEmpty()) {
                Log.e("InboundSmsHandler", "Inserted SMS but could not re-query: $newUri")
                return
            }

            val newMessage = queryResult.first().toSortedMap(naturalOrder()).toMutableMap()
            InboundMessaging(context).transferInboundMessage(MessageType.SMS, newMessage)

        } catch (e: Exception) {
            Log.e("InboundSmsHandler", " <<<<< Error: $e")
            Log.e("InboundSmsHandler", " <<<<< Error: ${e.stackTraceToString()}")
        }
    }
}
