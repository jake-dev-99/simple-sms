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

/**
 * Manages a singleton FlutterEngine for background message delivery.
 *
 * When the app is killed and a BroadcastReceiver fires, we need a running
 * Dart isolate to deliver the message to. This class:
 * - Reuses the foreground engine's BinaryMessenger when the app is alive
 * - Lazily creates a single background FlutterEngine when the app is killed
 * - Queues messages that arrive before the engine is ready
 * - Drains the queue once the engine and method channels are initialized
 */
object BackgroundEngineManager {
    private const val TAG = "BackgroundEngineManager"
    private const val CHANNEL_NAME = "io.simplezen.simple_sms/inbound_messaging"

    private var backgroundEngine: FlutterEngine? = null
    private var isEngineReady = false
    private val pendingMessages = mutableListOf<PendingMessage>()
    private val lock = Any()

    data class PendingMessage(
        val method: String,
        val payload: String,
    )

    /**
     * Delivers a message to the Dart side. If the foreground engine is running,
     * uses it directly. Otherwise creates/reuses a background engine and queues
     * the message until the engine is ready.
     */
    fun sendToFlutter(context: Context, method: String, payload: MutableMap<String, Any?>) {
        val jsonString = try {
            AnySerializer.encodeToString(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize message payload: ${e.message}")
            return
        }

        // If the foreground plugin is attached, use it directly.
        // Method channels were already set up in
        // `SimpleSmsPlugin.onAttachedToActivity`; calling
        // `ensureMethodChannels` here on every inbound message was
        // constructing a fresh `OutboundMessagingHandler` (and thus a
        // fresh `OutboundMessagingReceiver`) on every delivery — a
        // receiver leak that compounded for the session lifetime.
        val foregroundMessenger = SimpleSmsPlugin.flutterBinding?.binaryMessenger
        if (foregroundMessenger != null) {
            Log.d(TAG, "Foreground engine available, sending directly")
            invokeMethod(foregroundMessenger, method, jsonString)
            return
        }

        // Background path: use singleton engine
        synchronized(lock) {
            if (backgroundEngine == null) {
                Log.d(TAG, "Creating background FlutterEngine")
                pendingMessages.add(PendingMessage(method, jsonString))
                startBackgroundEngine(context)
            } else if (!isEngineReady) {
                Log.d(TAG, "Engine starting, queuing message")
                pendingMessages.add(PendingMessage(method, jsonString))
            } else {
                Log.d(TAG, "Background engine ready, sending directly")
                invokeMethod(backgroundEngine!!.dartExecutor.binaryMessenger, method, jsonString)
            }
        }
    }

    private fun startBackgroundEngine(context: Context) {
        val appContext = context.applicationContext
        val engine = FlutterEngine(appContext)
        backgroundEngine = engine

        val appBundlePath = FlutterInjector.instance().flutterLoader().findAppBundlePath()
        val dartEntrypoint = DartEntrypoint(appBundlePath, "initializeApp")

        Log.d(TAG, "Executing Dart entrypoint 'initializeApp'")
        engine.dartExecutor.executeDartEntrypoint(dartEntrypoint)

        val messenger = engine.dartExecutor.binaryMessenger
        ensureMethodChannels(appContext, messenger)

        // Post to main looper to give the Dart isolate time to initialize
        // and register its method call handler
        Handler(Looper.getMainLooper()).post {
            synchronized(lock) {
                isEngineReady = true
                Log.d(TAG, "Background engine ready, draining ${pendingMessages.size} queued messages")
                for (pending in pendingMessages) {
                    invokeMethod(messenger, pending.method, pending.payload)
                }
                pendingMessages.clear()
            }
        }
    }

    private fun ensureMethodChannels(context: Context, messenger: BinaryMessenger) {
        // Initialize all method channels on this messenger so the Dart side
        // can also send outbound messages, query, etc. from the background isolate
        SimpleSmsPlugin.initializeMethodChannelsStatic(context, messenger)
    }

    private fun invokeMethod(messenger: BinaryMessenger, method: String, jsonString: String) {
        val channel = MethodChannel(messenger, CHANNEL_NAME)

        Handler(Looper.getMainLooper()).post {
            channel.invokeMethod(method, jsonString, object : MethodChannel.Result {
                override fun success(result: Any?) {
                    Log.d(TAG, "Message delivered successfully: $method")
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    Log.e(TAG, "Failed to deliver message: $method - $errorMessage")
                }

                override fun notImplemented() {
                    Log.e(TAG, "Method $method not implemented on Dart side. " +
                        "Ensure your app defines a top-level @pragma('vm:entry-point') " +
                        "function named 'initializeApp' that calls AndroidMessaging.initialize().")
                }
            })
        }
    }

    /**
     * Called when the foreground engine detaches. Cleans up the background
     * engine if one was created (the foreground will take over again on restart).
     */
    fun onForegroundDetached() {
        synchronized(lock) {
            backgroundEngine?.let { engine ->
                // Release the cached OutboundMessagingHandler for the
                // background messenger before destroying the engine —
                // otherwise the handler's BroadcastReceiver lingers in
                // the Android registry after teardown.
                SimpleSmsPlugin.releaseHandler(engine.dartExecutor.binaryMessenger)
                engine.destroy()
            }
            backgroundEngine = null
            isEngineReady = false
            // Don't clear pending messages — they'll be delivered if a new engine starts
        }
    }
}
