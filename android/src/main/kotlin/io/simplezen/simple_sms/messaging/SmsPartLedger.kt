package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.content.Context
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.AtomicFile
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

private const val LEDGER_TAG = "SmsPartLedger"
private const val LEDGER_DIRECTORY = "simple_sms_sms_parts"
private const val RECORD_SUFFIX = ".json"

/**
 * How long a sent SMS keeps waiting for its parts' delivery reports. Many
 * carriers never send them, and SMS centres stop retrying delivery within
 * days; after this the row keeps the status it last had (UNFY-180).
 */
internal const val DELIVERY_REPORT_WAIT_MS = 7 * 24 * 60 * 60 * 1000L

/**
 * One outbound SMS's per-part results, as persisted between app processes.
 *
 * @param token the send's id, carried by each part's status intents.
 * @param sentResultCodes each part's SENT result code, `null` until reported.
 * @param deliveryStatuses each part's [Telephony.Sms] delivery status, `null`
 *   until its report arrives.
 */
@Serializable
internal data class SmsPartsRecord(
    val token: String,
    val sentResultCodes: List<Int?>,
    val deliveryStatuses: List<Int?>,
) {
    init {
        require(sentResultCodes.isNotEmpty()) { "An SMS has at least one part" }
        require(deliveryStatuses.size == sentResultCodes.size) { "Part counts disagree: $this" }
    }

    val partCount: Int get() = sentResultCodes.size

    /**
     * The message's SENT outcome once every part reported: the first failed
     * part's result code, or [Activity.RESULT_OK] when every part was sent.
     */
    val sendResultCode: Int?
        get() = if (sentResultCodes.any { it == null }) {
            null
        } else {
            sentResultCodes.firstOrNull { it != Activity.RESULT_OK } ?: Activity.RESULT_OK
        }

    /**
     * The message's delivery status from its parts' reports: failed if any
     * part failed, complete once every part is complete, else pending; `null`
     * before any report.
     */
    val deliveryStatus: Int?
        get() = when {
            deliveryStatuses.all { it == null } -> null
            deliveryStatuses.any { it == Telephony.Sms.STATUS_FAILED } -> Telephony.Sms.STATUS_FAILED
            deliveryStatuses.all { it == Telephony.Sms.STATUS_COMPLETE } -> Telephony.Sms.STATUS_COMPLETE
            else -> Telephony.Sms.STATUS_PENDING
        }

    /** Whether no later result can change the row: it failed to send, or its delivery is final. */
    val finished: Boolean
        get() {
            val sent = sendResultCode ?: return false
            return sent != Activity.RESULT_OK ||
                deliveryStatus == Telephony.Sms.STATUS_COMPLETE ||
                deliveryStatus == Telephony.Sms.STATUS_FAILED
        }
}

/** How one part's SENT result changed its send. */
internal sealed interface SentPartProgress {
    /** No send has this token: it was superseded or forgotten. */
    data object UnknownSend : SentPartProgress

    /** This part already reported; the repeat is ignored. */
    data object DuplicatePart : SentPartProgress

    /** Other parts have not reported yet. */
    data object AwaitingParts : SentPartProgress

    /** Every part has reported; [resultCode] is the message's SENT outcome. */
    data class Settled(val resultCode: Int) : SentPartProgress
}

/**
 * Purpose: aggregates each outbound SMS's per-part SENT results and delivery
 * reports, persisted so the parts of one send add up across app processes
 * (UNFY-180).
 *
 * A multipart SMS fires one SENT broadcast per part and up to one delivery
 * report per part, possibly to different app processes: Android starts the
 * app for each result after the sending process died. The row is settled
 * from the message as a whole: sent only once every part was sent, failed if
 * any part failed, delivered only once every part was delivered.
 *
 * One small file per send, under a directory per row (`<row id>/<token>`),
 * written atomically on the calling thread, like the provider writes that
 * follow it; every access is serialized process-wide. A record is kept until
 * no later result can change its row, or for [DELIVERY_REPORT_WAIT_MS] after
 * its last result.
 *
 * @param directory the ledger's root directory.
 * @param nowMillis the wall clock, compared with the records' modification times.
 */
