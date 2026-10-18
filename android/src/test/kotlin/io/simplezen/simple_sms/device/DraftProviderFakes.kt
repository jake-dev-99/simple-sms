package io.simplezen.simple_sms.device

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream

/** Purpose: one `content://sms` row, holding only the columns draft writes touch. */
internal data class SmsRow(
    val id: Long,
    val threadId: Long,
    val type: Int,
    val body: String?,
    val date: Long? = 1L,
    val subId: Long? = 1L,
)

/** The subscription the fake provider stamps on an insert that names none. */
internal const val DEFAULT_SUB_ID = 1L

/** Purpose: snapshot one write's target and filter. */
internal data class ProviderWrite(val uri: String, val selection: String?, val arguments: List<String>?)

/**
 * Purpose: an in-memory `content://sms` that evaluates exactly the filters the
 * draft writes use — any other filter fails the test, pinning the contract.
 */
internal class FakeSmsProvider : ContentProvider() {
    val rows = mutableListOf<SmsRow>()
    val inserted = mutableListOf<ContentValues>()
    val updates = mutableListOf<ProviderWrite>()
    val updatedValues = mutableListOf<ContentValues>()
    val deletes = mutableListOf<ProviderWrite>()
    var queries = 0
    var nullCursor = false
    var insertReturnsNull = false
    var ignoreDeletes = false
    var beforeUpdate: () -> Unit = {}
    var nextId = 100L
    var writeFailure: RuntimeException? = null
    var queryFailureAfterWrite: RuntimeException? = null
    var afterWrite: () -> Unit = {}
    private var wrote = false

    fun writeCount() = inserted.size + updates.size + deletes.size

    /** Purpose: permit registration without allocating external resources. */
    override fun onCreate() = true

    /** Purpose: answer a thread's draft rows, a null cursor, or a failure. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (wrote) queryFailureAfterWrite?.let { throw it }
        queries++
        if (nullCursor) return null
        assertEquals("content://sms", uri.toString())
        assertEquals(listOf("_id", "body", "date", "sub_id"), projection?.toList())
        return MatrixCursor(arrayOf("_id", "body", "date", "sub_id")).apply {
            matching(uri, selection, selectionArgs).forEach {
                addRow(arrayOf<Any?>(it.id, it.body, it.date, it.subId))
            }
        }
    }

    /** Purpose: insert a row and answer its URI, or drop it. */
    override fun insert(uri: Uri, values: ContentValues?): Uri? = write {
        assertEquals("content://sms", uri.toString())
        inserted += ContentValues(values)
        if (insertReturnsNull) return@write null
        val id = nextId++
        rows += SmsRow(
            id = id,
            threadId = values!!.getAsLong("thread_id"),
            type = values.getAsInteger("type"),
            body = values.getAsString("body"),
            date = values.getAsLong("date"),
            // AOSP stamps the default SMS subscription when none is given.
            subId = values.getAsLong("sub_id") ?: DEFAULT_SUB_ID,
        )
        Uri.parse("content://sms/$id")
    }

    /** Purpose: apply body + date to the matching row. */
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        write {
            beforeUpdate()
            updates += ProviderWrite(uri.toString(), selection, selectionArgs?.toList())
            updatedValues += ContentValues(values)
            val targets = matching(uri, selection, selectionArgs)
            targets.forEach { row ->
                rows[rows.indexOf(row)] = row.copy(
                    body = values!!.getAsString("body"),
                    date = values.getAsLong("date"),
                    subId = values.getAsLong("sub_id") ?: row.subId,
                )
            }
            targets.size
        }

    /** Purpose: remove the matching rows. */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = write {
        deletes += ProviderWrite(uri.toString(), selection, selectionArgs?.toList())
        if (ignoreDeletes) return@write 0
        val targets = matching(uri, selection, selectionArgs)
        rows.removeAll(targets)
        targets.size
    }

    /** Purpose: fail if a draft write unexpectedly inspects a MIME type. */
    override fun getType(uri: Uri): String? =
        throw UnsupportedOperationException("Draft writes must not inspect MIME types")

    private fun <T> write(block: () -> T): T {
        writeFailure?.let { throw it }
        wrote = true
        return block().also { afterWrite() }
    }

    /** The rows at [uri] (`content://sms`, or one thread's `content://sms/conversations/<id>`) matching the filter. */
    private fun matching(uri: Uri, selection: String?, args: Array<out String>?): List<SmsRow> {
        val a = args!!.map { it.toLong() }
        val segments = uri.pathSegments
        if (segments.isEmpty()) {
            return when (selection) {
                "thread_id = ? AND type = ?" -> rows.filter { it.threadId == a[0] && it.type.toLong() == a[1] }
                "_id = ? AND thread_id = ? AND type = ?" ->
                    rows.filter { it.id == a[0] && it.threadId == a[1] && it.type.toLong() == a[2] }
                else -> throw AssertionError("Unexpected SMS filter: $selection")
            }
        }
        assertEquals("conversations", segments[0])
        val inThread = rows.filter { it.threadId == segments[1].toLong() }
        return when (selection) {
            "type = ?" -> inThread.filter { it.type.toLong() == a[0] }
            "type = ? AND _id != ?" -> inThread.filter { it.type.toLong() == a[0] && it.id != a[1] }
            "_id = ?" -> inThread.filter { it.id == a[0] }
            else -> throw AssertionError("Unexpected conversation filter: $selection")
        }
    }
}

