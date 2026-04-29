package io.simplezen.simple_sms.messaging

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.util.Log
import com.google.android.mms.MmsException
import com.google.android.mms.pdu_alt.PduPersister
import com.google.android.mms.pdu_alt.RetrieveConf
import io.simplezen.simple_sms.queries.Query
import io.simplezen.simple_sms.queries.QueryObj

/**
 * Persists an inbound RetrieveConf PDU to the Telephony provider, then
 * shapes the resulting rows for delivery over the inbound bridge.
 *
 * Delegates the heavy column-write work to [PduPersister.persist], which
 * is the canonical AOSP implementation we have vendored at
 * `com.google.android.mms.pdu_alt.PduPersister`. After persist, we follow
 * the AOSP `MmsUtils.insertReceivedMmsMessage` recipe of overriding
 * `Mms.DATE` with local receive time (clock-drift mitigation per AOSP
 * comment) and pinning `Mms.TRANSACTION_ID` + `Mms.EXPIRY` so future
 * WAP-PUSH dedup can match against them even after the original
 * NotificationInd row has been deleted.
 *
 * Replaces the inbound usage of `MmsObject` / `MmsAddr` / `MmsPart` /
 * `MmsDatabaseWriter`. Those types are still consumed by
 * [OutboundMessagingHandler]; their removal lives in a future outbound
 * port step.
 *
 * Acceptance vs. the audit defect catalog:
 * - W1, W2: `Mms.DATE` written in seconds (PduPersister uses PDU date in
 *   seconds; we then overlay local receive time, also in seconds).
 * - W3: PduPersister inserts via `Mms.Inbox.CONTENT_URI` and writes
 *   `MESSAGE_BOX = INBOX` per AOSP convention.
 * - W4, W5: address writes go through PduPersister's address loop, which
 *   sets `MSG_ID` and `TYPE` per `PduHeaders.ADDRESS_FIELDS`.
 * - W6, W7, W8: part column writes (NAME, CONTENT_LOCATION, CONTENT_ID)
 *   come from PDU bytes verbatim with no synthesis fallback.
 * - W9: returned `partRows` come from a re-query of `content://mms/<id>/part`,
 *   so text parts are present unconditionally.
 * - W10: PduPersister logs failures via its own LogUtil; persistence
 *   exceptions surface to the caller via [MmsException].
 * - W11: `_data` blob writes happen inside PduPersister.persistPart,
 *   which performs the row insert and stream write atomically per AOSP.
 * - W12: `thread_id` is read back from the inserted row; PduPersister
 *   computed it via `Telephony.Threads.getOrCreateThreadId` with
 *   AOSP-canonical address normalisation.
 * - W13: PduPersister throws `MmsException` if the PDU header is
 *   malformed; callers must propagate the failure rather than persist a
 *   phantom row.
 * - W14: `subId` flows through unchanged from the broadcast intent.
 * - W15: `messageSize` populated by PduPersister from PDU header (no
 *   local re-summing).
 * - W16: `Mms.READ` and `Mms.SEEN` default to 0 in the provider for
 *   inbox-routed inserts; we do not override.
 * - W17: `Mms.TRANSACTION_ID` is pinned post-insert so subsequent
 *   WAP-PUSH retries can probe by it for dedup.
 */
object InboundMmsPersister {
    private const val TAG = "InboundMmsPersister"

    data class Persisted(
        val uri: Uri,
        val mmsId: Long,
        val mmsRow: MutableMap<String, Any?>,
        val partRows: List<Map<String, Any?>>,
        val addrRows: List<Map<String, Any?>>,
    )

    /**
     * Persist a downloaded MMS PDU into the Telephony provider.
     *
     * @param context Caller context — must outlive the persist call.
     * @param retrieveConf Parsed `RetrieveConf` from the MMSC download.
     * @param transactionId Hex-string transaction id from the originating
     *   `NotificationInd`. Pinned into `Mms.TRANSACTION_ID` for future
     *   WAP-PUSH dedup probes.
     * @param subId Subscription id of the receiving SIM. Caller is
     *   responsible for sourcing this from the broadcast intent
     *   (`intent.getIntExtra("subscription", -1)`) so multi-SIM is
     *   preserved end to end.
     * @param receivedTimestampSeconds Local receive time in epoch seconds.
     *   Defaults to now. Overrides the PDU's date per AOSP convention.
     * @param expirySeconds NotificationInd expiry in epoch seconds.
     *   Pass `-1` to skip the EXPIRY column write.
     */
    fun persistRetrievedMms(
        context: Context,
        retrieveConf: RetrieveConf,
        transactionId: String,
        subId: Int,
        receivedTimestampSeconds: Long = System.currentTimeMillis() / 1000L,
        expirySeconds: Long = -1L,
    ): Persisted {
        val persister = PduPersister.getPduPersister(context)
        val uri: Uri = try {
            persister.persist(
                retrieveConf,
                Mms.Inbox.CONTENT_URI,
                /* createThreadId = */ true,
                /* groupMmsEnabled = */ true,
                /* preOpenedFiles = */ null,
                /* subscriptionId = */ subId,
            )
        } catch (e: MmsException) {
            Log.e(TAG, "PduPersister.persist threw MmsException", e)
            throw e
        } ?: throw IllegalStateException("PduPersister.persist returned null URI")

        // Override DATE with local receive time and pin transaction-id +
        // expiry. Mirrors AOSP MmsUtils.insertReceivedMmsMessage:
        // > Update mms table with local time instead of PDU time
        // > Also update the transaction id and the expiry from
        // > NotificationInd so that wap push dedup would work even
        // > after the wap push is deleted.
        val cv = ContentValues(3).apply {
            put(Mms.DATE, receivedTimestampSeconds)
            put(Mms.TRANSACTION_ID, transactionId)
            if (expirySeconds > 0L) put(Mms.EXPIRY, expirySeconds)
        }
        try {
            context.contentResolver.update(uri, cv, null, null)
        } catch (e: Exception) {
            // AOSP swallows this — DATE/TRANSACTION_ID/EXPIRY update
            // failure is non-fatal; the row still exists with PDU-derived
            // values and downstream sync will pick it up.
            Log.w(TAG, "DATE/TRANSACTION_ID/EXPIRY post-update failed (non-fatal)", e)
        }

        val mmsId = ContentUris.parseId(uri)

        val mmsRowList = Query(context).query(QueryObj(contentUri = uri.toString()))
        if (mmsRowList.isEmpty()) {
            throw IllegalStateException(
                "Persisted MMS at $uri but re-query returned no row"
            )
        }
        val mmsRow = mmsRowList.first().toMutableMap().apply { put("uri", uri) }

        val partRows = Query(context).query(
            QueryObj(
                contentUri = Mms.Part.getPartUriForMessage(mmsId.toString()).toString()
            )
        )
        val addrRows = Query(context).query(
            QueryObj(
                contentUri = Mms.Addr.getAddrUriForMessage(mmsId.toString()).toString()
            )
        )

        Log.d(
            TAG,
            "Persisted MMS uri=$uri id=$mmsId parts=${partRows.size} addrs=${addrRows.size}"
        )

        return Persisted(
            uri = uri,
            mmsId = mmsId,
            mmsRow = mmsRow,
            partRows = partRows,
            addrRows = addrRows,
        )
    }
}
