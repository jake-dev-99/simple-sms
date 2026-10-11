package io.simplezen.simple_sms

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.FlutterException
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.StandardMethodCodec
import io.simplezen.simple_sms.messaging.ERR_NO_TELEPHONY
import io.simplezen.simple_sms.messaging.OutboundMessagingReceiver
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * (UNFY-306). The destructive channel stays off that path and runs on a
 * background task queue, each channel has a single registration owner, and
 * no engine registers a send-status receiver: the manifest-declared one
 * takes every result (UNFY-299).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineChannelRegistrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Records the handler registered on each channel. */
    private class RecordingMessenger : BinaryMessenger {
        val handlers = mutableMapOf<String, BinaryMessenger.BinaryMessageHandler>()
        val taskQueues = mutableMapOf<String, BinaryMessenger.TaskQueue?>()

        override fun send(channel: String, message: ByteBuffer?) = Unit

        override fun send(
            channel: String,
            message: ByteBuffer?,
            callback: BinaryMessenger.BinaryReply?,
        ) = Unit

        override fun setMessageHandler(
            channel: String,
            handler: BinaryMessenger.BinaryMessageHandler?,
        ) = setMessageHandler(channel, handler, null)

        /** The destructive channel runs on a background task queue (UNFY-244). */
        override fun makeBackgroundTaskQueue(
            options: BinaryMessenger.TaskQueueOptions,
        ): BinaryMessenger.TaskQueue = object : BinaryMessenger.TaskQueue {}

        override fun setMessageHandler(
            channel: String,
            handler: BinaryMessenger.BinaryMessageHandler?,
            taskQueue: BinaryMessenger.TaskQueue?,
        ) {
            if (handler == null) {
                handlers.remove(channel)
                taskQueues.remove(channel)
            } else {
                handlers[channel] = handler
                taskQueues[channel] = taskQueue
            }
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

    /** Registered send-status receivers (Robolectric lists the manifest one too). */
    private fun statusReceiverCount(): Int =
        shadowOf(context as Application).registeredReceivers.count {
            it.broadcastReceiver is OutboundMessagingReceiver
        }

    /** A plugin attached to [messenger], as an engine attach leaves it. */
    private fun attached(messenger: RecordingMessenger): SimpleSmsPlugin =
        SimpleSmsPlugin().also { it.attachEngineChannels(context, messenger) }

    @After
    fun tearDown() {
        detachAll.forEach { it() }
        detachAll.clear()
    }

    private val detachAll = mutableListOf<() -> Unit>()

    private fun attachedForTest(messenger: RecordingMessenger): SimpleSmsPlugin =
        attached(messenger).also { plugin ->
            detachAll += { plugin.detachEngineChannels(messenger) }
        }

    @Test
    fun engineAttachRegistersTheActionsAndMessagingChannels() {
        val messenger = RecordingMessenger()

        attachedForTest(messenger)

        assertEquals(
            setOf(SimpleSmsPlugin.ACTIONS_CHANNEL, SimpleSmsPlugin.MESSAGING_CHANNEL),
            messenger.handlers.keys,
        )
    }

    @Test
    fun markReadReachesDeviceActionsWithoutAnActivity() {
        val messenger = RecordingMessenger()
        attachedForTest(messenger)

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
    fun sendReachesTheSenderWithoutAnActivity() {
        val messenger = RecordingMessenger()
        attachedForTest(messenger)

        // Robolectric reports no telephony feature, so the sender answers
        // with its own NO_TELEPHONY error — proof the call reached it
        // instead of failing with MissingPluginException.
        val code = messenger.invokeExpectingError(
            SimpleSmsPlugin.MESSAGING_CHANNEL,
            MethodCall("sendMessage", null),
        )

        assertEquals(ERR_NO_TELEPHONY, code)
    }

    @Test
    fun engineDetachRemovesTheEngineChannels() {
        val messenger = RecordingMessenger()
        val plugin = attached(messenger)

        plugin.detachEngineChannels(messenger)

        assertEquals(emptySet<String>(), messenger.handlers.keys)
    }

    @Test
    fun engineSetupRegistersNoStatusReceiver() {
        val manifestOnly = statusReceiverCount()
        val messenger = RecordingMessenger()

        attachedForTest(messenger)
        SimpleSmsPlugin.initializeMethodChannelsStatic(context, messenger)

        assertEquals(manifestOnly, statusReceiverCount())
    }

    @Test
    fun activityAndBackgroundChannelSetupRegistersOnlyTheDestructiveChannel() {
        val messenger = RecordingMessenger()

        SimpleSmsPlugin.initializeMethodChannelsStatic(context, messenger)

        assertEquals(setOf(SimpleSmsPlugin.DESTRUCTIVE_ACTIONS_CHANNEL), messenger.handlers.keys)
    }

    /** Deletes and their read-backs are blocking provider I/O (UNFY-244). */
    @Test
    fun destructiveChannelRunsOnABackgroundTaskQueue() {
        val messenger = RecordingMessenger()
        attachedForTest(messenger)

        SimpleSmsPlugin.initializeMethodChannelsStatic(context, messenger)

        assertNotNull(messenger.taskQueues[SimpleSmsPlugin.DESTRUCTIVE_ACTIONS_CHANNEL])
        assertNull(messenger.taskQueues[SimpleSmsPlugin.MESSAGING_CHANNEL])
    }
}
