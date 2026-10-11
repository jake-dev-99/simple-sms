package io.simplezen.simple_sms.messaging

import android.app.Activity
import android.content.Context
import android.provider.Telephony
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
 * @param messageUri the message's `content://sms/N` row.
 * @param messageId the row's `_id`.
 * @param sentResultCodes each part's SENT result code, `null` until reported.
 * @param deliveryStatuses each part's [Telephony.Sms] delivery status, `null`
 *   until its report arrives.
 * @param createdAtMillis when the send started (wall clock), for pruning.
 */
@Serializable
internal data class SmsPartsRecord(
    val token: String,
    val messageUri: String,
    val messageId: Int,
    val sentResultCodes: List<Int?>,
    val deliveryStatuses: List<Int?>,
    val createdAtMillis: Long,
) {
    init {
        require(sentResultCodes.isNotEmpty()) { "An SMS has at least one part" }
        require(deliveryStatuses.size == sentResultCodes.size) { "Part counts disagree: $this" }
    }

    val partCount: Int get() = sentResultCodes.size

    /** Whether every part reported its SENT result. */
    val sendSettled: Boolean get() = sentResultCodes.none { it == null }

    /**
     * The message's SENT outcome once every part reported: the first failed
     * part's result code, or [Activity.RESULT_OK] when every part was sent.
     */
    val sendResultCode: Int?
        get() = if (!sendSettled) null else sentResultCodes.firstOrNull { it != Activity.RESULT_OK } ?: Activity.RESULT_OK

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
 * A multipart SMS fires one SENT broadcast and one delivery report per part,
 * possibly to different app processes: Android starts the app for each
 * result after the sending process died. The row is settled from the
 * message as a whole: sent only once every part was sent, failed if any part
 * failed, delivered only once every part was delivered.
 *
 * One small file per send, written atomically on the calling thread, like
 * the provider writes that follow it; every access is serialized
 * process-wide. A record is kept until no later
 * result can change its row, or for [DELIVERY_REPORT_WAIT_MS].
 *
 * @param directory the directory holding one file per send.
 * @param nowMillis the wall clock, for pruning.
 */
internal class SmsPartLedger(
    private val directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val json = Json

    /**
     * Purpose: records a new send before its parts are handed to the radio.
     *
     * Also drops every record older than [DELIVERY_REPORT_WAIT_MS], and any
     * earlier send for the same row: the provider reuses a deleted row's
     * `_id`, and the earlier send's late results must not count toward this
     * one.
     *
     * @param messageUri the message's row.
     * @param messageId the row's `_id`.
     * @param partCount how many parts the body was divided into; ≥ 1.
     * @returns the new send's token.
     * @throws IOException when the record cannot be written; the send must
     *   then not start, since its results could not be aggregated.
     * @throws IllegalArgumentException when [partCount] is below 1.
     */
    fun start(messageUri: String, messageId: Int, partCount: Int): String = synchronized(LOCK) {
        require(partCount >= 1) { "An SMS has at least one part, got $partCount" }
        val cutoff = nowMillis() - DELIVERY_REPORT_WAIT_MS
        records().filter { it.messageUri == messageUri || it.createdAtMillis < cutoff }.forEach { forget(it.token) }
        val record = SmsPartsRecord(
            token = UUID.randomUUID().toString(),
            messageUri = messageUri,
            messageId = messageId,
            sentResultCodes = List(partCount) { null },
            deliveryStatuses = List(partCount) { null },
            createdAtMillis = nowMillis(),
        )
        write(record)
        record.token
    }

    /**
     * Purpose: forgets a send that never started (the radio refused it).
     *
     * @param token the send's token.
     * @returns nothing.
     * @throws nothing.
     */
    fun forget(token: String) {
        synchronized(LOCK) { recordFile(token)?.delete() }
    }

    /**
     * Purpose: records one part's SENT result.
     *
     * @param token the send's token.
     * @param partIndex the part's zero-based index.
     * @param partCount the send's part count, as the part's intent carries it.
     * @param resultCode the broadcast's result code.
     * @returns how the send changed.
     * @throws IllegalArgumentException when the part does not fit the send.
     */
    fun recordSent(token: String, partIndex: Int, partCount: Int, resultCode: Int): SentPartProgress {
        synchronized(LOCK) {
            val record = read(token) ?: return SentPartProgress.UnknownSend
            requirePart(record, partIndex, partCount)
            if (record.sentResultCodes[partIndex] != null) return SentPartProgress.DuplicatePart
            val updated = record.copy(sentResultCodes = record.sentResultCodes.replaced(partIndex, resultCode))
            save(updated)
            return updated.sendResultCode?.let(SentPartProgress::Settled) ?: SentPartProgress.AwaitingParts
        }
    }

    /**
     * Purpose: records one part's delivery report.
     *
     * A later report for the same part replaces the earlier one: the network
     * sends a pending report first and the final one after it.
     *
     * @param token the send's token.
     * @param partIndex the part's zero-based index.
     * @param partCount the send's part count, as the part's intent carries it.
     * @param status the report's [Telephony.Sms] delivery status.
     * @returns the message's delivery status after this report, or `null`
     *   when no send has this token.
     * @throws IllegalArgumentException when the part does not fit the send.
     */
    fun recordDelivery(token: String, partIndex: Int, partCount: Int, status: Int): Int? {
        synchronized(LOCK) {
            val record = read(token) ?: return null
            requirePart(record, partIndex, partCount)
            val updated = record.copy(deliveryStatuses = record.deliveryStatuses.replaced(partIndex, status))
            save(updated)
            return updated.deliveryStatus
        }
    }

    // Writes [record], or forgets it once no later result can change its row.
    private fun save(record: SmsPartsRecord) {
        if (record.finished) forget(record.token) else write(record)
    }

    private fun requirePart(record: SmsPartsRecord, partIndex: Int, partCount: Int) {
        require(partCount == record.partCount && partIndex in 0 until partCount) {
            "Part ${partIndex + 1}/$partCount does not fit send ${record.token} of ${record.partCount} parts"
        }
    }

    private fun write(record: SmsPartsRecord) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create the SMS part ledger at $directory")
        }
        val file = checkNotNull(recordFile(record.token)) { "Invalid token ${record.token}" }
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(SmsPartsRecord.serializer(), record).toByteArray())
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    private fun read(token: String): SmsPartsRecord? {
        val file = recordFile(token) ?: return null
        if (!file.baseFile.exists()) return null
        return decode(token, file)
    }

    private fun records(): List<SmsPartsRecord> =
        directory.listFiles { file -> file.name.endsWith(RECORD_SUFFIX) }
            .orEmpty()
            .mapNotNull { file ->
                val token = file.name.removeSuffix(RECORD_SUFFIX)
                val atomic = recordFile(token) ?: run {
                    Log.e(LEDGER_TAG, "Unexpected file ${file.name} in the SMS part ledger; removing it")
                    file.delete()
                    return@mapNotNull null
                }
                decode(token, atomic)
            }

    // A record that cannot be read or decoded can never be aggregated; it is
    // logged and removed rather than failing every later result.
    private fun decode(token: String, file: AtomicFile): SmsPartsRecord? = try {
        json.decodeFromString(SmsPartsRecord.serializer(), String(file.readFully()))
            .also { require(it.token == token) { "Record names send ${it.token}" } }
    } catch (e: IOException) {
        Log.e(LEDGER_TAG, "Unreadable SMS part record $token; removing it", e)
        file.delete()
        null
    } catch (e: SerializationException) {
        Log.e(LEDGER_TAG, "Undecodable SMS part record $token; removing it", e)
        file.delete()
        null
    } catch (e: IllegalArgumentException) {
        Log.e(LEDGER_TAG, "Invalid SMS part record $token; removing it", e)
        file.delete()
        null
    }

    // Tokens are minted here (UUIDs); anything else could step outside the
    // ledger directory, so it names no record.
    private fun recordFile(token: String): AtomicFile? {
        if (token.isEmpty() || !token.all { it.isLetterOrDigit() || it == '-' }) return null
        return AtomicFile(File(directory, token + RECORD_SUFFIX))
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
