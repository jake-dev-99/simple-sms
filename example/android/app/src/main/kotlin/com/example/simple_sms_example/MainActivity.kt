package com.example.simple_sms_example

import android.content.Context
import io.flutter.Log
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MessageCodec
import io.flutter.plugin.common.StandardMessageCodec
import kotlin.jvm.java
import android.app.role.RoleManager
import android.content.Context.ROLE_SERVICE
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.JSONMethodCodec
import io.flutter.plugin.common.EventChannel
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

class MainActivity : FlutterActivity()

/** SimpleSmsPlugin */
@RequiresApi(Build.VERSION_CODES.Q)
class SimpleSmsPlugin:  FlutterFragmentActivity(), FlutterPlugin, ActivityAware {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity

    private lateinit var context: Context
    private lateinit var roleManager: RoleManager

    private lateinit var messageChannel: MethodChannel
    private lateinit var queryChannel: MethodChannel
    private lateinit var permissionsChannel: MethodChannel
    private lateinit var actionsChannel: MethodChannel
    private lateinit var destructiveActionsChannel: MethodChannel

    lateinit var instance: SimpleSmsPlugin

    companion object {
        internal lateinit var flutterEngine: FlutterEngine

        var binding: FlutterPlugin.FlutterPluginBinding? = null
        var flutterBinding: FlutterPlugin.FlutterPluginBinding? = null
        var activityBinding: ActivityPluginBinding? = null

        lateinit var roleManager: RoleManager
        // Store reference to the FlutterEngine for use from InboundMessaging

        private lateinit var requestRoleLauncher: ActivityResultLauncher<Intent>
        private lateinit var permissionsLauncher: ActivityResultLauncher<Array<String>>
        val permissionResults = mutableMapOf<String, Boolean>()


        fun getFlutterEngine(): FlutterEngine? {
            return flutterEngine
        }

        fun requestRole(role: String): Boolean {
            // Implementation will be added later
            return false
        }

        fun requestPermissions(permissions: Array<String>): Map<String, Boolean> {
            // Implementation will be added later
            return emptyMap()
        }

        fun checkPermissions(context: Context, permissions: Array<String>): Map<String, Boolean> {
            val permissionResults = mutableMapOf<String, Boolean>()
            for (permission in permissions) {
                val permissionGranted = context.checkSelfPermission(permission)
                permissionResults[permission] = permissionGranted == 0
            }
            return permissionResults
        }

        fun checkRole(context: Context, role: String): Boolean {
            val roleManager = context.getSystemService(ROLE_SERVICE) as RoleManager
            return roleManager.isRoleAvailable(role) && roleManager.isRoleHeld(role)
        }
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        SimpleSmsPlugin.flutterEngine = flutterPluginBinding.flutterEngine
        flutterBinding = flutterPluginBinding
        instance = this

        context = flutterPluginBinding.applicationContext

        messageChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "io.simplezen.simple_sms/messaging")
        queryChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "io.simplezen.simple_sms/query")
        permissionsChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "io.simplezen.simple_sms/permissions")
        actionsChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "io.simplezen.simple_sms/actions")
        destructiveActionsChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "io.simplezen.simple_sms/destructive_actions")

        roleManager = context.getSystemService(ROLE_SERVICE) as RoleManager
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        queryChannel.setMethodCallHandler(null)
        permissionsChannel.setMethodCallHandler(null)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        // Save activity binding reference
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityBinding = binding
    }

    override fun onDetachedFromActivity() {
    }
}


class InboundMessaging(val context: Context) {
    companion object {
    }

    private val channelName = "io.simplezen.simple_sms/inbound_messaging"
    private lateinit var channel: MethodChannel

    init {

        val binding = SimpleSmsPlugin.binding
        val flutterBinding = SimpleSmsPlugin.flutterBinding
        channel = MethodChannel(flutterBinding!!.binaryMessenger, channelName)
    }

    fun receiveInboundMessage(message: Map<String, Any?>) {
        // Perform the send operation
        Log.d("receiveInboundMessage", "Message received: $message")

        try {
            channel.invokeMethod("receiveInboundMessage", message, object : MethodChannel.Result {
                override fun success(result: Any?) {
                    Log.d("receiveInboundMessage", "Message received successfully")
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    Log.e("receiveInboundMessage", "Failed to receive message: $errorMessage")
                }

                override fun notImplemented() {
                    Log.e("receiveInboundMessage", "Method not implemented")
                }
            })
        } catch (e: Exception) {
            Log.e("receiveInboundMessage", "Error sending message: ${e.message}")
            e.printStackTrace()
        }
    }
}
