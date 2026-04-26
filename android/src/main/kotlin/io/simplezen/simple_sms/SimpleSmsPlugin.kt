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

  // The FlutterPluginBinding this specific plugin instance was attached
  // to. Each engine attach creates a new SimpleSmsPlugin instance via
  // Flutter's auto-registration, so this field is per-instance and
  // identifies "which engine am I attached to". Used in
  // [onAttachedToActivity] to mark this instance's engine as the
  // foreground (Activity-bearing) one in the static binding registry.
  private var ownBinding: FlutterPlugin.FlutterPluginBinding? = null

  // Method Channels
  private lateinit var messageChannel: MethodChannel
  private lateinit var actionsChannel: MethodChannel
  private lateinit var destructiveActionsChannel: MethodChannel

  companion object {
    private const val TAG = "SimpleSmsPlugin"

    /**
     * The "best" binding for plugin-initiated calls into Dart (e.g.
     * inbound SMS delivery via [BackgroundEngineManager]).
     *
     * Prefers the Activity-attached binding (the foreground main app
     * engine) over any other attached binding. When the foreground is
     * detached but another engine is attached (e.g. WorkManager's
     * headless dispatcher engine), returns null so the caller treats
     * it as "no foreground" and spawns a dedicated background engine
     * via [BackgroundEngineManager.startBackgroundEngine] — which
     * runs `initializeApp` and DOES register the inbound handler.
     *
     * Why this matters: Flutter auto-attaches every plugin to every
     * engine that boots in the process. WorkManager spawning a
     * `v1SyncCallbackDispatcher` engine causes our `onAttachedToEngine`
     * to fire for that engine, and we used to blindly assign
     * `flutterBinding = binding`, overwriting the foreground reference.
     * If a real-time SMS then arrived, BackgroundEngineManager would
     * use the WM engine's messenger — but the WM dispatcher entry
     * point never calls `AndroidMessaging.initialize`, so the
     * MethodChannel handler isn't registered there, and the plugin
     * logs "Method receiveInboundSmsMessage not implemented" and
     * drops the message.
     *
     * The fix is to expose ONLY the foreground (Activity-attached)
     * binding here, falling through to the "no foreground" path when
     * only headless engines are attached.
     */
    val flutterBinding: FlutterPlugin.FlutterPluginBinding?
      get() = foregroundBinding

    /**
     * The Activity-attached engine's binding, if any. Set in
     * [onAttachedToActivity] to the per-instance [ownBinding], cleared
     * in [onDetachedFromActivity]. Headless engines (WorkManager,
     * background sync) never call onAttachedToActivity, so this stays
     * pointed at the real foreground engine even when other engines
     * boot in the process.
     */
    private var foregroundBinding: FlutterPlugin.FlutterPluginBinding? = null

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
    Log.d(TAG, "onAttachedToEngine (engine=${System.identityHashCode(binding)})")
    // Stash the per-instance binding. We do NOT promote this to
    // `foregroundBinding` until `onAttachedToActivity` confirms this
    // engine actually has an Activity. Headless engines (WorkManager
    // dispatcher, BackgroundEngineManager-spawned bg engines) attach
    // here too, and used to clobber the foreground reference.
    ownBinding = binding
    applicationContext = binding.applicationContext
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    Log.d(TAG, "onDetachedFromEngine (engine=${System.identityHashCode(binding)})")
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
    // Only clear `foregroundBinding` if this instance was the one that
    // owned it. Headless-engine detaches must not clobber the
    // foreground reference held by the main app's plugin instance.
    if (foregroundBinding === binding) {
      foregroundBinding = null
      BackgroundEngineManager.onForegroundDetached()
    }
    if (ownBinding === binding) {
      ownBinding = null
    }
  }

  // --- ActivityAware lifecycle ---

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    Log.d(TAG, "onAttachedToActivity (engine=${System.identityHashCode(ownBinding)})")
    activity = binding.activity
    activityBinding = binding
    // Mark THIS plugin instance's engine as the foreground engine.
    // From here on `flutterBinding` (the public getter) returns this
    // binding, regardless of how many headless engines later attach.
    foregroundBinding = ownBinding
    binding.addActivityResultListener(this)
    binding.addRequestPermissionsResultListener(this)
    initializeMethodChannels(applicationContext, ownBinding!!.binaryMessenger)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    Log.d(TAG, "onDetachedFromActivityForConfigChanges")
    activity = null
    // Don't clear foregroundBinding — config changes are transient and
    // onReattachedToActivityForConfigChanges will restore the activity
    // shortly. The engine itself stays running.
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    Log.d(TAG, "onReattachedToActivityForConfigChanges")
    activity = binding.activity
    activityBinding = binding
    foregroundBinding = ownBinding
    binding.addActivityResultListener(this)
    binding.addRequestPermissionsResultListener(this)
  }

  override fun onDetachedFromActivity() {
    Log.d(TAG, "onDetachedFromActivity")
    activity = null
    activityBinding = null
    // The Activity is gone for good (not a config change). The engine
    // may still be alive in the background, but it no longer has a UI.
    // Drop the foreground claim so inbound delivery routes through the
    // dedicated background engine path that owns the receive handler.
    if (foregroundBinding === ownBinding) {
      foregroundBinding = null
    }
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
