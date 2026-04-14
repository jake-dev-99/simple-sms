package io.simplezen.simple_sms.messaging

import android.content.Context
import android.util.Log

enum class MessageType {
    SMS,
    MMS,
}

class InboundMessaging(val context: Context) {

    fun transferInboundMessage(messageType: MessageType, message: MutableMap<String, Any?>) {
        val method = when (messageType) {
            MessageType.SMS -> "receiveInboundSmsMessage"
            MessageType.MMS -> "receiveInboundMmsMessage"
        }
        message["messageType"] = messageType.name.lowercase()

        Log.d("InboundMessaging", "Transferring ${messageType.name} message to Dart")
        BackgroundEngineManager.sendToFlutter(context, method, message)
    }
}
