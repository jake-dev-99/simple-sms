package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import androidx.annotation.VisibleForTesting
import io.flutter.plugin.common.MethodChannel
import io.simplezen.simple_sms.queries.Query
import io.simplezen.simple_sms.queries.QueryObj
import java.util.concurrent.ConcurrentHashMap

/**
 * Records outbound SENT results and settles the sends waiting on them
 * (UNFY-160, UNFY-282, UNFY-283, UNFY-306).
 *
 * SENT broadcasts target the manifest-declared [OutboundMessagingReceiver],
 * so Android delivers them even when the process is cached or has been
 * killed since the send started — as when an inline notification reply sends
 * from a headless engine and nothing keeps the process alive. That receiver
 * records every carrier result on the message row through [onSentBroadcast]
 * (a send that fails to start is settled by `markPersistedSendFailed`
 * instead), and settles the send's Flutter reply when the send is still
 * tracked in this process. A send whose process died, or whose engine has
 * gone, is no longer tracked; its row is still recorded.
 */
internal object OutboundSendResults {
    /** A send awaiting its SENT result, and the engine's handler that started it. */
    private class Tracked(
            val owner: OutboundMessagingHandler,
            val request: OutboundMessagingHandler.MessageRequestDetails,
    )

    /** Sends awaiting their SENT result, by message row, across every engine in this process. */
    private val tracked = ConcurrentHashMap<Uri, Tracked>()

    /** Waits for [messageUri]'s SENT result to settle [request], started by [owner]. */
    fun track(
            owner: OutboundMessagingHandler,
            messageUri: Uri,
            request: OutboundMessagingHandler.MessageRequestDetails,
    ) {
        tracked[messageUri] = Tracked(owner, request)
    }

    /** Stops waiting for [messageUri]: its send failed to start and has been answered. */
    fun untrack(messageUri: Uri) {
        tracked.remove(messageUri)
    }

    /**
     * Stops waiting for every send [owner] started: its engine has detached,
     * so no Dart caller awaits their replies. Their results are still recorded
     * on their rows when they arrive.
     */
    fun forget(owner: OutboundMessagingHandler) {
        tracked.values.removeIf { it.owner === owner }
    }

    /** The message rows whose sends are tracked. */
    @VisibleForTesting
    fun trackedUris(): Set<Uri> = tracked.keys.toSet()

    @VisibleForTesting
    fun clear() {
        tracked.clear()
    }

    /**
     * Handles one SENT result: records a failure (or an MMS's move to the sent
     * box) on the message row, then — once the SENT parts of a send still
     * tracked in this process settle its outcome — sends that send's Flutter
     * reply exactly once.
     *
     * Failures here happen after the carrier has handled the send, so they
     * settle the tracked send as [ERR_SEND_ACK_FAILED] instead of escaping the
     * receiver, which would crash the process and leave the Dart future
     * unsettled (UNFY-282). The reply itself is sent once, outside any catch.
     */
    fun onSentBroadcast(
            context: Context,
            messageId: Int,
            messageUri: Uri,
            eventType: String,
            statusString: String,
            resultCode: Int,
    ) {
        val sendFailed = resultCode != Activity.RESULT_OK
        // A result for a send no longer tracked here is only recorded on the row.
        val settled =
                claimSettledSend(
                        messageUri,
                        CarrierOutcome(eventType, resultCode, statusString, sent = !sendFailed),
                )
        val persistFailure: Exception? =
                try {
                    persistStatus(context, messageId, messageUri, eventType, statusString, resultCode, sendFailed)
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Failed writing $eventType status for $messageUri", e)
                    e
                }
        if (settled == null) return
        val ack =
                persistFailure?.let { Ack.Failed(SendAckFailedException(messageUri, settled.outcome, it)) }
                        ?: try {
                            ackFromRow(context, settled.outcome, messageId, messageUri)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed acknowledging $eventType for $messageUri", e)
                            Ack.Failed(SendAckFailedException(messageUri, settled.outcome, e))
                        }
        when (ack) {
            is Ack.Recorded -> settled.result.success(ack.payload)
            is Ack.Failed -> {
                val (code, message, details) = mapSendException(ack.error)
                settled.result.error(code, message, details)
            }
        }
    }

    /** A pending send claimed for settlement, with its settled carrier outcome. */
    private class SettledSend(val result: MethodChannel.Result, val outcome: CarrierOutcome)