internal class SmsPartLedger(
    private val directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val json = Json

    /**
     * Purpose: records a new send before its parts are handed to the radio.
     *
     * Forgets any earlier send for the same row: the provider reuses a deleted
     * row's `_id`, and the earlier send's late results must not count toward
     * this one. Also drops every record untouched for
     * [DELIVERY_REPORT_WAIT_MS]. Neither reads a record.
     *
     * @param rowId the message row's `_id`.
     * @param partCount how many parts the body was divided into; ≥ 1.
     * @returns the new send's parts, each carrying its token.
     * @throws IOException when the record cannot be written; the send must
     *   then not start, since its results could not be aggregated.
     * @throws IllegalArgumentException when [partCount] is below 1.
     */
    fun start(rowId: Long, partCount: Int): List<SmsPartRef> = synchronized(LOCK) {
        require(partCount >= 1) { "An SMS has at least one part, got $partCount" }
        pruneOlderThan(nowMillis() - DELIVERY_REPORT_WAIT_MS)
        rowDirectory(rowId).listFiles().orEmpty().forEach { it.delete() }
        val token = UUID.randomUUID().toString()
        write(rowId, SmsPartsRecord(token, List(partCount) { null }, List(partCount) { null }))
        (0 until partCount).map { SmsPartRef(token, rowId, it, partCount) }
    }

    /**
     * Purpose: whether a send to [rowId] is tracked: its record is kept, so
     * its results may still come.
     *
     * @param rowId the message row's `_id`.
     * @returns true while a send to the row is tracked.
     * @throws nothing; an unreadable ledger tracks nothing.
     */
    fun tracks(rowId: Long): Boolean = synchronized(LOCK) {
        rowDirectory(rowId).listFiles().orEmpty().any { it.name.endsWith(RECORD_SUFFIX) }
    }

    /**
     * Purpose: forgets a send that never started (the radio refused it).
     *
     * @param part any part of the send.
     * @returns nothing.
     * @throws nothing.
     */
    fun forget(part: SmsPartRef) {
        synchronized(LOCK) { recordFile(part)?.delete() }
    }

    /**
     * Purpose: records one part's SENT result.
     *
     * @param part the reporting part, from its intent.
     * @param resultCode the broadcast's result code.
     * @returns how the send changed.
     * @throws IOException when the record cannot be read or written.
     * @throws IllegalArgumentException when the part does not fit its send.
     */
    fun recordSent(part: SmsPartRef, resultCode: Int): SentPartProgress {
        synchronized(LOCK) {
            val record = read(part) ?: return SentPartProgress.UnknownSend
            requireFits(record, part)
            if (record.sentResultCodes[part.index] != null) return SentPartProgress.DuplicatePart
            val updated = record.copy(sentResultCodes = record.sentResultCodes.replaced(part.index, resultCode))
            save(part, updated)
            return updated.sendResultCode?.let(SentPartProgress::Settled) ?: SentPartProgress.AwaitingParts
        }
    }

    /**
     * Purpose: records one part's delivery report.
     *
     * A later report for the same part replaces the earlier one: the network
     * sends a pending report first and the final one after it. The last
     * part's 3GPP2 (CDMA) report stands for every part: CDMA asks for a
     * report on a multipart SMS's last part only.
     *
     * @param part the reporting part, from its intent.
     * @param status the report's [Telephony.Sms] delivery status.
     * @param format the report's PDU format (`3gpp` or `3gpp2`).
     * @returns the message's delivery status after this report, or `null`
     *   when no send has this token.
     * @throws IOException when the record cannot be read or written.
     * @throws IllegalArgumentException when the part does not fit its send.
     */
    fun recordDelivery(part: SmsPartRef, status: Int, format: String?): Int? {
        synchronized(LOCK) {
            val record = read(part) ?: return null
            requireFits(record, part)
            val wholeMessage = format == SmsMessage.FORMAT_3GPP2 && part.index == record.partCount - 1
            val statuses =
                if (wholeMessage) List(record.partCount) { status }
                else record.deliveryStatuses.replaced(part.index, status)
            val updated = record.copy(deliveryStatuses = statuses)
            save(part, updated)
            return updated.deliveryStatus
        }
    }

    // Writes [record], or forgets it once no later result can change its row.
    private fun save(part: SmsPartRef, record: SmsPartsRecord) {
        if (record.finished) recordFile(part)?.delete() else write(part.rowId, record)
    }

    private fun requireFits(record: SmsPartsRecord, part: SmsPartRef) {
        require(part.count == record.partCount && part.index in 0 until part.count) {
            "Part ${part.index + 1}/${part.count} does not fit send ${record.token} of ${record.partCount} parts"
        }
    }

    private fun pruneOlderThan(cutoffMillis: Long) {
        directory.listFiles().orEmpty().forEach { row ->
            row.listFiles().orEmpty().filter { it.lastModified() < cutoffMillis }.forEach { it.delete() }
            if (row.isDirectory && row.list().isNullOrEmpty()) row.delete()
        }
    }

    private fun write(rowId: Long, record: SmsPartsRecord) {
        val row = rowDirectory(rowId)
        if (!row.isDirectory && !row.mkdirs()) throw IOException("Could not create the SMS part ledger at $row")
        val file = AtomicFile(File(row, record.token + RECORD_SUFFIX))
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(SmsPartsRecord.serializer(), record).toByteArray())
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    // A record that cannot be decoded can never be aggregated; it is logged
    // and removed. One that cannot be read is left for a later attempt.
    private fun read(part: SmsPartRef): SmsPartsRecord? {
        val file = recordFile(part) ?: return null
        if (!file.baseFile.exists()) return null
        val text = String(file.readFully())
        return try {
            json.decodeFromString(SmsPartsRecord.serializer(), text)
                .also { require(it.token == part.token) { "Record names send ${it.token}" } }
        } catch (e: SerializationException) {
            Log.e(LEDGER_TAG, "Undecodable SMS part record ${part.token}; removing it", e)
            file.delete()
            null
        } catch (e: IllegalArgumentException) {
            Log.e(LEDGER_TAG, "Invalid SMS part record ${part.token}; removing it", e)
            file.delete()
            null
        }
    }

    private fun rowDirectory(rowId: Long) = File(directory, rowId.toString())

    // Tokens are minted here (UUIDs); anything else could step outside the
    // ledger directory, so it names no record.
    private fun recordFile(part: SmsPartRef): AtomicFile? {
        if (part.token.isEmpty() || !part.token.all { it.isLetterOrDigit() || it == '-' }) return null
        return AtomicFile(File(rowDirectory(part.rowId), part.token + RECORD_SUFFIX))
    }

    private fun <T> List<T>.replaced(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }

    companion object {
        // Every ledger over the app's files reads and writes the same records.
        private val LOCK = Any()

        /**
         * Purpose: the app's ledger, in its private files.
         *
         * @param context any context.
         * @returns the ledger.
         * @throws nothing.
         */
        fun forApp(context: Context): SmsPartLedger = SmsPartLedger(File(context.filesDir, LEDGER_DIRECTORY))
    }
}

/**
 * Whether an SMS result is stale: it carries no [part] token, while a tracked
 * send to its row [rowId] is under way, whose own results all carry its token
 * (UNFY-180). Such a result comes from a send made before parts were tracked,
 * whose status intents outlived an app update while the provider reused its
 * row id, and was on its way before they were cancelled
 * ([cancelUntrackedStatusIntents]).
 */
internal fun isStaleUntrackedSmsResult(context: Context, part: SmsPartRef?, rowId: Long?): Boolean =
    part == null && rowId != null && SmsPartLedger.forApp(context).tracks(rowId)
