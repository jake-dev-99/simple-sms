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
 */
@Serializable
internal data class RowWrite(
    val uri: String,
    val kind: RowWriteKind,
    val values: Map<String, Long>,
    val selection: String,
    val selectionArgs: List<String>,
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
 * read the row back and tell why (UNFY-335). The write is owed when its row
 * can be read later ([OwedRowWrites]).
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
    if (outcome == RowWriteOutcome.IGNORED || outcome == RowWriteOutcome.UNREADABLE) oweRowWrite(context, write)
    return outcome
}

// Owing is the fallback for a write that already failed. A write whose row
// can't be read to tie it to ([tiedToItsRow]), or that the store can't keep,
// is logged and lost, as before UNFY-335.
private fun oweRowWrite(context: Context, write: RowWrite) {
    val tied = try {
        tiedToItsRow(context, write)
    } catch (e: Exception) {
        Log.e(ROW_WRITES_TAG, "Could not read ${write.uri} to keep its unwritten status write", e)
        return
    }
    if (tied == null) {
        Log.w(ROW_WRITES_TAG, "${write.uri} is gone or unreadable; its unwritten status write is not kept")
        return
    }
    try {
        OwedRowWrites.forApp(context).owe(tied)
    } catch (e: IOException) {
        Log.e(ROW_WRITES_TAG, "Could not keep the unwritten status write for ${write.uri} to retry", e)
    }
}

/**
 * [write], also guarded by its row's `date`, so it only ever lands on that
 * row: a row deleted while the write is owed may have its id reused, by this
 * app's next send or by whichever app holds the default-SMS role now. Null
 * when the row is gone, or the provider returned no cursor.
 */
@VisibleForTesting
internal fun tiedToItsRow(context: Context, write: RowWrite): RowWrite? {
    val date = ContentQuery.queryOrNull(context, write.uri, arrayOf(DATE_COLUMN))
        ?.singleOrNull()?.get(DATE_COLUMN) as? Long ?: return null
    return write.copy(
        selection = "(${write.selection}) AND $DATE_COLUMN = ?",
        selectionArgs = write.selectionArgs + date.toString(),
    )
}

// SMS and MMS rows both have it: when the message was sent or received (SMS
// in milliseconds, MMS in seconds).
private const val DATE_COLUMN = "date"

/**
 * Purpose: keeps the send-status writes that did not land on their rows, so
 * they are retried rather than lost (UNFY-335): the provider ignored them
 * (the app lost the default-SMS role or its WRITE_SMS app-op) or failed.
 * Each is tied to its row ([tiedToItsRow]).
 *
 * One small file per row and [RowWriteKind], written atomically; a later
 * write of the same kind to the same row replaces the earlier one. Each is
 * retried ([retry]) at every engine start, before the stale-outbox sweep, and
 * before each new send. One is dropped once it lands, once its row no longer matches its
 * guard, or after [DELIVERY_REPORT_WAIT_MS]. Every access is serialized
 * process-wide.
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
     * Purpose: keeps [write] to retry, in place of any write owed to the same
     * row's same columns.
     *
     * @throws IOException when it cannot be written.
     */
    fun owe(write: RowWrite) {
        synchronized(LOCK) {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Could not create the owed row-write store at $directory")
            }
            val file = AtomicFile(fileFor(write))
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
    }

    /**
     * Purpose: the rows that still owe a write.
     *
     * @returns their URIs.
     * @throws nothing; an unreadable write is left for [retry] to drop.
     */
    @VisibleForTesting
    fun rows(): Set<Uri> = synchronized(LOCK) { entries().mapNotNull { it.second?.uri?.let(Uri::parse) }.toSet() }

    /**
     * Purpose: retries every owed write, dropping each that lands or no
     * longer applies, and each owed for [DELIVERY_REPORT_WAIT_MS] or more.
     * A write still ignored, or still failing, is kept.
     *
     * @param context any context.
     * @returns how many landed.
     * @throws nothing; each failure is logged.
     */
    fun retry(context: Context): Int {
        val cutoff = nowMillis() - DELIVERY_REPORT_WAIT_MS
        val owed = synchronized(LOCK) { entries() }
        var landed = 0
        for ((file, write) in owed) {
            if (write == null || file.lastModified() < cutoff) {
                if (write != null) Log.w(ROW_WRITES_TAG, "Gave up on the status write owed to ${write.uri}")
                dropIfUnchanged(file, write)
                continue
            }
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
                RowWriteOutcome.NOT_APPLICABLE -> dropIfUnchanged(file, write)
                RowWriteOutcome.IGNORED -> Log.w(ROW_WRITES_TAG, "The status write owed to ${write.uri} is still ignored")
                RowWriteOutcome.UNREADABLE -> Log.w(ROW_WRITES_TAG, "The row owing a status write, ${write.uri}, is unreadable")
            }
        }
        return landed
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

    private fun fileFor(write: RowWrite): File {
        val uri = Uri.parse(write.uri)
        val key = listOf(uri.authority.orEmpty(), uri.lastPathSegment.orEmpty(), write.kind.name)
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
