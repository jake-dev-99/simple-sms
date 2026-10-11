package io.simplezen.simple_sms.messaging

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Routes SENT results from the manifest-declared [OutboundMessagingReceiver]
 * to the live [OutboundMessagingHandler] that owns the send (UNFY-299).
 *
 * SENT intents target that receiver explicitly, so Android delivers them even
 * after the app process has gone. An SMS's parts are first added up in
 * [SmsPartLedger], which persists them across processes: nothing is settled
 * until every part has reported, and then the message is failed if any part
 * failed (UNFY-180). The owning handler settles its pending send; a settled
 * result nobody owns (the process restarted) is still recorded on the row. A
 * result lost outright (a reboot mid-send) is caught by
 * [markStaleOutboxFailed].
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

    /**
     * Purpose: settles one SENT result: an SMS part through its send's
     * ledger, an MMS (or an SMS part sent before parts were tracked, which
     * carries no [part]) on its own.
     *
     * @param part the SMS part reporting, from its intent.
     * @returns nothing.
     * @throws nothing; failures are logged.
     */
    fun dispatch(
        context: Context,
        messageId: Int,
        messageUri: Uri,
        eventType: String,
        statusString: String,
        resultCode: Int,
        part: SmsPartRef?,
    ) {
        val settledCode = if (eventType == SENTSMS_ACTION && part != null) {
            settledSmsResult(context, messageUri, part, resultCode) ?: return
        } else {
            resultCode
        }
        val settledStatus = if (settledCode == resultCode) statusString else smsSentStatusString(settledCode)
        if (handlers.any { it.onSendResult(messageId, messageUri, eventType, settledStatus, settledCode) }) return
        try {
            recordSendResult(context, messageId, messageUri, eventType, settledStatus, settledCode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed recording unowned $eventType result for $messageUri", e)
        }
    }

    // The SMS's SENT outcome once [part] completes it, else null (logged).
    private fun settledSmsResult(context: Context, messageUri: Uri, part: SmsPartRef, resultCode: Int): Int? {
        val progress = try {
            SmsPartLedger.forApp(context).recordSent(part.token, part.index, part.count, resultCode)
        } catch (e: IOException) {
            Log.e(TAG, "Could not record part ${part.index + 1}/${part.count}'s SENT result for $messageUri", e)
            return null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "SENT result for $messageUri names no part of its send", e)
            return null
        }
        return when (progress) {
            is SentPartProgress.Settled -> progress.resultCode
            SentPartProgress.AwaitingParts -> null.also {
                Log.d(TAG, "$messageUri part ${part.index + 1}/${part.count} reported; awaiting the others")
            }
            SentPartProgress.DuplicatePart -> null.also {
                Log.w(TAG, "$messageUri part ${part.index + 1}/${part.count} reported again; ignored")
            }
            SentPartProgress.UnknownSend -> null.also {
                Log.i(TAG, "SENT result for $messageUri from a send no longer tracked; ignored")
            }
        }
    }
}
