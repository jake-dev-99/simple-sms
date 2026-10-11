package io.simplezen.simple_sms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.FlutterException
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.StandardMethodCodec
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins UNFY-241: the stateless action channels are registered on any engine
 * the plugin attaches to — including a headless one with no Activity, such as
 * flutter_local_notifications' notification-action dispatcher — so "Mark Read"
 * from a notification can write native read-state without opening the app.
 * The stateful messaging channel is not part of that set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionChannelRegistrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

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
            var reply: ByteBuffer? = null
            handler!!.onMessage(StandardMethodCodec.INSTANCE.encodeMethodCall(call)) {
                reply = it
            }
            val bytes = reply
            assertNotNull("no reply on $channel", bytes)
            bytes!!.rewind()
            val error = assertThrows(FlutterException::class.java) {
                StandardMethodCodec.INSTANCE.decodeEnvelope(bytes)
            }
            return error.code
        }
    }

    @Test
    fun registersOnlyTheStatelessActionChannels() {
        val messenger = RecordingMessenger()

        SimpleSmsPlugin.initializeActionChannelsStatic(context, messenger)

        assertEquals(
            setOf(SimpleSmsPlugin.ACTIONS_CHANNEL, SimpleSmsPlugin.DESTRUCTIVE_ACTIONS_CHANNEL),
            messenger.handlers.keys,
        )
    }

    @Test
    fun markReadReachesDeviceActionsOnAHeadlessEngine() {
        val messenger = RecordingMessenger()
        SimpleSmsPlugin.initializeActionChannelsStatic(context, messenger)

        // No arguments: DeviceActions answers with its own validation error,
        // which proves the call reached the handler instead of failing with
        // MissingPluginException.
        val code = messenger.invokeExpectingError(
            SimpleSmsPlugin.ACTIONS_CHANNEL,
            MethodCall("markMessageAsRead", null),
        )

        assertEquals("INVALID_ARGUMENT", code)
    }
}