/**
 * Purpose: the conversation list's thread rows (`content://mms-sms/conversations?simple=true`)
 * and the thread allocation the PDU persister asks for (`content://mms-sms/threadID?recipient=`).
 */
internal class FakeThreadsProvider : ContentProvider() {
    val listed = mutableSetOf<Long>()
    var queries = 0

    /** The thread `threadID` allocates for any recipients. */
    var threadForRecipients = 5L

    /** The recipient sets `threadID` was asked about. */
    val recipientLookups = mutableListOf<Set<String>>()

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        queries++
        if (uri.path == "/threadID") {
            recipientLookups += uri.getQueryParameters("recipient").toSet()
            return MatrixCursor(arrayOf("_id")).apply { addRow(arrayOf(threadForRecipients)) }
        }
        assertEquals("content://mms-sms/conversations?simple=true", uri.toString())
        assertEquals("_id = ?", selection)
        val id = selectionArgs!!.single().toLong()
        return MatrixCursor(arrayOf("_id")).apply { if (id in listed) addRow(arrayOf(id)) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Draft writes must not create threads")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Draft writes must not update threads")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Draft writes must not delete threads")

    override fun getType(uri: Uri): String? =
        throw UnsupportedOperationException("Draft writes must not inspect MIME types")
}

/** Where `PduPersister.release` deletes its temporary DRM objects. */
private val PERSISTER_TEMPORARY_URI = "content://mms/${Long.MAX_VALUE}/part"

/** Purpose: one `content://mms` row, holding only the columns draft writes touch. */
internal data class MmsRow(
    val id: Long,
    val threadId: Long,
    val box: Int,
    val date: Long = 1L,
    val read: Int = 0,
    val seen: Int = 0,
)

/** Purpose: one `content://mms/part` row; a non-text part's bytes are read with [FakeMmsProvider.bytesOf]. */
internal data class MmsPartRow(
    val id: Long,
    val mid: Long,
    val contentType: String,
    val text: String? = null,
    val location: String? = null,
)

/**
 * Purpose: an in-memory `content://mms` that serves exactly the calls the PDU
 * persister and the draft writes make — any other call fails the test,
 * pinning the contract. Inserting into `drafts` files the row in box 3 unread
 * and unseen, as AOSP `MmsProvider` does.
 */
internal class FakeMmsProvider(private val context: Context) : ContentProvider() {
    val rows = mutableListOf<MmsRow>()
    val parts = mutableListOf<MmsPartRow>()

    /** `(mms id, address)` of every `addr` row inserted. */
    val addresses = mutableListOf<Pair<Long, String>>()
    val insertedDrafts = mutableListOf<ContentValues>()
    val updates = mutableListOf<ProviderWrite>()

    /** The values of every update to a message row (not to its parts). */
    val rowUpdates = mutableListOf<ContentValues>()
    val deletes = mutableListOf<ProviderWrite>()
    var queries = 0
    var nullCursor = false
    var ignoreDeletes = false
    var nextId = 200L
    var writeFailure: RuntimeException? = null
    var queryFailureAfterWrite: RuntimeException? = null
    var afterWrite: () -> Unit = {}
    private val partBytes = mutableMapOf<Long, ByteArrayOutputStream>()
    private var wrote = false

    fun writeCount() = insertedDrafts.size + parts.size + addresses.size + updates.size + deletes.size

    /** The bytes written to non-text part [partId]. */
    fun bytesOf(partId: Long): ByteArray = partBytes.getValue(partId).toByteArray()

    /** Purpose: permit registration without allocating external resources. */
    override fun onCreate() = true

