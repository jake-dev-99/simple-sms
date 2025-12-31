package io.simplezen.simple_sms.device

import android.content.Context
import android.util.Log
import io.flutter.plugin.common.JSONMethodCodec
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall

// KamikazeePigeon
class DestructiveActions(val context: Context) : MethodChannel.MethodCallHandler {

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "sendMessage" -> {
                result.notImplemented()
            }
            else ->  result.notImplemented()
        }
    }

    fun deleteMessage(lookupId: String): Boolean {
        throw Exception("Not implemented")
    }

    fun deleteContact(lookupId: String): Boolean {
        throw Exception("Not implemented")
    }
}