package io.simplezen.simple_sms.messaging

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor.DartEntrypoint
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.simplezen.simple_sms.SimpleSmsPlugin

enum class MessageType {
    SMS,
    MMS,
    CALL,
}

class InboundMessaging(val context : Context) {
    private val channelName = "io.simplezen.simple_sms/inbound_messaging"

    fun transferInboundMessage(messageType : MessageType, message: MutableMap<String, Any?>) {
        sendToFlutter(
            method = when (messageType) {
                MessageType.SMS -> "receiveInboundSmsMessage"
                MessageType.MMS -> "receiveInboundMmsMessage"
                MessageType.CALL -> "receiveCallEvent"
            },
            payload = message.apply { this["messageType"] = messageType.name.lowercase() },
        )
    }

    fun transferCallEvent(event: MutableMap<String, Any?>) {
        sendToFlutter(method = "receiveCallEvent", payload = event)
    }

    private fun sendToFlutter(method: String, payload: MutableMap<String, Any?>) {
        // Perform the send operation
        Log.d("receiveInboundMessage", " <<< setting up flutter engine")
        val binaryMessenger : BinaryMessenger =  if(SimpleSmsPlugin.flutterBinding != null) {
            Log.d("receiveInboundMessage", " <<< flutterBinding is not null")
            SimpleSmsPlugin.flutterBinding!!.binaryMessenger
        } else {
            Log.d("receiveInboundMessage", " <<< flutterBinding is null")
            val flutterEngine = FlutterEngine(context)

            val bundlePath = "flutter_assets"
            Log.d("receiveInboundMessage", " <<< bundlePath: $bundlePath")

            val appBundlePath = FlutterInjector.instance().flutterLoader().findAppBundlePath()

            Log.d("receiveInboundMessage", " <<< appBundlePath: $appBundlePath")
            val dartEntrypoint = DartEntrypoint(
                appBundlePath,
                "initializeApp" // Function name in your Dart code
            )
            Log.d("receiveInboundMessage", " <<< dartEntrypoint: $dartEntrypoint")

            flutterEngine.dartExecutor.executeDartEntrypoint(dartEntrypoint)
            Log.d("receiveInboundMessage", " <<< flutterEngine: $flutterEngine")
            Log.d("receiveInboundMessage", " <<< Finished setting up Flutter Engine")
            flutterEngine.dartExecutor.binaryMessenger
        }
        // Prime the Method Channels required for processing the inbound message
        SimpleSmsPlugin().initializeMethodChannels(context, binaryMessenger)
        var channel = MethodChannel(binaryMessenger, channelName)

        Log.d("receiveInboundMessage", " <<< Sending Message to Dart: $payload")

        try {
            val jsonString = AnySerializer.encodeToString(payload)

            Handler(Looper.getMainLooper()).post {
                channel.invokeMethod(method, jsonString, object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        Log.d("receiveInboundMessage", " <<< Message received successfully")
                    }

                    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                        Log.e("receiveInboundMessage", " <<< Failed to receive message: $errorMessage")
                    }

                    override fun notImplemented() {
                        Log.e("receiveInboundMessage", " <<< Method $method not implemented")
                    }
                })
                // Call the desired channel message here.
            }
        } catch (e: Exception) {
            Log.e("receiveInboundMessage", " <<< Error transferring inbound message: ${e.message}")
            e.printStackTrace()
        }
    }
}
