package io.simplezen.simple_sms

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.FlutterException
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.StandardMethodCodec
import io.simplezen.simple_sms.messaging.ERR_NO_TELEPHONY
import io.simplezen.simple_sms.messaging.OutboundMessagingReceiver
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins the channels every engine gets at attach — including a headless one
 * with no Activity, such as flutter_local_notifications' notification-action
 * dispatcher — so notification actions work without opening the app: "Mark
 * Read" writes native read-state (UNFY-241) and an inline "Reply" sends
 * (UNFY-306). The destructive channel stays off that path, each channel has a
 * single registration owner, and no engine registers a send-status receiver:
 * the manifest-declared one records every result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineChannelRegistrationTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** Records the handler registered on each channel. */
    private class RecordingMessenger : BinaryMessenger {
        val handlers = mutableMapOf<String, BinaryMessenger.BinaryMessageHandler>()

        override fun send(channel: String, message: ByteBuffer?) = Unit

        override fun send(
            channel: String,
            message: ByteBuffer?,
            callback: BinaryMessenger.BinaryReply?,
        ) = Unit

        override fun setMessageHandler(
            channel: String,
            handler: BinaryMessenger.BinaryMessageHandler?,
        ) {
            if (handler == null) handlers.remove(channel) else handlers[channel] = handler
        }

        /** Delivers [call] to [channel]'s handler and returns the reply's error code. */
        fun invokeExpectingError(channel: String, call: MethodCall): String {
            val handler = handlers[channel]
            assertNotNull("no handler on $channel", handler)
            // The codec leaves the encoded buffer positioned at its end; a
            // real messenger hands the handler a buffer read from the start.
            val request = StandardMethodCodec.INSTANCE.encodeMethodCall(call)
            request.rewind()
            var reply: ByteBuffer? = null
            handler!!.onMessage(request) { reply = it }
            val bytes = reply
            assertNotNull("no reply on $channel", bytes)
            bytes!!.rewind()
            val error = assertThrows(FlutterException::class.java) {
                StandardMethodCodec.INSTANCE.decodeEnvelope(bytes)
            }
            return error.code
        }
    }

    /** Registered send-status receivers. */
    private fun statusReceiverCount(): Int =
        shadowOf(context).registeredReceivers.count {
            it.broadcastReceiver is OutboundMessagingReceiver
        }

    @Test
    fun engineAttachRegistersTheActionsAndMessagingChannels() {
        val messenger = RecordingMessenger()

        SimpleSmsPlugin().attachEngineChannels(context, messenger)

        assertEquals(
            setOf(SimpleSmsPlugin.ACTIONS_CHANNEL, SimpleSmsPlugin.MESSAGING_CHANNEL),
            messenger.handlers.keys,
        )
    }

    @Test
    fun markReadReachesDeviceActionsWithoutAnActivity() {
        val messenger = RecordingMessenger()
        SimpleSmsPlugin().attachEngineChannels(context, messenger)

        // No arguments: DeviceActions answers with its own validation error,
        // which proves the call reached the handler instead of failing with
        // MissingPluginException.
        val code = messenger.invokeExpectingError(
            SimpleSmsPlugin.ACTIONS_CHANNEL,
            MethodCall("markMessageAsRead", null),
        )

        assertEquals("INVALID_ARGUMENT", code)
    }

    @Test
    fun sendReachesTheOutboundHandlerWithoutAnActivity() {
        val messenger = RecordingMessenger()
        SimpleSmsPlugin().attachEngineChannels(context, messenger)

        // Robolectric reports no telephony feature, so the handler answers
        // with its own NO_TELEPHONY error — proof the call reached it
        // instead of failing with MissingPluginException.
        val code = messenger.invokeExpectingError(
            SimpleSmsPlugin.MESSAGING_CHANNEL,
            MethodCall("sendMessage", null),
        )

        assertEquals(ERR_NO_TELEPHONY, code)
    }

    @Test
    fun engineSetupRegistersNoStatusReceiver() {
        // Robolectric lists the manifest-declared receiver too. Which receiver
        // a send's status intents name is pinned in OutboundSendAckTest.
        val manifestOnly = statusReceiverCount()
        val messenger = RecordingMessenger()

        SimpleSmsPlugin().attachEngineChannels(context, messenger)
        SimpleSmsPlugin.initializeMethodChannelsStatic(context, messenger)

        assertEquals(manifestOnly, statusReceiverCount())
    }

    @Test
    fun activityAndBackgroundChannelSetupRegistersOnlyTheDestructiveChannel() {
        val messenger = RecordingMessenger()

        SimpleSmsPlugin.initializeMethodChannelsStatic(context, messenger)

        assertEquals(
            setOf(SimpleSmsPlugin.DESTRUCTIVE_ACTIONS_CHANNEL),
            messenger.handlers.keys,
        )
    }
}