    /** Purpose: answer a thread's draft rows, one row's thread, or a message's parts. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (wrote) queryFailureAfterWrite?.let { throw it }
        queries++
        if (nullCursor) return null
        val args = selectionArgs!!
        val columns = projection!!
        return MatrixCursor(columns).apply {
            val answer: List<Array<Any?>> = when {
                uri.pathSegments.isEmpty() && selection == "thread_id = ? AND msg_box = ?" -> {
                    assertEquals(listOf("_id", "date"), columns.toList())
                    rows.filter { it.threadId == args[0].toLong() && it.box == args[1].toInt() }
                        .map { arrayOf(it.id, it.date) }
                }
                uri.pathSegments.isEmpty() && selection == "_id = ?" -> {
                    assertEquals(listOf("thread_id"), columns.toList())
                    rows.filter { it.id == args.single().toLong() }.map { arrayOf(it.threadId) }
                }
                uri.pathSegments == listOf("part") && selection == "mid = ?" -> {
                    assertEquals(listOf("ct", "text"), columns.toList())
                    assertEquals("_id ASC", sortOrder)
                    parts.filter { it.mid == args.single().toLong() }.sortedBy { it.id }
                        .map { arrayOf(it.contentType, it.text) }
                }
                else -> throw AssertionError("Unexpected MMS query: $uri where $selection")
            }
            answer.forEach { addRow(it) }
        }
    }

    /** Purpose: file a draft row, a part under its dummy id, or an address. */
    override fun insert(uri: Uri, values: ContentValues?): Uri? = write {
        val segments = uri.pathSegments
        val id = nextId++
        when {
            segments == listOf("drafts") -> {
                insertedDrafts += ContentValues(values)
                rows += MmsRow(id, threadId = values!!.getAsLong("thread_id"), box = 3, date = values.getAsLong("date"))
                Uri.parse("content://mms/$id")
            }
            segments.size == 2 && segments[1] == "part" -> {
                parts += MmsPartRow(
                    id,
                    mid = segments[0].toLong(),
                    contentType = values!!.getAsString("ct"),
                    location = values.getAsString("cl"),
                )
                // The persister writes a non-text part's data to its URI.
                val partUri = Uri.parse("content://mms/part/$id")
                ByteArrayOutputStream().also {
                    partBytes[id] = it
                    shadowOf(context.contentResolver).registerOutputStream(partUri, it)
                }
                partUri
            }
            segments.size == 2 && segments[1] == "addr" -> {
                addresses += segments[0].toLong() to values!!.getAsString("address")
                Uri.parse("content://mms/${segments[0]}/addr/$id")
            }
            else -> throw AssertionError("Unexpected MMS insert: $uri")
        }
    }

    /** Purpose: set a text part's text, move parts from their dummy id to the row, or mark a draft read + seen. */
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        write {
            updates += ProviderWrite(uri.toString(), selection, selectionArgs?.toList())
            val segments = uri.pathSegments
            when {
                segments.size == 2 && segments[0] == "part" -> {
                    val index = parts.indexOfFirst { it.id == segments[1].toLong() }
                    if (index < 0) return@write 0
                    parts[index] = parts[index].copy(text = values!!.getAsString("text"))
                    1
                }
                segments.size == 2 && segments[1] == "part" -> {
                    val dummy = segments[0].toLong()
                    val moved = parts.count { it.mid == dummy }
                    parts.replaceAll { if (it.mid == dummy) it.copy(mid = values!!.getAsLong("mid")) else it }
                    moved
                }
                segments.size == 2 && segments[0] == "drafts" -> {
                    rowUpdates += ContentValues(values)
                    val index = rows.indexOfFirst { it.id == segments[1].toLong() }
                    if (index < 0) return@write 0
                    rows[index] = rows[index].copy(
                        read = values!!.getAsInteger("read"),
                        seen = values.getAsInteger("seen"),
                    )
                    1
                }
                else -> throw AssertionError("Unexpected MMS update: $uri")
            }
        }

    /** Purpose: remove the matching draft rows with their parts. */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        // The persister clears its temporary DRM objects when a new context takes it over.
        if (uri.toString() == PERSISTER_TEMPORARY_URI) return 0
        return deleteDraft(uri, selection, selectionArgs)
    }

    private fun deleteDraft(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = write {
        deletes += ProviderWrite(uri.toString(), selection, selectionArgs?.toList())
        if (ignoreDeletes) return@write 0
        assertEquals("content://mms", uri.toString())
        assertEquals("_id = ? AND thread_id = ? AND msg_box = ?", selection)
        val args = selectionArgs!!.map { it.toLong() }
        val targets = rows.filter { it.id == args[0] && it.threadId == args[1] && it.box.toLong() == args[2] }
        rows.removeAll(targets)
        parts.removeAll { part -> targets.any { it.id == part.mid } }
        targets.size
    }

    /** Purpose: fail if a draft write unexpectedly inspects a MIME type. */
    override fun getType(uri: Uri): String? =
        throw UnsupportedOperationException("Draft writes must not inspect MIME types")

    private fun <T> write(block: () -> T): T {
        writeFailure?.let { throw it }
        wrote = true
        return block().also { afterWrite() }
    }
}

/** Purpose: capture the handler's single reply, keeping success values apart from error codes. */
internal class DraftCallResult : MethodChannel.Result {
    val successes = mutableListOf<Any?>()
    val errors = mutableListOf<String>()
    val messages = mutableListOf<String>()

    override fun success(result: Any?) { successes.add(result) }

    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
        errors.add(errorCode)
        messages.add("$errorMessage $errorDetails")
    }

    override fun notImplemented() { throw AssertionError("Draft command was not implemented") }
}
