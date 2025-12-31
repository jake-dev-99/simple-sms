
package io.simplezen.simple_sms.messaging

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.provider.Telephony
import android.provider.Telephony.Threads.getOrCreateThreadId
import android.util.Log
import io.simplezen.simple_sms.queries.Query
import io.simplezen.simple_sms.queries.QueryObj

// Inbound SMS Messages
class InboundSmsHandler : BroadcastReceiver() {
    @OptIn(ExperimentalStdlibApi::class)
    override fun onReceive(context: Context, intent: Intent) {

        ContactsContract.Contacts.CONTENT_FILTER_URI

        // Check if the action string matches the expected value
        if (intent.action != "android.provider.Telephony.SMS_DELIVER") {
            Log.w("IncomingMmsHandler", " <<< Received unexpected action: ${intent.action}")
            return
        }

        // Log the incoming Intent
        Log.d("IncomingSmsHandler", " <<< Received SMS - $intent")
        Log.d("IncomingSmsHandler", " <<< Received SMS - ${intent.dataString}")
        Log.d(
            "IncomingSmsHandler",
            " <<< Received SMS - ${intent.extras?.keySet()?.toList().toString()}"
        )
        Log.d("IncomingSmsHandler", " <<< ")

        try {
            // Retrieve SMS messages from the Intent
            val intentMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val message = intentMessages[0]

            Log.d("IncomingSmsHandler", " <<< Msg: $message")

            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, message.displayOriginatingAddress) // sender
                put(Telephony.Sms.BODY, message.displayMessageBody,)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.DATE_SENT, message.timestampMillis)
                put(Telephony.Sms.SERVICE_CENTER, message.timestampMillis)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.STATUS, message.status)
                put(Telephony.Sms.SUBJECT, message.pseudoSubject)
                put(
                    Telephony.Sms.THREAD_ID,
                    getOrCreateThreadId(context, message.originatingAddress)
                )
                put(Telephony.Sms.REPLY_PATH_PRESENT, message.isReplyPathPresent)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            val uri = Telephony.Sms.Inbox.CONTENT_URI // Don't use raw string URI anymore
            val newUri = context.contentResolver.insert(uri, values)
            Log.d("IncomingSmsHandler", " <<< Inserted SMS - $values")

            val newMessage = Query(context).query(
                QueryObj(
                    contentUri = newUri.toString(),
                    projection = emptyList(),
                    selection = "",
                    selectionArgs = emptyList(),
                    sortOrder = "",
                )
            ).first() .toSortedMap(naturalOrder()).toMutableMap()
            Log.d("InboundSmsHandler", " <<< Inserted SMS - $newMessage")
            Log.d("InboundSmsHandler", " <<< Sending to inbound handler")
            InboundMessaging(context).transferInboundMessage(
                MessageType.SMS,
                newMessage
            )

        } catch (e: Exception) {
            Log.e("IncomingSmsHandler", " <<<<< Error: $e")
            Log.e("IncomingSmsHandler", " <<<<< Error: ${e.stackTraceToString()}")
        }
    }
}
