package io.simplezen.simple_sms.messaging

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.VisibleForTesting
import io.simplezen.simple_query.ContentQuery
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

private const val ROW_WRITES_TAG = "OwedRowWrites"
private const val OWED_WRITES_DIRECTORY = "simple_sms_owed_row_writes"
private const val OWED_WRITE_SUFFIX = ".json"

/** What a [RowWrite] records; a row owes at most one write of each kind. */
@Serializable
internal enum class RowWriteKind {
    /** A SENT result ([sendResultWrite]). */
    SEND_RESULT,

    /** An SMS delivery status ([persistSmsDeliveryStatus]). */
    DELIVERY_STATUS,
}

/**
 * One guarded write to a message row: the row at [uri] takes [values] only
 * while it still matches [selection] (UNFY-335). Every send-status write sets
 * integer columns, so [values] are integers, which keeps a write that did not
 * land simple to persist ([OwedRowWrites]).
 *
 * @param uri the message row, `content://sms/<id>` or `content://mms/<id>`.
 * @param kind what it records.
 * @param values the columns to set, and their values.
 * @param selection the guard the row must still match.
 * @param selectionArgs the guard's arguments.
 * @param final whether it records an outcome nothing later may replace: a
 *   carrier failure, or a failed delivery.
 * @param tied whether [selection] also names its row's `date` ([tiedTo]).
 */
@Serializable
internal data class RowWrite(
    val uri: String,
    val kind: RowWriteKind,
    val values: Map<String, Long>,
    val selection: String,
    val selectionArgs: List<String>,
    val final: Boolean = false,
    val tied: Boolean = false,
) {
    fun contentValues(): ContentValues = ContentValues().apply { values.forEach { (column, value) -> put(column, value) } }
}

/** How a [RowWrite] went ([applyRowWrite]). */
internal enum class RowWriteOutcome {
    /** The row took the write. */
    WRITTEN,

    /** No row matches the write's guard: the row is gone, or already past it. */
    NOT_APPLICABLE,

    /**
     * The row still matches the write's guard, yet took nothing: the provider
     * ignored the write, as it does for an app without the default-SMS role
     * or its WRITE_SMS app-op.
     */
    IGNORED,

    /**
     * No row took the write, and the provider returned no cursor to read it
     * back, so whether it still matches the write's guard is unknown.
     */
    UNREADABLE,
}

/**
 * The provider ignored a send-status write: the app has lost the default-SMS
 * role or its WRITE_SMS app-op (UNFY-335). The write is owed and retried
 * ([OwedRowWrites]).
 */
internal class RowWriteIgnoredException(messageUri: Uri) : IllegalStateException(
    "The SMS provider ignored the status write for $messageUri (no default-SMS role or WRITE_SMS " +
        "app-op); it is retried later",
)

/**
 * A send-status write took no row, and the provider returned no cursor to
 * read the row back and tell why (UNFY-335). The write is owed and retried
 * ([OwedRowWrites]).
 */
internal class RowWriteUnreadableException(messageUri: Uri) : IllegalStateException(
    "The status write for $messageUri took no row, and the provider returned no cursor to read it back",
)

/**
 * Purpose: applies [write] to its row, and tells a write the provider ignored
 * from one the row no longer matches.
 *
 * The SMS provider ignores, rather than refuses, writes from an app without
 * write access (the default-SMS role and its WRITE_SMS app-op), so when no
 * row took the write, the row is read back through `simple_query`: still
 * matching the guard means the write was ignored.
 *
 * @returns how the write went.
 * @throws Exception the provider's failure to write or read.
 */
internal fun applyRowWrite(context: Context, write: RowWrite): RowWriteOutcome {
    val args = write.selectionArgs.toTypedArray()
    val updated = context.contentResolver.update(Uri.parse(write.uri), write.contentValues(), write.selection, args)
    if (updated > 0) return RowWriteOutcome.WRITTEN
    val matching = ContentQuery.queryOrNull(context, write.uri, arrayOf("_id"), write.selection, args)
        ?: return RowWriteOutcome.UNREADABLE
    return if (matching.isEmpty()) RowWriteOutcome.NOT_APPLICABLE else RowWriteOutcome.IGNORED
}

