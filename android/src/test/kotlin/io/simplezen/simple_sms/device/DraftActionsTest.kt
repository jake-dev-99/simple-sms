package io.simplezen.simple_sms.device

import android.app.role.RoleManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowRoleManager

/**
 * Purpose: pin the native SMS draft write contract (UNFY-213, ADR-0016) — a
 * save leaves exactly one draft holding the saved body and answers its `_id`,
 * a discard leaves none, every write is re-qualified by thread and draft type,
 * and every unverifiable state surfaces as its own error code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DraftActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sms = FakeSmsProvider()
    private val threads = FakeThreadsProvider()
    private val drafts = DraftActions(context)

    /** Purpose: isolate provider state per test, list thread 5, and start as the default SMS app. */
    @Before
    fun setUp() {
        ShadowContentResolver.registerProviderInternal("sms", sms)
        ShadowContentResolver.registerProviderInternal("mms-sms", threads)
        threads.listed += 5L
        roleManager().apply {
            addAvailableRole(RoleManager.ROLE_SMS)
            addHeldRole(RoleManager.ROLE_SMS)
        }
    }

    // ── saveSmsDraft ──────────────────────────────────────────────────

    /** Purpose: a thread without a draft gets one, typed draft, read and seen, under that thread. */
    @Test
    fun save_withoutDraft_insertsOne() {
        sms.rows += SmsRow(id = 1, threadId = 5, type = 1, body = "inbound")

        val result = save("5", "see you")

        val draft = sms.rows.single { it.type == 3 }
        assertEquals(listOf<Any?>(draft.id.toString()), result.successes)
        assertEquals(SmsRow(id = draft.id, threadId = 5, type = 3, body = "see you", date = draft.date), draft)
        assertEquals(1, sms.inserted.single().getAsInteger("read"))
        assertEquals(1, sms.inserted.single().getAsInteger("seen"))
        assertTrue(draft.date!! > 0)
    }

    /**
     * Purpose: an existing draft is updated in place, so its `_id` stays stable
     * across saves, and it is marked read + seen even if another app left it unread.
     */
    @Test
    fun save_withDraft_updatesItInPlace() {
        sms.rows += SmsRow(id = 4, threadId = 5, type = 3, body = "old", date = 10)

        val result = save("5", "new")

        assertEquals(listOf<Any?>("4"), result.successes)
        assertEquals(listOf(SmsRow(id = 4, threadId = 5, type = 3, body = "new", date = sms.rows.single().date)), sms.rows)
        assertTrue(sms.rows.single().date!! > 10)
        assertEquals(1, sms.updatedValues.single().getAsInteger("read"))
        assertEquals(1, sms.updatedValues.single().getAsInteger("seen"))
        assertTrue(sms.inserted.isEmpty())
    }

    /** Purpose: a draft that vanished between the read and the update is replaced by a read + seen insert. */
    @Test
    fun save_draftVanishedBeforeUpdate_insertsInstead() {
        sms.rows += SmsRow(id = 4, threadId = 5, type = 3, body = "old", date = 10)
        sms.beforeUpdate = { sms.rows.clear() }

        val result = save("5", "new")

        val draft = sms.rows.single()
        assertEquals(listOf<Any?>(draft.id.toString()), result.successes)
        assertEquals("new", draft.body)
        assertEquals(1, sms.inserted.single().getAsInteger("read"))
        assertEquals(1, sms.inserted.single().getAsInteger("seen"))
        // The pre-read draft is still deleted defensively, sparing the new one.
        assertEquals(
            listOf(ProviderWrite("content://sms/conversations/5", "type = ? AND _id != ?", listOf("3", "${draft.id}"))),
            sms.deletes,
        )
    }

    /** Purpose: an insert that reuses the vanished draft's `_id` (SQLite rowid reuse) is kept, never deleted. */
    @Test
    fun save_fallbackInsertReusingTheVanishedId_isKept() {
        sms.rows += SmsRow(id = 4, threadId = 5, type = 3, body = "old", date = 10)
        sms.beforeUpdate = { sms.rows.clear() }
        sms.nextId = 4

        val result = save("5", "new")

        assertEquals(listOf<Any?>("4"), result.successes)
        assertEquals("new", sms.rows.single().body)
        assertTrue(sms.deletes.isEmpty())
    }

    /** Purpose: extra drafts another app left are deleted; the newest is kept; other threads are untouched. */
    @Test
    fun save_withSeveralDrafts_keepsTheNewestAndDeletesTheRest() {
        sms.rows += SmsRow(id = 2, threadId = 5, type = 3, body = "older", date = 10)
        sms.rows += SmsRow(id = 3, threadId = 5, type = 3, body = "newest", date = 20)
        sms.rows += SmsRow(id = 6, threadId = 9, type = 3, body = "other thread", date = 30)

        val result = save("5", "kept")

        assertEquals(listOf<Any?>("3"), result.successes)
        assertEquals(listOf(3L, 6L), sms.rows.map { it.id })
        assertEquals("kept", sms.rows.first().body)
    }

    /** Purpose: every write is re-qualified by thread and draft type, never by a bare `_id` (UNFY-307). */
    @Test
    fun save_writesAreQualifiedByThreadAndDraftType() {
        sms.rows += SmsRow(id = 2, threadId = 5, type = 3, body = "a", date = 10)
        sms.rows += SmsRow(id = 3, threadId = 5, type = 3, body = "b", date = 20)

        save("5", "c")

        assertEquals(
            listOf(ProviderWrite("content://sms", "_id = ? AND thread_id = ? AND type = ?", listOf("3", "5", "3"))),
            sms.updates,
        )
        // One delete through the conversation URI, which supplies the thread
        // and makes the provider recompute the thread's snippet and date.
        assertEquals(
            listOf(ProviderWrite("content://sms/conversations/5", "type = ? AND _id != ?", listOf("3", "3"))),
            sms.deletes,
        )
    }

    /** Purpose: a draft must belong to a listed thread; nothing is written otherwise. */
    @Test
    fun save_unlistedThread_isThreadNotFound() {
        val result = save("8", "hi")

        assertEquals(listOf("THREAD_NOT_FOUND"), result.errors)
        assertTrue(sms.writeCount() == 0)
    }

    /** Purpose: without the SMS role Android ignores the write, so nothing is read or written. */
    @Test
    fun save_withoutSmsRole_failsBeforeTouchingTheProvider() {
        roleManager().removeHeldRole(RoleManager.ROLE_SMS)

        val result = save("5", "hi")

        assertEquals(listOf("NOT_DEFAULT_SMS_APP"), result.errors)
        assertTrue(threads.queries == 0 && sms.queries == 0 && sms.writeCount() == 0)
    }

    /** Purpose: an unreadable thread is never written blind. */
    @Test
    fun save_unreadableDrafts_failsBeforeWriting() {
        sms.nullCursor = true

        val result = save("5", "hi")

        assertEquals(listOf("DRAFT_WRITE_FAILED"), result.errors)
        assertEquals(0, sms.writeCount())
    }

    /** Purpose: an insert the provider drops (no row URI) is a failed write, not a draft. */
    @Test
    fun save_insertWithoutRow_isDraftWriteFailed() {
        sms.insertReturnsNull = true

        assertEquals(listOf("DRAFT_WRITE_FAILED"), save("5", "hi").errors)
    }

    /** Purpose: a provider exception surfaces as a failed write. */
    @Test
    fun save_providerThrows_isDraftWriteFailed() {
        sms.writeFailure = IllegalStateException("disk full")

        assertEquals(listOf("DRAFT_WRITE_FAILED"), save("5", "hi").errors)
    }

    /** Purpose: an extra draft the provider kept leaves the save unverified. */
    @Test
    fun save_extraDraftSurvives_isIncomplete() {
        sms.rows += SmsRow(id = 2, threadId = 5, type = 3, body = "a", date = 10)
        sms.rows += SmsRow(id = 3, threadId = 5, type = 3, body = "b", date = 20)
        sms.ignoreDeletes = true

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), save("5", "c").errors)
    }

    /** Purpose: a draft another app saved meanwhile leaves the save unverified. */
    @Test
    fun save_concurrentDraft_isIncomplete() {
        sms.afterWrite = { sms.rows += SmsRow(id = 50, threadId = 5, type = 3, body = "theirs", date = 99) }

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), save("5", "mine").errors)
    }

    /** Purpose: the draft body is message content: no error text carries it, read back or saved. */
    @Test
    fun save_errorsNeverCarryTheBody() {
        sms.rows += SmsRow(id = 2, threadId = 5, type = 3, body = "their secret", date = 10)
        sms.rows += SmsRow(id = 3, threadId = 5, type = 3, body = "older secret", date = 5)
        sms.ignoreDeletes = true

        val result = save("5", "my secret")

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), result.errors)
        result.messages.forEach { assertTrue(it, !it.contains("secret")) }
        val invalid = invoke(MethodCall("saveSmsDraft", mapOf("threadId" to "x", "body" to "my secret")))
        invalid.messages.forEach { assertTrue(it, !it.contains("secret")) }
    }

    /** Purpose: a provider exception that echoes the submitted values never carries them to Dart. */
    @Test
    fun save_providerExceptionEchoingTheBody_isRedacted() {
        sms.writeFailure = IllegalStateException("insert failed: body=my secret", RuntimeException("body=my secret"))

        val result = save("5", "my secret")

        assertEquals(listOf("DRAFT_WRITE_FAILED"), result.errors)
        val text = result.messages.single()
        assertTrue(text, !text.contains("secret"))
        assertTrue(text, text.contains("java.lang.IllegalStateException") && text.contains("Caused by: java.lang.RuntimeException"))
    }

    /** Purpose: a thread deleted while the save ran leaves no orphaned draft and reports the thread gone. */
    @Test
    fun save_threadDeletedDuringSave_removesTheDraftAndIsThreadNotFound() {
        sms.afterWrite = { threads.listed.remove(5L) }

        val result = save("5", "hi")

        assertEquals(listOf("THREAD_NOT_FOUND"), result.errors)
        assertTrue(sms.rows.isEmpty())
        assertEquals(
            ProviderWrite("content://sms", "_id = ? AND thread_id = ? AND type = ?", listOf("100", "5", "3")),
            sms.deletes.single(),
        )
    }

    /** Purpose: a write that can't be read back is unverified. */
    @Test
    fun save_unreadableReadBack_isVerifyFailed() {
        sms.queryFailureAfterWrite = IllegalStateException("provider died")

        assertEquals(listOf("VERIFY_FAILED"), save("5", "hi").errors)
    }

    /** Purpose: reject ids that match no provider row, and missing or blank bodies, before any I/O. */
    @Test
    fun save_rejectsInvalidArguments() {
        for (arguments in listOf(
            null,
            mapOf("threadId" to "5"),
            mapOf("threadId" to "5", "body" to "   "),
            mapOf("threadId" to "5", "body" to 7),
            mapOf("threadId" to "0", "body" to "hi"),
            mapOf("threadId" to "abc", "body" to "hi"),
            mapOf("threadId" to 5, "body" to "hi"),
        )) {
            val result = invoke(MethodCall("saveSmsDraft", arguments))

            assertEquals("$arguments", listOf("INVALID_ARGUMENT"), result.errors)
        }
        assertTrue(threads.queries == 0 && sms.queries == 0 && sms.writeCount() == 0)
    }

    // ── discardSmsDraft ───────────────────────────────────────────────

    /**
     * Purpose: delete every draft of the thread only, through its conversation
     * URI so the provider recomputes the thread's snippet and date.
     */
    @Test
    fun discard_deletesTheThreadsDrafts() {
        sms.rows += SmsRow(id = 1, threadId = 5, type = 1, body = "inbound")
        sms.rows += SmsRow(id = 2, threadId = 5, type = 3, body = "a", date = 10)
        sms.rows += SmsRow(id = 3, threadId = 9, type = 3, body = "other", date = 10)

        val result = discard("5")

        assertEquals(listOf<Any?>("discarded"), result.successes)
        assertEquals(listOf(1L, 3L), sms.rows.map { it.id })
        assertEquals(
            listOf(ProviderWrite("content://sms/conversations/5", "type = ?", listOf("3"))),
            sms.deletes,
        )
    }

    /** Purpose: a thread without a draft — including one no longer listed — is already absent. */
    @Test
    fun discard_withoutDraft_isAlreadyAbsent() {
        assertEquals(listOf<Any?>("alreadyAbsent"), discard("5").successes)
        assertEquals(listOf<Any?>("alreadyAbsent"), discard("8").successes)
    }

    /** Purpose: a draft that survives the delete fails the discard. */
    @Test
    fun discard_draftSurvives_isIncomplete() {
        sms.rows += SmsRow(id = 2, threadId = 5, type = 3, body = "a", date = 10)
        sms.ignoreDeletes = true

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), discard("5").errors)
    }

    /** Purpose: without the SMS role nothing is deleted. */
    @Test
    fun discard_withoutSmsRole_failsBeforeDeleting() {
        roleManager().removeHeldRole(RoleManager.ROLE_SMS)

        assertEquals(listOf("NOT_DEFAULT_SMS_APP"), discard("5").errors)
        assertEquals(0, sms.writeCount())
    }

    /** Purpose: a provider exception and an unreadable read-back are told apart. */
    @Test
    fun discard_failures() {
        sms.writeFailure = IllegalStateException("disk full")
        assertEquals(listOf("DRAFT_WRITE_FAILED"), discard("5").errors)

        sms.writeFailure = null
        sms.queryFailureAfterWrite = IllegalStateException("provider died")
        assertEquals(listOf("VERIFY_FAILED"), discard("5").errors)
    }

    /** Purpose: reject ids that match no provider thread before any I/O. */
    @Test
    fun discard_rejectsInvalidThreadIds() {
        for (arguments in listOf(null, "", "0", "-4", "abc", 5)) {
            assertEquals("$arguments", listOf("INVALID_ARGUMENT"), invoke(MethodCall("discardSmsDraft", arguments)).errors)
        }
        assertTrue(sms.queries == 0 && sms.writeCount() == 0)
    }

    private fun save(threadId: String, body: String) =
        invoke(MethodCall("saveSmsDraft", mapOf("threadId" to threadId, "body" to body)))

    private fun discard(threadId: String) = invoke(MethodCall("discardSmsDraft", threadId))

    private fun roleManager(): ShadowRoleManager =
        Shadow.extract(context.getSystemService(RoleManager::class.java))

    private fun invoke(call: MethodCall): DraftCallResult =
        DraftCallResult().also { drafts.onMethodCall(call, it) }
}

