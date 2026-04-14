package io.simplezen.simple_sms

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import io.simplezen.simple_sms.device.DestructiveActions
import io.simplezen.simple_sms.device.DeviceActions
import io.simplezen.simple_sms.messaging.BackgroundEngineManager
import io.simplezen.simple_sms.messaging.OutboundMessagingHandler
import io.simplezen.simple_sms.queries.Query

/** SimpleSmsPlugin */
class SimpleSmsPlugin : FlutterPlugin, ActivityAware, PluginRegistry.ActivityResultListener,
    PluginRegistry.RequestPermissionsResultListener {

  private lateinit var applicationContext: Context
  private var activity: Activity? = null

  // Method Channels
  private lateinit var messageChannel: MethodChannel
  private lateinit var queryChannel: MethodChannel
  private lateinit var actionsChannel: MethodChannel
  private lateinit var destructiveActionsChannel: MethodChannel

  companion object {
    private const val TAG = "SimpleSmsPlugin"

    var flutterBinding: FlutterPlugin.FlutterPluginBinding? = null
      private set

    var activityBinding: ActivityPluginBinding? = null
      private set

    /**
     * Initialize method channels on a given BinaryMessenger. Used by both the
     * foreground plugin lifecycle and BackgroundEngineManager for background delivery.
     */
    fun initializeMethodChannelsStatic(context: Context, binaryMessenger: BinaryMessenger) {
      val appContext = context.applicationContext

      MethodChannel(binaryMessenger, "io.simplezen.simple_sms/messaging")
        .setMethodCallHandler(OutboundMessagingHandler(appContext))

      MethodChannel(binaryMessenger, "io.simplezen.simple_sms/query")
        .setMethodCallHandler(Query(appContext))

      MethodChannel(binaryMessenger, "io.simplezen.simple_sms/actions")
        .setMethodCallHandler(DeviceActions(appContext))

      MethodChannel(binaryMessenger, "io.simplezen.simple_sms/destructive_actions")
        .setMethodCallHandler(DestructiveActions(appContext))
    }

  }

  // --- FlutterPlugin lifecycle ---

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    flutterBinding = binding
    applicationContext = binding.applicationContext
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    Log.d(TAG, "onDetachedFromEngine")
    messageChannel.setMethodCallHandler(null)
    queryChannel.setMethodCallHandler(null)
    actionsChannel.setMethodCallHandler(null)
    destructiveActionsChannel.setMethodCallHandler(null)
    flutterBinding = null
    BackgroundEngineManager.onForegroundDetached()
  }

  // --- ActivityAware lifecycle ---

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    Log.d(TAG, "onAttachedToActivity")
    activity = binding.activity
    activityBinding = binding
    binding.addActivityResultListener(this)
    binding.addRequestPermissionsResultListener(this)
    initializeMethodChannels(applicationContext, flutterBinding!!.binaryMessenger)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    Log.d(TAG, "onDetachedFromActivityForConfigChanges")
    activity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    Log.d(TAG, "onReattachedToActivityForConfigChanges")
    activity = binding.activity
    activityBinding = binding
    binding.addActivityResultListener(this)
    binding.addRequestPermissionsResultListener(this)
  }

  override fun onDetachedFromActivity() {
    Log.d(TAG, "onDetachedFromActivity")
    activity = null
    activityBinding = null
  }

  // --- Activity result callbacks ---
  //
  // SimpleSmsPlugin no longer handles permission or role requests directly
  // (that belongs to simple_permissions_native). These listeners remain
  // registered on the ActivityPluginBinding for future use but currently
  // decline every request so other plugins on the same binding can handle
  // their own results.

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    return false
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
  ): Boolean {
    return false
  }

  // --- Method channel setup ---

  fun initializeMethodChannels(context: Context, binaryMessenger: BinaryMessenger) {
    applicationContext = context

    messageChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/messaging")
    queryChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/query")
    actionsChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/actions")
    destructiveActionsChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/destructive_actions")

    initializeMethodChannelsStatic(applicationContext, binaryMessenger)
  }
}
