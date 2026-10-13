package io.simplezen.simple_sms.messaging

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import io.simplezen.simple_sms.mms.codec.PduHeaders
import io.simplezen.simple_sms.mms.sending.Transaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

private const val LEDGER_TAG = "MmsDeliveryLedger"
private const val LEDGER_DIRECTORY = "simple_sms_mms_delivery"
private const val RECORD_SUFFIX = ".json"

/**
 * How long a sent MMS keeps its recipients' delivery reports: the MMSC
 * reports a message expired once the expiry its send set passes
 * ([Transaction.DEFAULT_EXPIRY_TIME]), plus a day for that report to arrive.
 * After this the row keeps the status it last had (UNFY-110).
 */
internal const val MMS_DELIVERY_REPORT_WAIT_MS = (Transaction.DEFAULT_EXPIRY_TIME + 24 * 60 * 60) * 1000L

/**
 * One sent MMS's recipients' delivery verdicts, as persisted between app
 * processes.
 *
 * @param messageId the MMSC's message id, which every report names.
 * @param verdicts each reporting recipient's first verdict, an X-Mms-Status
 *   ([isMmsDeliveryVerdict]), keyed by the recipient's address as the row
 *   stores it. A recipient's later reports are ignored.
 * @param firstFailedStatus the first failed verdict to arrive; `null` exactly
 *   while no recipient failed. A record that disagrees with its verdicts is
 *   invalid: reading it removes it.
 */
@Serializable
internal data class MmsDeliveryRecord(
    val messageId: String,
    val verdicts: Map<String, Int> = emptyMap(),
    val firstFailedStatus: Int? = null,
) {
    init {
        require(verdicts.values.all(::isMmsDeliveryVerdict)) { "Not every verdict is one: $this" }
        require(
            if (firstFailedStatus == null) {
                verdicts.values.none { it in MMS_DELIVERY_FAILED_STATUSES }
            } else {
                firstFailedStatus in MMS_DELIVERY_FAILED_STATUSES && firstFailedStatus in verdicts.values
            }
        ) { "First failure disagrees with the verdicts: $this" }
    }

    /**
     * This record with [recipient]'s verdict [status], unless it already
     * holds one: a recipient's first verdict stands.
     *
     * @throws IllegalArgumentException when [status] is not a verdict.
     */
    fun withVerdict(recipient: String, status: Int): MmsDeliveryRecord {
        require(isMmsDeliveryVerdict(status)) { "X-Mms-Status 0x${Integer.toHexString(status)} is not a verdict" }
        if (recipient in verdicts) return this
        return copy(
            verdicts = verdicts + (recipient to status),
            firstFailedStatus = firstFailedStatus ?: status.takeIf { it in MMS_DELIVERY_FAILED_STATUSES },
        )
    }

    /**
     * The message's verdict over its [recipientCount] recipients, as recorded
     * on its row, once every one of them reported: [PduHeaders.STATUS_RETRIEVED]
     * when all got it, the first failure when none did. `null` before then,
     * and when some got it and some never will: the message is not failed
     * for those who got it, and a resend would reach them twice.
     */
    fun verdict(recipientCount: Int): Int? {
        if (verdicts.size < recipientCount) return null
        return when {
            firstFailedStatus == null -> PduHeaders.STATUS_RETRIEVED
            verdicts.values.all { it in MMS_DELIVERY_FAILED_STATUSES } -> firstFailedStatus
            else -> null
        }
    }
}

/**
 * Purpose: adds up each sent MMS's per-recipient delivery reports, persisted
 * so the reports of one group MMS add up across app processes (UNFY-110).
 *
 * A group MMS gets one M-Delivery.ind per recipient, each a WAP push that may
 * reach a different app process. The row is settled from the message as a
 * whole ([MmsDeliveryRecord.verdict]): delivered once every recipient got
 * it, failed once none will.
 *
 * One small file per row (`<row id>.json`), written atomically on the calling
 * thread, like the provider write that follows it; every access is
 * serialized process-wide. A record holds one message id: a report naming
 * another id (the row was resent, or reused by a new message) starts the row
 * over. A record is dropped [MMS_DELIVERY_REPORT_WAIT_MS] after its last
 * report.
 *
 * @param directory the ledger's root directory.
 * @param nowMillis the wall clock, compared with the records' modification times.
 */
internal class MmsDeliveryLedger(
    private val directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val json = Json

    /**
     * Purpose: records [recipient]'s verdict for the sent MMS at [rowId].
     *
     * @param rowId the message row's `_id`.
     * @param messageId the MMSC's message id the report names.
     * @param recipient the reporting recipient, as the row stores its address.
     * @param status the report's X-Mms-Status verdict ([isMmsDeliveryVerdict]).
     * @param recipientCount how many recipients the row has; ≥ 1.
     * @returns the message's verdict after this report
     *   ([MmsDeliveryRecord.verdict]), or `null` while it waits on other
     *   recipients.
     * @throws IOException when the record cannot be read or written.
     * @throws IllegalArgumentException when [status] is not a verdict, or
     *   [recipientCount] is below 1.
     */
    fun record(rowId: Long, messageId: String, recipient: String, status: Int, recipientCount: Int): Int? =
        synchronized(LOCK) {
            require(recipientCount >= 1) { "An MMS has at least one recipient, got $recipientCount" }
            pruneOlderThan(nowMillis() - MMS_DELIVERY_REPORT_WAIT_MS)
            val file = recordFile(rowId)
            val stored = read(file)?.takeIf { it.messageId == messageId } ?: MmsDeliveryRecord(messageId)
            val record = stored.withVerdict(recipient, status)
            // A repeated report changes nothing, nor extends the record's window.
            if (record == stored) {
                Log.i(LEDGER_TAG, "Row $rowId's recipient reported again; its first verdict stands")
            } else {
                write(file, record)
            }
            record.verdict(recipientCount)
        }

    private fun pruneOlderThan(cutoffMillis: Long) {
        directory.listFiles().orEmpty()
            .filter { it.name.endsWith(RECORD_SUFFIX) && it.lastModified() < cutoffMillis }
            .forEach { AtomicFile(it).delete() }
    }

    private fun write(file: AtomicFile, record: MmsDeliveryRecord) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create the MMS delivery ledger at $directory")
        }
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(MmsDeliveryRecord.serializer(), record).toByteArray())
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    // A record that cannot be decoded can never be added to; it is logged and
    // removed, and the row starts over from this report. One that cannot be
    // read is left for a later report.
    private fun read(file: AtomicFile): MmsDeliveryRecord? {
        if (!file.baseFile.exists()) return null
        val text = String(file.readFully())
        return try {
            json.decodeFromString(MmsDeliveryRecord.serializer(), text)
        } catch (e: SerializationException) {
            Log.e(LEDGER_TAG, "Undecodable MMS delivery record ${file.baseFile.name}; removing it", e)
            file.delete()
            null
        } catch (e: IllegalArgumentException) {
            Log.e(LEDGER_TAG, "Invalid MMS delivery record ${file.baseFile.name}; removing it", e)
            file.delete()
            null
        }
    }

    private fun recordFile(rowId: Long) = AtomicFile(File(directory, rowId.toString() + RECORD_SUFFIX))

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
        fun forApp(context: Context): MmsDeliveryLedger = MmsDeliveryLedger(File(context.filesDir, LEDGER_DIRECTORY))
    }
}