/**
 * Purpose: applies [write] ([applyRowWrite]), and owes it ([OwedRowWrites])
 * when it did not land: the provider ignored it, or failed, or could not be
 * read back to tell.
 *
 * @returns how the write went.
 * @throws Exception the provider's failure, after the write is owed.
 */
internal fun recordRowWrite(context: Context, write: RowWrite): RowWriteOutcome {
    val outcome = try {
        applyRowWrite(context, write)
    } catch (e: Exception) {
        oweRowWrite(context, write)
        throw e
    }
    val owe = when (outcome) {
        RowWriteOutcome.IGNORED, RowWriteOutcome.UNREADABLE -> true
        // The row is failed for lack of a result while that result is owed:
        // the report waits for it (see [OwedRowWrites.retry]).
        RowWriteOutcome.NOT_APPLICABLE ->
            write.kind == RowWriteKind.DELIVERY_STATUS &&
                OwedRowWrites.forApp(context).owes(write.uri, RowWriteKind.SEND_RESULT)
        RowWriteOutcome.WRITTEN -> false
    }
    if (owe) oweRowWrite(context, write)
    return outcome
}

// Owing is the fallback for a write that already failed; one the store
// can't keep is logged and lost, as before UNFY-335.
private fun oweRowWrite(context: Context, write: RowWrite) {
    val owed = when (val row = readRowDate(context, write.uri)) {
        RowDate.Gone -> {
            Log.i(ROW_WRITES_TAG, "${write.uri} is gone; its unwritten status write is dropped")
            return
        }
        RowDate.Unknown -> write.also {
            Log.w(ROW_WRITES_TAG, "${write.uri} can't be read; its unwritten status write is kept untied")
        }
        is RowDate.Known -> write.tiedTo(row.date)
    }
    try {
        if (!OwedRowWrites.forApp(context).owe(owed)) {
            Log.w(ROW_WRITES_TAG, "${write.uri} already owes a final ${write.kind}; this one is dropped")
        }
    } catch (e: IOException) {
        Log.e(ROW_WRITES_TAG, "Could not keep the unwritten status write for ${write.uri} to retry", e)
    }
}

/** What a row's `date` read found ([readRowDate]). */
private sealed interface RowDate {
    /** The row is gone: nothing is left to write to. */
    data object Gone : RowDate

    /** The row could not be read, or its date is not an integer. */
    data object Unknown : RowDate

    data class Known(val date: Long) : RowDate
}

// The row's `date`, which SMS and MMS rows both have: when the message was
// sent or received (SMS in milliseconds, MMS in seconds).
private fun readRowDate(context: Context, uri: String): RowDate {
    val rows = try {
        ContentQuery.queryOrNull(context, uri, arrayOf(DATE_COLUMN))
    } catch (e: Exception) {
        Log.e(ROW_WRITES_TAG, "Could not read the date of $uri", e)
        return RowDate.Unknown
    } ?: return RowDate.Unknown
    if (rows.isEmpty()) return RowDate.Gone
    val date = rows.first()[DATE_COLUMN] as? Long ?: return RowDate.Unknown
    return RowDate.Known(date)
}

/**
 * This write, also guarded by its row's `date`, so it only ever lands on
 * that row: a row deleted while the write is owed may have its id reused,
 * by whichever app holds the default-SMS role now. (This app forgets a row's
 * owed writes when its own next send takes the row, [OwedRowWrites.forget].)
 */
@VisibleForTesting
internal fun RowWrite.tiedTo(date: Long): RowWrite = copy(
    selection = "($selection) AND $DATE_COLUMN = ?",
    selectionArgs = selectionArgs + date.toString(),
    tied = true,
)

private const val DATE_COLUMN = "date"