/** Purpose: one `content://sms` row, holding only the columns draft writes touch. */
private data class SmsRow(val id: Long, val threadId: Long, val type: Int, val body: String?, val date: Long? = 1L)

/** Purpose: snapshot one write's target and filter. */
private data class ProviderWrite(val uri: String, val selection: String?, val arguments: List<String>?)

/**
 * Purpose: an in-memory `content://sms` that evaluates exactly the filters the
 * draft writes use — any other filter fails the test, pinning the contract.
 */
private class FakeSmsProvider : ContentProvider() {
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
        assertEquals(listOf("_id", "body", "date"), projection?.toList())
        return MatrixCursor(arrayOf("_id", "body", "date")).apply {
            matching(uri, selection, selectionArgs).forEach { addRow(arrayOf(it.id, it.body, it.date)) }
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
                rows[rows.indexOf(row)] = row.copy(body = values!!.getAsString("body"), date = values.getAsLong("date"))
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
            else -> throw AssertionError("Unexpected conversation filter: $selection")
        }
    }
}

/** Purpose: the conversation list's thread rows (`content://mms-sms/conversations?simple=true`). */
private class FakeThreadsProvider : ContentProvider() {
    val listed = mutableSetOf<Long>()
    var queries = 0

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        queries++
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

/** Purpose: capture the handler's single reply, keeping success values apart from error codes. */
private class DraftCallResult : MethodChannel.Result {
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