    /** The single reply a settled send receives. */
    private sealed interface Ack {
        /** The encoded message row. */
        class Recorded(val payload: String) : Ack

        class Failed(val error: SendAckException) : Ack
    }

    /**
     * Records [part] against the send tracked on [messageUri], if this process
     * still tracks one, and claims it once its outcome is settled. Returns null
     * while parts are outstanding or when nothing is tracked here.
     */
    private fun claimSettledSend(messageUri: Uri, part: CarrierOutcome): SettledSend? {
        val entry = tracked[messageUri] ?: return null
        val outcome = entry.request.sentParts.record(part) ?: return null
        tracked.remove(messageUri, entry)
        return SettledSend(entry.request.flutterResult, outcome)
    }

    /**
     * Honor the send result (UNFY-178). A failed *send* (resultCode !=
     * RESULT_OK on a SENT broadcast) must move the row to the failed box so
     * the consumer derives delivered=false — previously this wrote
     * STATUS_COMPLETE unconditionally, so a failed send (e.g.
     * MMS_ERROR_IO_ERROR) showed as sent. SMS uses the TYPE column, MMS uses
     * MESSAGE_BOX — different columns. STATUS is a delivery status, which a
     * send result never writes (UNFY-293): the SMS row is inserted as
     * MESSAGE_TYPE_SENT when the send starts, so success needs no write, and
     * its STATUS is left for the delivery report; an MMS's `st` holds X-Mms-Status values that only a
     * delivery indication supplies. A failed multipart part
     * marks the row failed; later successful parts write nothing over it.
     * NB: provider-write semantics — verify on a real device (esp. Samsung MMS
     * msg_box) before relying on it.
     */
    private fun persistStatus(
            context: Context,
            messageId: Int,
            messageUri: Uri,
            eventType: String,
            statusString: String,
            resultCode: Int,
            sendFailed: Boolean,
    ) {
        val statusUpdate =
                when {
                    sendFailed && eventType == SENTMMS_ACTION ->
                            ContentValues().apply {
                                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_FAILED)
                            }
                    sendFailed ->
                            ContentValues().apply {
                                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
                            }
                    eventType == SENTMMS_ACTION ->
                            ContentValues().apply {
                                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_SENT)
                                put(Telephony.Mms.READ, 1)
                                put(Telephony.Mms.SEEN, 1)
                                put(Telephony.Mms.DATE_SENT, System.currentTimeMillis() / 1000L)
                            }
                    else -> return
                }
        context.contentResolver.update(messageUri, statusUpdate, null, null)
        if (sendFailed) {
            Log.w(
                    TAG,
                    "Send FAILED for messageId=$messageId ($eventType, " +
                            "resultCode=$resultCode, $statusString) — marked failed.",
            )
        }
    }

    /**
     * Builds the ack for a settled send from the just-updated message row, or
     * [ERR_MESSAGE_ROW_MISSING] when the row is gone (UNFY-160).
     */
    private fun ackFromRow(context: Context, outcome: CarrierOutcome, messageId: Int, messageUri: Uri): Ack {
        // Read the just-updated row back through simple_query (Rule 1).
        // Message rows (content://sms/N, content://mms/N) are INTEGER/TEXT
        // only — no BLOB columns — so ContentQuery's BLOB-coalescing is
        // moot here (verified vs the AOSP sms/pdu schema + codec; UNFY-156).
        val row =
                Query(context).query(QueryObj(contentUri = messageUri.toString())).firstOrNull()
        if (row == null) {
            val missing = MessageRowMissingException(messageUri, outcome)
            Log.w(TAG, "${missing.message} (resultCode=${outcome.resultCode}, ${outcome.statusString})")
            return Ack.Failed(missing)
        }
        val finalMessage: MutableMap<String, Any?> = row.toMutableMap()
        finalMessage["uri"] = messageUri.toString()
        if (outcome.eventType == SENTMMS_ACTION) {
            finalMessage["parts"] =
                    Query(context)
                            .query(QueryObj(Telephony.Mms.Part.getPartUriForMessage(messageId.toString()).toString()))
            finalMessage["recipients"] =
                    Query(context)
                            .query(QueryObj(Telephony.Mms.Addr.getAddrUriForMessage(messageId.toString()).toString()))
        }
        return Ack.Recorded(AnySerializer.encodeToString(finalMessage))
    }
}