/**
 * Purpose: keeps the send-status writes that did not land on their rows, so
 * they are retried rather than lost (UNFY-335): the provider ignored them
 * (the app lost the default-SMS role or its WRITE_SMS app-op) or failed.
 * Each is tied to its row ([tiedTo]) as soon as the row can be read: when it
 * is owed, or else when it is next retried.
 *
 * One small file per row and [RowWriteKind], written atomically; a later
 * write of the same kind to the same row replaces the earlier one, unless that
 * one is [RowWrite.final]. Each is retried ([retry]) at every engine start,
 * before the stale-outbox sweep, and after each new send starts; a row's send
 * result goes before its delivery status, which needs the row sent. One is
 * dropped once it lands, once its row no longer matches its guard, or after
 * [DELIVERY_REPORT_WAIT_MS]. Every access is serialized process-wide.
 *
 * @param directory the store's root directory.
 * @param nowMillis the wall clock, compared with the files' modification times.
 */
internal class OwedRowWrites(
    private val directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val json = Json

    /**
     * Purpose: keeps [write] to retry, in place of any write of its kind owed
     * to its row, unless that one is [RowWrite.final].
     *
     * @returns whether it was kept: false when a final write is owed instead.
     * @throws IOException when it cannot be written.
     */
    fun owe(write: RowWrite): Boolean = synchronized(LOCK) {
        val target = fileFor(write)
        if (target.exists() && read(target)?.final == true) return false
        store(target, write)
        true
    }

    /**
     * Purpose: whether [uri] owes a write of [kind].
     *
     * @throws nothing.
     */
    fun owes(uri: String, kind: RowWriteKind): Boolean = synchronized(LOCK) { fileFor(Uri.parse(uri), kind).exists() }

    // Writes [write] to [target]; the caller holds LOCK.
    private fun store(target: File, write: RowWrite) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create the owed row-write store at $directory")
        }
        val file = AtomicFile(target)
        val stream = file.startWrite()
            try {
                stream.write(json.encodeToString(RowWrite.serializer(), write).toByteArray())
                file.finishWrite(stream)
            } catch (e: IOException) {
                file.failWrite(stream)
                throw e
            }
        // Its window runs on this store's clock.
        file.baseFile.setLastModified(nowMillis())
    }

    /**
     * Purpose: forgets every write owed to [uri]: this app's new send has
     * taken the row, so the message they were for is gone.
     *
     * @throws nothing.
     */
    fun forget(uri: Uri) {
        synchronized(LOCK) {
            RowWriteKind.entries.forEach { kind ->
                val file = fileFor(uri, kind)
                if (file.exists()) AtomicFile(file).delete()
            }
        }
    }

    /**
     * Purpose: the rows that still owe a write; a seam for tests.
     *
     * @returns their URIs.
     * @throws nothing; an unreadable write is left for [retry] to drop.
     */
    @VisibleForTesting
    fun rows(): Set<Uri> = synchronized(LOCK) { entries().mapNotNull { it.second?.uri?.let(Uri::parse) }.toSet() }

    /**
     * Purpose: retries every owed write, dropping each that lands or no
     * longer applies, and each owed for [DELIVERY_REPORT_WAIT_MS] or more.
     * A write still ignored, or still failing, is kept, as is a row's
     * delivery status while its send result is still owed. One not yet tied
     * to its row is tied first, or dropped if its row is gone.
     *
     * @param context any context.
     * @param skip rows a live send awaits: they are that send's now.
     * @returns how many landed.
     * @throws nothing; each failure is logged.
     */
    fun retry(context: Context, skip: Set<Uri> = emptySet()): Int {
        val cutoff = nowMillis() - DELIVERY_REPORT_WAIT_MS
        val owed = synchronized(LOCK) { entries() }.sortedBy { (_, write) -> write?.kind?.ordinal ?: -1 }
        var landed = 0
        for ((file, owedWrite) in owed) {
            if (owedWrite == null || file.lastModified() < cutoff) {
                if (owedWrite != null) Log.w(ROW_WRITES_TAG, "Gave up on the status write owed to ${owedWrite.uri}")
                dropIfUnchanged(file, owedWrite)
                continue
            }
            if (Uri.parse(owedWrite.uri) in skip) continue
            val write = tiedForRetry(context, file, owedWrite) ?: continue
            val outcome = try {
                applyRowWrite(context, write)
            } catch (e: Exception) {
                Log.e(ROW_WRITES_TAG, "Retrying the status write owed to ${write.uri} failed", e)
                continue
            }
            when (outcome) {
                RowWriteOutcome.WRITTEN -> {
                    landed++
                    dropIfUnchanged(file, write)
                }
                RowWriteOutcome.NOT_APPLICABLE ->
                    if (write.kind == RowWriteKind.DELIVERY_STATUS && owes(write.uri, RowWriteKind.SEND_RESULT)) {
                        Log.i(ROW_WRITES_TAG, "The delivery status owed to ${write.uri} waits for its send result")
                    } else {
                        dropIfUnchanged(file, write)
                    }
                RowWriteOutcome.IGNORED -> Log.w(ROW_WRITES_TAG, "The status write owed to ${write.uri} is still ignored")
                RowWriteOutcome.UNREADABLE -> Log.w(ROW_WRITES_TAG, "The row owing a status write, ${write.uri}, is unreadable")
            }
        }
        return landed
    }

    // [write], tied to its row ([tiedTo]) if it was not yet; null, leaving it
    // for a later retry, while the row can't be read, or after dropping it
    // when the row is gone.
    private fun tiedForRetry(context: Context, file: File, write: RowWrite): RowWrite? {
        if (write.tied) return write
        return when (val row = readRowDate(context, write.uri)) {
            RowDate.Gone -> null.also { dropIfUnchanged(file, write) }
            RowDate.Unknown -> null
            is RowDate.Known -> write.tiedTo(row.date).also { tied ->
                synchronized(LOCK) { if (read(file) == write) store(file, tied) }
            }
        }
    }

    // Each owed file and its write ([read]).
    private fun entries(): List<Pair<File, RowWrite?>> =
        directory.listFiles().orEmpty().filter { it.name.endsWith(OWED_WRITE_SUFFIX) }.map { it to read(it) }

    // The write in [file]; null, logged, when it cannot be read or decoded,
    // which no retry can mend.
    private fun read(file: File): RowWrite? = try {
        json.decodeFromString(RowWrite.serializer(), String(AtomicFile(file).readFully()))
    } catch (e: IOException) {
        Log.e(ROW_WRITES_TAG, "Unreadable owed row write ${file.name}", e)
        null
    } catch (e: SerializationException) {
        Log.e(ROW_WRITES_TAG, "Undecodable owed row write ${file.name}", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.e(ROW_WRITES_TAG, "Invalid owed row write ${file.name}", e)
        null
    }

    // Drops [file] unless a newer write replaced [write] in it meanwhile.
    private fun dropIfUnchanged(file: File, write: RowWrite?) {
        synchronized(LOCK) {
            if (read(file) == write) AtomicFile(file).delete()
        }
    }

    private fun fileFor(write: RowWrite): File = fileFor(Uri.parse(write.uri), write.kind)

    private fun fileFor(uri: Uri, kind: RowWriteKind): File {
        val key = listOf(uri.authority.orEmpty(), uri.lastPathSegment.orEmpty(), kind.name)
            .joinToString("_") { part -> part.replace(Regex("[^A-Za-z0-9]"), "-") }
        return File(directory, key + OWED_WRITE_SUFFIX)
    }

    companion object {
        // Every store over the app's files reads and writes the same files.
        private val LOCK = Any()

        /**
         * Purpose: the app's store, in its private files.
         *
         * @param context any context.
         * @returns the store.
         * @throws nothing.
         */
        fun forApp(context: Context): OwedRowWrites = OwedRowWrites(File(context.filesDir, OWED_WRITES_DIRECTORY))
    }
}
