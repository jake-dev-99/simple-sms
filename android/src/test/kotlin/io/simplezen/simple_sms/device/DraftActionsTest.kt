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
import org.junit.Assert.assertFalse
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
 * and every unverifiable state surfaces as its own error code. A thread holds
 * one draft across SMS and MMS (UNFY-281): a save removes the thread's MMS
 * drafts and a discard removes them with the SMS ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DraftActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sms = FakeSmsProvider()
    private val mms = FakeMmsProvider(context)
    private val threads = FakeThreadsProvider()
    private val drafts = DraftActions(context)

    /** Purpose: isolate provider state per test, list thread 5, and start as the default SMS app. */
    @Before
    fun setUp() {
        ShadowContentResolver.registerProviderInternal("sms", sms)
        ShadowContentResolver.registerProviderInternal("mms", mms)
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
        result.messages.forEach { assertFalse(it, it.contains("secret")) }
        val invalid = invoke(MethodCall("saveSmsDraft", mapOf("threadId" to "x", "body" to "my secret")))
        invalid.messages.forEach { assertFalse(it, it.contains("secret")) }
    }

    /** Purpose: a provider exception that echoes the submitted values never carries them to Dart. */
    @Test
    fun save_providerExceptionEchoingTheBody_isRedacted() {
        sms.writeFailure = IllegalStateException("insert failed: body=my secret", RuntimeException("body=my secret"))

        val result = save("5", "my secret")

        assertEquals(listOf("DRAFT_WRITE_FAILED"), result.errors)
        val text = result.messages.single()
        assertFalse(text, text.contains("secret"))
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

    /** Purpose: an orphaned draft the provider keeps is reported, never claimed removed. */
    @Test
    fun save_threadDeletedDuringSave_orphanSurvivesCleanup_isDraftWriteFailed() {
        sms.afterWrite = {
            threads.listed.remove(5L)
            sms.ignoreDeletes = true
        }

        assertEquals(listOf("DRAFT_WRITE_FAILED"), save("5", "hi").errors)
        assertEquals(1, sms.rows.size)
    }

    /** Purpose: a write that can't be read back is unverified. */
    @Test
    fun save_unreadableReadBack_isVerifyFailed() {
        sms.queryFailureAfterWrite = IllegalStateException("provider died")

        assertEquals(listOf("VERIFY_FAILED"), save("5", "hi").errors)
    }

    /** Purpose: a named subscription is stored on insert and verified on read-back. */
    @Test
    fun save_withSubscription_insertsItOnTheDraft() {
        val result = invoke(MethodCall("saveSmsDraft", mapOf("threadId" to "5", "body" to "hi", "subscriptionId" to 2)))

        assertEquals(1, result.successes.size)
        assertEquals(2L, sms.rows.single().subId)
        assertEquals(2, sms.inserted.single().getAsInteger("sub_id"))
    }

    /** Purpose: an update moves the kept draft to a named subscription and keeps its own when none is named. */
    @Test
    fun save_update_setsANamedSubscriptionOnly() {
        sms.rows += SmsRow(id = 4, threadId = 5, type = 3, body = "old", date = 10, subId = 1)

        invoke(MethodCall("saveSmsDraft", mapOf("threadId" to "5", "body" to "a", "subscriptionId" to 2)))
        assertEquals(2L, sms.rows.single().subId)

        save("5", "b")
        assertEquals(2L, sms.rows.single().subId)
        assertFalse(sms.updatedValues.last().containsKey("sub_id"))
    }

    /** Purpose: a provider that stores another subscription than the one named leaves the save unverified. */
    @Test
    fun save_subscriptionNotStored_isIncomplete() {
        sms.afterWrite = { sms.rows.replaceAll { it.copy(subId = DEFAULT_SUB_ID) } }

        val result = invoke(MethodCall("saveSmsDraft", mapOf("threadId" to "5", "body" to "hi", "subscriptionId" to 2)))

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), result.errors)
    }

    /**
     * Purpose: removing a thread's MMS drafts never reads their parts, so a
     * draft whose parts can't be read (no content type, an unavailable parts
     * table) can still be replaced or discarded.
     */
    @Test
    fun saveAndDiscard_removeMmsDraftsWithoutReadingTheirParts() {
        mms.rows += MmsRow(id = 60, threadId = 5, box = 3)
        mms.parts += MmsPartRow(id = 1, mid = 60, contentType = "")

        save("5", "text now")
        mms.rows += MmsRow(id = 61, threadId = 5, box = 3)
        discard("5")

        assertEquals(emptyList<Long>(), mms.rows.map { it.id })
        assertEquals("no part was listed", 0, mms.partQueries)
    }

    /**
     * Purpose: a text save removes the thread's MMS drafts, one by one and only
     * its own, before the SMS write's conversation-URI delete recomputes the thread.
     */
    @Test
    fun save_removesTheThreadsMmsDrafts() {
        sms.rows += SmsRow(id = 2, threadId = 5, type = 3, body = "old", date = 10)
        mms.rows += MmsRow(id = 60, threadId = 5, box = 3)
        mms.rows += MmsRow(id = 61, threadId = 5, box = 3)
        mms.rows += MmsRow(id = 62, threadId = 5, box = 1)
        mms.rows += MmsRow(id = 63, threadId = 9, box = 3)
        var mmsAtConversationDelete: List<Long>? = null
        sms.afterWrite = {
            if (sms.deletes.isNotEmpty() && mmsAtConversationDelete == null) {
                mmsAtConversationDelete = mms.rows.map { it.id }
            }
        }

        val result = save("5", "text now")

        assertEquals(listOf<Any?>("2"), result.successes)
        assertEquals(listOf(62L, 63L), mms.rows.map { it.id })
        assertEquals(
            listOf(
                ProviderWrite("content://mms", "_id = ? AND thread_id = ? AND msg_box = ?", listOf("60", "5", "3")),
                ProviderWrite("content://mms", "_id = ? AND thread_id = ? AND msg_box = ?", listOf("61", "5", "3")),
            ),
            mms.deletes,
        )
        assertEquals(listOf(62L, 63L), mmsAtConversationDelete)
        assertEquals(
            listOf(ProviderWrite("content://sms/conversations/5", "type = ? AND _id != ?", listOf("3", "2"))),
            sms.deletes,
        )
    }

    /** Purpose: a thread with only an MMS draft still gets the conversation-URI delete, after the MMS delete. */
    @Test
    fun save_overAMmsOnlyDraft_recomputesTheThread() {
        mms.rows += MmsRow(id = 60, threadId = 5, box = 3)

        val result = save("5", "text now")

        val draft = sms.rows.single()
        assertEquals(listOf<Any?>(draft.id.toString()), result.successes)
        assertTrue(mms.rows.isEmpty())
        assertEquals(
            listOf(ProviderWrite("content://sms/conversations/5", "type = ? AND _id != ?", listOf("3", "${draft.id}"))),
            sms.deletes,
        )
    }

    /** Purpose: an MMS draft the provider kept leaves the text save unverified. */
    @Test
    fun save_mmsDraftSurvives_isIncomplete() {
        mms.rows += MmsRow(id = 60, threadId = 5, box = 3)
        mms.ignoreDeletes = true

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), save("5", "hi").errors)
    }

    /** Purpose: an MMS draft that can't be read means the thread is never written blind. */
    @Test
    fun save_unreadableMmsDrafts_failsBeforeWriting() {
        mms.nullCursor = true

        assertEquals(listOf("DRAFT_WRITE_FAILED"), save("5", "hi").errors)
        assertEquals(0, sms.writeCount() + mms.writeCount())
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
            mapOf("threadId" to "5", "body" to "hi", "subscriptionId" to "2"),
            mapOf("threadId" to "5", "body" to "hi", "subscriptionId" to 2L),
        )) {
            val result = invoke(MethodCall("saveSmsDraft", arguments))

            assertEquals("$arguments", listOf("INVALID_ARGUMENT"), result.errors)
        }
        assertTrue(threads.queries == 0 && sms.queries == 0 && sms.writeCount() == 0)
    }

    // ── discardDraft ──────────────────────────────────────────────────

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

    /** Purpose: a discard deletes the thread's SMS and MMS drafts, MMS one by one, and no other message. */
    @Test
    fun discard_removesSmsAndMmsDrafts() {
        sms.rows += SmsRow(id = 1, threadId = 5, type = 1, body = "inbound")
        sms.rows += SmsRow(id = 2, threadId = 5, type = 3, body = "a", date = 10)
        mms.rows += MmsRow(id = 60, threadId = 5, box = 3)
        mms.rows += MmsRow(id = 62, threadId = 5, box = 1)
        mms.rows += MmsRow(id = 63, threadId = 9, box = 3)

        val result = discard("5")

        assertEquals(listOf<Any?>("discarded"), result.successes)
        assertEquals(listOf(1L), sms.rows.map { it.id })
        assertEquals(listOf(62L, 63L), mms.rows.map { it.id })
        assertEquals(
            listOf(ProviderWrite("content://mms", "_id = ? AND thread_id = ? AND msg_box = ?", listOf("60", "5", "3"))),
            mms.deletes,
        )
    }

    /** Purpose: an MMS draft that survives the delete fails the discard. */
    @Test
    fun discard_mmsDraftSurvives_isIncomplete() {
        mms.rows += MmsRow(id = 60, threadId = 5, box = 3)
        mms.ignoreDeletes = true

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), discard("5").errors)
    }

    /**
     * Purpose: a thread that held only MMS drafts is still deleted through its
     * conversation URI, so the provider recomputes it (and removes it if empty).
     */
    @Test
    fun discard_mmsOnlyThread_deletesThroughTheConversationUri() {
        mms.rows += MmsRow(id = 60, threadId = 5, box = 3)

        val result = discard("5")

        assertEquals(listOf<Any?>("discarded"), result.successes)
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
            assertEquals("$arguments", listOf("INVALID_ARGUMENT"), invoke(MethodCall("discardDraft", arguments)).errors)
        }
        assertTrue(sms.queries == 0 && sms.writeCount() == 0)
    }

    private fun save(threadId: String, body: String) =
        invoke(MethodCall("saveSmsDraft", mapOf("threadId" to threadId, "body" to body)))

    private fun discard(threadId: String) = invoke(MethodCall("discardDraft", threadId))

    private fun roleManager(): ShadowRoleManager =
        Shadow.extract(context.getSystemService(RoleManager::class.java))

    private fun invoke(call: MethodCall): DraftCallResult =
        DraftCallResult().also { drafts.onMethodCall(call, it) }
}
