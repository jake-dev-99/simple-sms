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

/** SimpleSmsPlugin */
class SimpleSmsPlugin : FlutterPlugin, ActivityAware, PluginRegistry.ActivityResultListener,
    PluginRegistry.RequestPermissionsResultListener {

  private lateinit var applicationContext: Context
  private var activity: Activity? = null

  // Method Channels
  private lateinit var messageChannel: MethodChannel
  private lateinit var actionsChannel: MethodChannel
  private lateinit var destructiveActionsChannel: MethodChannel

  companion object {
    private const val TAG = "SimpleSmsPlugin"

    var flutterBinding: FlutterPlugin.FlutterPluginBinding? = null
      private set

    var activityBinding: ActivityPluginBinding? = null
      private set

    /**
     * Per-messenger cache of the OutboundMessagingHandler instance so
     * repeat calls to [initializeMethodChannelsStatic] don't construct a
     * fresh handler each time.
     *
     * The handler's secondary constructor calls `setupSmsReceiver()`,
     * which registers an Android `BroadcastReceiver`. Before this cache
     * existed, every inbound SMS delivery (which ran through
     * `BackgroundEngineManager.ensureMethodChannels` → here) spawned a
     * new handler + a new receiver, all of them leaking because the
     * Service lifecycle that normally calls `unregisterSmsReceiver`
     * never fires on constructor-created instances.
     *
     * Identity-hash keyed: BinaryMessengers are compared by reference,
     * not content. The foreground engine's messenger and each
     * background engine's messenger are distinct instances, which is
     * the correct grouping.
     */
    private val handlersByMessenger =
      java.util.IdentityHashMap<BinaryMessenger, OutboundMessagingHandler>()

    /**
     * Initialize method channels on a given BinaryMessenger. Used by both the
     * foreground plugin lifecycle and BackgroundEngineManager for background delivery.
     *
     * Idempotent per-messenger: the `OutboundMessagingHandler` instance
     * is cached so repeat invocations for the same messenger don't
     * register additional `OutboundMessagingReceiver`s.
     */
    fun initializeMethodChannelsStatic(context: Context, binaryMessenger: BinaryMessenger) {
      val appContext = context.applicationContext

      val outboundHandler = synchronized(handlersByMessenger) {
        handlersByMessenger[binaryMessenger] ?: OutboundMessagingHandler(appContext).also {
          handlersByMessenger[binaryMessenger] = it
        }
      }

      MethodChannel(binaryMessenger, "io.simplezen.simple_sms/messaging")
        .setMethodCallHandler(outboundHandler)

      MethodChannel(binaryMessenger, "io.simplezen.simple_sms/actions")
        .setMethodCallHandler(DeviceActions(appContext))

      MethodChannel(binaryMessenger, "io.simplezen.simple_sms/destructive_actions")
        .setMethodCallHandler(DestructiveActions(appContext))
    }

    /**
     * Release the cached [OutboundMessagingHandler] for [binaryMessenger]
     * (if one is cached), calling its `release()` so the underlying
     * BroadcastReceiver is unregistered. Called from
     * [onDetachedFromEngine] and from
     * [BackgroundEngineManager.onForegroundDetached] when engines tear
     * down.
     */
    internal fun releaseHandler(binaryMessenger: BinaryMessenger) {
      val handler = synchronized(handlersByMessenger) {
        handlersByMessenger.remove(binaryMessenger)
      }
      handler?.release()
    }

  }

  // --- FlutterPlugin lifecycle ---

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    flutterBinding = binding
    applicationContext = binding.applicationContext
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    Log.d(TAG, "onDetachedFromEngine")
    // Release the cached OutboundMessagingHandler for this messenger so
    // its BroadcastReceiver is unregistered. Without this, every engine
    // teardown would leave a live receiver in the Android registry.
    releaseHandler(binding.binaryMessenger)
    // The channel `lateinit var`s are only populated in onAttachedToActivity
    // (via initializeMethodChannels). A background FlutterEngine — e.g. the
    // one Workmanager spins up for a headless sync task — attaches and
    // detaches from the engine without ever seeing an Activity, so these
    // channels may legitimately be unset here. Guard each access instead of
    // crashing with UninitializedPropertyAccessException on engine teardown.
    if (::messageChannel.isInitialized) messageChannel.setMethodCallHandler(null)
    if (::actionsChannel.isInitialized) actionsChannel.setMethodCallHandler(null)
    if (::destructiveActionsChannel.isInitialized) destructiveActionsChannel.setMethodCallHandler(null)
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
    actionsChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/actions")
    destructiveActionsChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/destructive_actions")

    initializeMethodChannelsStatic(applicationContext, binaryMessenger)
  }
}
