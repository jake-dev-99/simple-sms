package io.simplezen.simple_sms

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Context.ROLE_SERVICE
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher // Keep if you plan to use it
import androidx.core.net.toUri
import io.flutter.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.simplezen.simple_sms.device.DestructiveActions
import io.simplezen.simple_sms.device.DeviceActions
import io.simplezen.simple_sms.device.PermissionsHandler
import io.simplezen.simple_sms.messaging.OutboundMessagingHandler
import io.simplezen.simple_sms.queries.Query

/** SimpleSmsPlugin */
class SimpleSmsPlugin : FlutterPlugin, ActivityAware {

  private lateinit var applicationContext: Context // Renamed for clarity, from FlutterPluginBinding
  private var activity: Activity? = null // Keep track of the current activity

  // Instance roleManager
  private lateinit var roleManager: RoleManager

  // Method Channels
  private lateinit var messageChannel: MethodChannel
  private lateinit var queryChannel: MethodChannel
  private lateinit var permissionsChannel: MethodChannel
  private lateinit var actionsChannel: MethodChannel
  private lateinit var destructiveActionsChannel: MethodChannel
  // private lateinit var inboundMessagingChannel: MethodChannel // Uncomment if using InboundMessagingRegistry

  companion object {
    private const val TAG = "SimpleSmsPlugin"

    // Static reference to FlutterPluginBinding, make it nullable
    var flutterBinding: FlutterPlugin.FlutterPluginBinding? = null
      private set

    // Static reference to ActivityPluginBinding, make it nullable
    // This is useful if external components (like BroadcastReceivers) need Activity context
    // and are registered through the plugin. Manage its lifecycle carefully.
    var activityBinding: ActivityPluginBinding? = null
      private set

    // ActivityResultLaunchers - These need to be initialized in onAttachedToActivity
    // and require the ActivityPluginBinding.
    // Consider making them instance variables if they are tied to this plugin instance's lifecycle with an activity.
    // If they truly need to be static, they need careful handling.
    private var requestRoleLauncher: ActivityResultLauncher<Intent>? = null
    private var permissionsLauncher: ActivityResultLauncher<Array<String>>? = null
    val permissionResults = mutableMapOf<String, Boolean>()


    // Consider moving role/permission checking methods to a helper class or making them instance methods
    // if they depend on an activity or specific plugin state.
    // For now, they require a Context passed to them.

    fun requestRole(role: String): Boolean {
      // TODO: Implement actual role request logic using requestRoleLauncher
      // Example:
      // val intent = (activity.getSystemService(ROLE_SERVICE) as RoleManager).createRequestRoleIntent(role)
      // requestRoleLauncher?.launch(intent)
      Log.w(TAG, "requestRole for '$role' not fully implemented.")
      return false
    }

    fun requestPermissions(permissions: Array<String>): Map<String, Boolean> {
      // TODO: Implement actual permission request logic using permissionsLauncher
      // Example:
      // permissionsLauncher?.launch(permissions)
      Log.w(TAG, "requestPermissions not fully implemented.")
      return emptyMap() // Or return current status and update asynchronously
    }

    fun checkPermissions(context: Context, permissions: Array<String>): Map<String, Boolean> {
      val results = mutableMapOf<String, Boolean>()
      for (permission in permissions) {
        results[permission] = context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
      }
      return results
    }

    fun checkRole(context: Context, role: String): Boolean {
      val roleManager = context.getSystemService(ROLE_SERVICE) as RoleManager
      return roleManager.isRoleAvailable(role) && roleManager.isRoleHeld(role)
    }
  }

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    flutterBinding = binding // Assign to static variable
    applicationContext = binding.applicationContext

    // Initialize instance roleManager
    roleManager = applicationContext.getSystemService(ROLE_SERVICE) as RoleManager

  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    Log.d(TAG, "onDetachedFromEngine")
    messageChannel.setMethodCallHandler(null)
    queryChannel.setMethodCallHandler(null)
    permissionsChannel.setMethodCallHandler(null)
    actionsChannel.setMethodCallHandler(null)
    destructiveActionsChannel.setMethodCallHandler(null)
    flutterBinding = null
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    Log.d(TAG, "onAttachedToActivity")
    activity = binding.activity
    activityBinding = binding
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
  }

  override fun onDetachedFromActivity() {
    Log.d(TAG, "onDetachedFromActivity")
    activity = null
    activityBinding = null // Clear static activity binding
  }


  fun initializeMethodChannels(context: Context, binaryMessenger : BinaryMessenger) {
    // You can also pass the activity to your handlers if they need it,
    // or use applicationContext if that's sufficient.
    applicationContext = context // Ensure applicationContext is set

    messageChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/messaging")
    messageChannel.setMethodCallHandler(OutboundMessagingHandler(applicationContext)) // Pass activity if needed

    queryChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/query")
    queryChannel.setMethodCallHandler(Query(applicationContext)) // Pass activity if needed

    permissionsChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/permissions")
    permissionsChannel.setMethodCallHandler(PermissionsHandler(applicationContext)) // Pass binding for launchers

    actionsChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/actions")
    actionsChannel.setMethodCallHandler(DeviceActions(applicationContext)) // Pass activity if needed

    destructiveActionsChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/destructive_actions")
    destructiveActionsChannel.setMethodCallHandler(DestructiveActions(applicationContext)) // Pass activity if needed
  }
}

