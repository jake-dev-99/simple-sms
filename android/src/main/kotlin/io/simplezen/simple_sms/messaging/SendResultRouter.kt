package io.simplezen.simple_sms.messaging

import android.content.Context
import android.net.Uri
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Routes SENT results from the manifest-declared [OutboundMessagingReceiver]
 * to the live [OutboundMessagingHandler] that owns the send (UNFY-299).
 *
 * SENT intents target that receiver explicitly, so Android delivers them even
 * after the app process has gone. The owning handler settles its pending
 * send; a result nobody owns (the process restarted, or a multipart part
 * after the send settled) is still recorded on the row, part by part: with
 * no owner to count the parts, a multipart SMS moves to sent at its first
 * successful part, and a later failed part still marks it failed. A result
 * lost outright (a reboot mid-send) is caught by [markStaleOutboxFailed].
 */
internal object SendResultRouter {
    private const val TAG = "SendResultRouter"

    private val handlers = CopyOnWriteArraySet<OutboundMessagingHandler>()

    fun register(handler: OutboundMessagingHandler) {
        handlers.add(handler)
    }

    fun unregister(handler: OutboundMessagingHandler) {
        handlers.remove(handler)
    }

    /** The rows every live handler still awaits a SENT result for. */
    fun pendingSends(): Set<Uri> = handlers.flatMapTo(mutableSetOf()) { it.pendingSends }

    fun dispatch(
        context: Context,
        messageId: Int,
        messageUri: Uri,
        eventType: String,
        statusString: String,
        resultCode: Int,
    ) {
        if (handlers.any { it.onSendResult(messageId, messageUri, eventType, statusString, resultCode) }) return
        try {
            recordSendResult(context, messageId, messageUri, eventType, statusString, resultCode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed recording unowned $eventType result for $messageUri", e)
        }
    }
}
