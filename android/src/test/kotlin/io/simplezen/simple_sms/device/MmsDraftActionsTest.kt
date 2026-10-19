package io.simplezen.simple_sms.device

import android.app.role.RoleManager
import android.content.Context
import android.webkit.MimeTypeMap
import androidx.test.core.app.ApplicationProvider
import io.flutter.plugin.common.MethodCall
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowRoleManager
import java.io.File

/**
 * Purpose: pin the native MMS draft write contract (UNFY-281, ADR-0016) — a
 * save leaves the thread exactly one draft, an MMS row holding the given
 * attachments and body and marked read and seen, answers its `_id`, and every
 * unverifiable state surfaces as its own error code. The PDU persister runs
 * for real against an in-memory `content://mms`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MmsDraftActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sms = FakeSmsProvider()
    private val mms = FakeMmsProvider(context)
    private val threads = FakeThreadsProvider()
    private val drafts = DraftActions(context)

    @get:Rule
    val files = TemporaryFolder()

    /** Purpose: isolate provider state per test, list thread 5 (the recipients' thread), and start as the default SMS app. */
    @Before
    fun setUp() {
        ShadowContentResolver.registerProviderInternal("sms", sms)
        ShadowContentResolver.registerProviderInternal("mms", mms)
        ShadowContentResolver.registerProviderInternal("mms-sms", threads)
        threads.listed += 5L
        // The persister probes the provider once per process; probe this one.
        io.simplezen.simple_sms.mms.support.SubscriptionIdChecker::class.java.getDeclaredField("sInstance").apply {
            isAccessible = true
            set(null, null)
        }
        // The attachments are typed by file extension, as a send reads them.
        shadowOf(MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("jpg", "image/jpeg")
            addExtensionMimeTypeMapping("pdf", "application/pdf")
            addExtensionMimeTypeMapping("txt", "text/plain")
        }
        roleManager().apply {
            addAvailableRole(RoleManager.ROLE_SMS)
            addHeldRole(RoleManager.ROLE_SMS)
        }
    }

    // ── saveMmsDraft ──────────────────────────────────────────────────

    /**
     * Purpose: a save leaves the thread one MMS draft whose non-SMIL parts are
     * the attachments, in order, then the body, with the raw bytes and no SMS
     * draft or other MMS draft; other threads and boxes are untouched.
     */
    @Test
    fun save_insertsOneMmsDraftAndRemovesOtherDrafts() {
        val photo = file("photo.jpg", byteArrayOf(1, 2, 3))
        val note = file("note.pdf", byteArrayOf(9, 8))
        sms.rows += SmsRow(id = 2, threadId = 5, type = 3, body = "old text", date = 10)
        sms.rows += SmsRow(id = 3, threadId = 5, type = 1, body = "inbound")
        mms.rows += MmsRow(id = 60, threadId = 5, box = 3)
        mms.rows += MmsRow(id = 61, threadId = 5, box = 1)
        mms.rows += MmsRow(id = 62, threadId = 9, box = 3)

        val result = save("5", "caption", listOf(photo, note))

        val draft = mms.rows.single { it.threadId == 5L && it.box == 3 }
        assertEquals(listOf<Any?>(draft.id.toString()), result.successes)
        assertEquals(listOf(61L, 62L), mms.rows.map { it.id }.filter { it != draft.id })
        assertEquals(listOf(3L), sms.rows.map { it.id })

        val parts = mms.parts.filter { it.mid == draft.id }
        assertEquals("application/smil", parts.first().contentType)
        val content = parts.filter { it.contentType != "application/smil" }
        assertEquals(listOf("image/jpeg", "application/pdf", "text/plain"), content.map { it.contentType })
        assertEquals(listOf("photo.jpg", "note.pdf", "text"), content.map { it.location })
        assertEquals(listOf<Byte>(1, 2, 3), mms.bytesOf(content[0].id).toList())
        assertEquals(listOf<Byte>(9, 8), mms.bytesOf(content[1].id).toList())
        assertEquals("caption", content[2].text)
        assertEquals(listOf(draft.id to ADDRESS), mms.addresses.filter { it.second == ADDRESS })
        assertEquals(listOf(setOf(ADDRESS)), threads.recipientLookups)
        // The persister allocated the thread itself: the stray-thread cleanup never ran.
        assertEquals(
            listOf(ProviderWrite("content://sms/conversations/5", "type = ?", listOf("3"))),
            sms.deletes,
        )
        assertEquals(
            listOf(ProviderWrite("content://mms", "_id = ? AND thread_id = ? AND msg_box = ?", listOf("60", "5", "3"))),
            mms.deletes,
        )
    }

    /** Purpose: a draft is never unread or unseen, so it must not count toward the unread badge or notifications. */
    @Test
    fun save_marksTheDraftReadAndSeen() {
        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        val draft = mms.rows.single()
        assertEquals(listOf<Any?>(draft.id.toString()), result.successes)
        assertEquals(1, mms.rowUpdates.single().getAsInteger("read"))
        assertEquals(1, mms.rowUpdates.single().getAsInteger("seen"))
        assertEquals(1, draft.read)
        assertEquals(1, draft.seen)
    }

    /** Purpose: without a body, or with a blank one, the draft holds the attachments only. */
    @Test
    fun save_withoutAReadableBody_hasNoTextPart() {
        val photo = file("photo.jpg", byteArrayOf(1))

        for (body in listOf(null, "", "   ")) {
            mms.rows.clear()
            mms.parts.clear()

            val result = invoke(saveCall(body = body, paths = listOf(photo)))

            assertEquals("$body", 1, result.successes.size)
            assertEquals(
                "$body",
                listOf("image/jpeg"),
                mms.parts.map { it.contentType }.filter { it != "application/smil" },
            )
        }
    }

    /** Purpose: a file whose type is unknown is saved as `application/octet-stream`, as a send reads it. */
    @Test
    fun save_unknownFileType_isOctetStream() {
        val result = save("5", null, listOf(file("blob.zzzz", byteArrayOf(7))))

        assertEquals(1, result.successes.size)
        assertEquals(
            listOf("application/octet-stream"),
            mms.parts.map { it.contentType }.filter { it != "application/smil" },
        )
    }

    /**
     * Purpose: recipients that resolve to another thread remove the new row
     * from it — only that row — then delete through that thread's conversation
     * URI so the provider recomputes it, and the save is incomplete.
     */
    @Test
    fun save_recipientsResolveToAnotherThread_removesTheRowAndIsIncomplete() {
        threads.threadForRecipients = 9L
        mms.rows += MmsRow(id = 60, threadId = 5, box = 3)
        mms.rows += MmsRow(id = 70, threadId = 9, box = 1)
        mms.rows += MmsRow(id = 71, threadId = 9, box = 3)

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), result.errors)
        // The thread-5 draft and every thread-9 row survive; only the new row is gone.
        assertEquals(listOf(60L, 70L, 71L), mms.rows.map { it.id })
        val strayId = mms.deletes.single().arguments!![0]
        assertEquals(
            listOf(ProviderWrite("content://mms", "_id = ? AND msg_box = ?", listOf(strayId, "3"))),
            mms.deletes,
        )
        assertEquals(
            listOf(ProviderWrite("content://sms/conversations/9", "_id = ?", listOf("-1"))),
            sms.deletes,
        )
    }

    /** Purpose: a stray row the provider keeps is reported as a failed write, never claimed removed. */
    @Test
    fun save_strayRowSurvivesRemoval_isDraftWriteFailed() {
        threads.threadForRecipients = 9L
        mms.ignoreDeletes = true

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("DRAFT_WRITE_FAILED"), result.errors)
        assertEquals(1, mms.rows.size)
    }

    /** Purpose: an unreadable attachment fails the save before anything is written or allocated. */
    @Test
    fun save_unreadableAttachment_failsBeforeWriting() {
        mms.rows += MmsRow(id = 60, threadId = 5, box = 3)
        val missing = File(files.root, "gone.jpg").path

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1)), missing))

        assertEquals(listOf("DRAFT_WRITE_FAILED"), result.errors)
        assertEquals(0, sms.writeCount() + mms.writeCount())
        assertTrue(threads.recipientLookups.isEmpty())
        assertEquals(listOf(60L), mms.rows.map { it.id })
    }

    /** Purpose: a draft must belong to a listed thread; nothing is read or written otherwise. */
    @Test
    fun save_unlistedThread_isThreadNotFound() {
        val result = save("8", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("THREAD_NOT_FOUND"), result.errors)
        assertEquals(0, sms.writeCount() + mms.writeCount())
        assertEquals(0, mms.queries)
    }

    /** Purpose: without the SMS role Android ignores the write, so nothing is read or written. */
    @Test
    fun save_withoutSmsRole_isNotDefaultSmsApp() {
        roleManager().removeHeldRole(RoleManager.ROLE_SMS)

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("NOT_DEFAULT_SMS_APP"), result.errors)
        assertTrue(threads.queries == 0 && sms.queries == 0 && mms.queries == 0)
        assertEquals(0, sms.writeCount() + mms.writeCount())
    }

    /** Purpose: reject calls that name no attachment, no recipient or a malformed argument before any I/O. */
    @Test
    fun save_noAttachments_isInvalidArgument() {
        val photo = file("photo.jpg", byteArrayOf(1))
        val valid = mapOf<String, Any?>(
            "threadId" to "5",
            "addresses" to listOf(ADDRESS),
            "body" to "caption",
            "attachmentPaths" to listOf(photo),
        )
        for (arguments in listOf<Map<String, Any?>?>(
            null,
            valid + ("attachmentPaths" to emptyList<String>()),
            valid - "attachmentPaths",
            valid + ("attachmentPaths" to listOf(photo, " ")),
            valid + ("attachmentPaths" to listOf(photo, 7)),
            valid + ("attachmentPaths" to photo),
            valid + ("attachmentPaths" to List(9) { photo }),
            valid + ("addresses" to emptyList<String>()),
            valid - "addresses",
            valid + ("addresses" to listOf(" ")),
            valid + ("threadId" to "0"),
            valid + ("threadId" to 5),
            valid + ("body" to 7),
            valid + ("subscriptionId" to "2"),
            valid + ("subscriptionId" to 2L),
        )) {
            val result = invoke(MethodCall("saveMmsDraft", arguments))

            assertEquals("$arguments", listOf("INVALID_ARGUMENT"), result.errors)
        }
        assertTrue(threads.queries == 0 && sms.queries == 0 && mms.queries == 0)
        assertEquals(0, sms.writeCount() + mms.writeCount())
    }

    /** Purpose: up to eight attachments are accepted. */
    @Test
    fun save_eightAttachments_isAccepted() {
        val photos = List(8) { file("photo$it.jpg", byteArrayOf(it.toByte())) }

        val result = save("5", null, photos)

        assertEquals(1, result.successes.size)
        assertEquals(8, mms.parts.count { it.contentType == "image/jpeg" })
    }

    /** Purpose: the body and the attachment bytes are message content: no error text carries them. */
    @Test
    fun save_errorsNeverCarryTheBody() {
        val photo = file("photo.jpg", "secret-bytes".toByteArray())
        val calls = mutableListOf<DraftCallResult>()

        // Unverified, and the new draft cannot be removed: the earlier MMS draft survives.
        mms.rows += MmsRow(id = 60, threadId = 5, box = 3)
        mms.ignoreDeletes = true
        calls += save("5", "my secret", listOf(photo))
        mms.ignoreDeletes = false

        // A provider exception echoing the submitted values.
        mms.writeFailure = IllegalStateException("insert failed: body=my secret", RuntimeException("body=my secret"))
        calls += save("5", "my secret", listOf(photo))
        mms.writeFailure = null

        // An unreadable attachment.
        calls += save("5", "my secret", listOf(File(files.root, "secret-bytes.jpg").path))

        // Malformed arguments.
        calls += invoke(saveCall(body = "my secret", paths = emptyList()))
        calls += invoke(MethodCall("saveMmsDraft", mapOf("threadId" to "x", "body" to "my secret")))

        assertEquals(
            listOf("DRAFT_WRITE_FAILED", "DRAFT_WRITE_FAILED", "DRAFT_WRITE_FAILED", "INVALID_ARGUMENT", "INVALID_ARGUMENT"),
            calls.flatMap { it.errors },
        )
        calls.flatMap { it.messages }.forEach { assertFalse(it, it.contains("secret")) }
        assertTrue(calls[1].messages.single().contains("java.lang.IllegalStateException"))
    }

    /** Purpose: a thread deleted while the save ran leaves no orphaned draft and reports the thread gone. */
    @Test
    fun save_threadDeletedDuringSave_removesTheDraftAndIsThreadNotFound() {
        mms.afterWrite = { if (mms.rowUpdates.isNotEmpty()) threads.listed.remove(5L) }

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("THREAD_NOT_FOUND"), result.errors)
        assertTrue(mms.rows.isEmpty())
    }

    /** Purpose: a part the provider dropped leaves the save unverified. */
    @Test
    fun save_partDropped_isIncomplete() {
        mms.afterWrite = {
            if (mms.rowUpdates.isNotEmpty()) mms.parts.removeAll { it.contentType == "text/plain" }
        }

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), result.errors)
        assertTrue("the unverified draft is removed", mms.rows.isEmpty())
        assertTrue(result.messages.single(), result.messages.single().contains("the new draft was removed"))
    }

    /** Purpose: an SMS draft another app added meanwhile leaves the save unverified. */
    @Test
    fun save_concurrentSmsDraft_isIncomplete() {
        mms.afterWrite = {
            if (mms.rowUpdates.isNotEmpty()) sms.rows += SmsRow(id = 50, threadId = 5, type = 3, body = "theirs", date = 99)
        }

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), result.errors)
        assertTrue("the unverified draft is removed", mms.rows.isEmpty())
    }

    /**
     * Purpose: a save whose earlier drafts can't be removed removes its own new
     * draft too, then fails; the earlier draft stays.
     */
    @Test
    fun save_earlierDraftsCannotBeRemoved_removesTheNewDraftAndFails() {
        mms.rows += MmsRow(id = 60, threadId = 5, box = 3)
        mms.afterWrite = {
            if (mms.rowUpdates.isNotEmpty() && mms.deletes.isEmpty() && mms.failNextWrite == null) {
                mms.failNextWrite = IllegalStateException("disk full")
            }
        }

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("DRAFT_WRITE_FAILED"), result.errors)
        assertEquals(listOf(60L), mms.rows.map { it.id })
        assertTrue(result.messages.single(), result.messages.single().contains("the new draft was removed"))
    }

    /** Purpose: when the new draft can't be removed either, the error says so. */
    @Test
    fun save_neitherTheEarlierDraftsNorTheNewOneCanBeRemoved_isSaidInTheError() {
        mms.rows += MmsRow(id = 60, threadId = 5, box = 3)
        mms.afterWrite = { if (mms.rowUpdates.isNotEmpty()) mms.writeFailure = IllegalStateException("disk full") }

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("DRAFT_WRITE_FAILED"), result.errors)
        assertTrue(result.messages.single(), result.messages.single().contains("could not be removed"))
        assertEquals(2, mms.rows.size)
    }

    /**
     * Purpose: a save that fails after the row was inserted leaves no unfinished
     * (unread) draft beside the earlier one, which stays, and no parts under the
     * persister's temporary id.
     */
    @Test
    fun save_persistFailsAfterTheRowIsInserted_removesTheUnfinishedDraft() {
        mms.rows += MmsRow(id = 60, threadId = 5, box = 3)
        var armed = true
        mms.afterWrite = {
            if (armed && mms.insertedDrafts.isNotEmpty()) {
                armed = false
                mms.failNextWrite = IllegalStateException("disk full")
            }
        }

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("DRAFT_WRITE_FAILED"), result.errors)
        assertEquals(listOf(60L), mms.rows.map { it.id })
        assertTrue("no part is left under the temporary id", mms.parts.isEmpty())
        assertFalse(result.messages.single(), result.messages.single().contains("could not be removed"))
    }

    /**
     * Purpose: a failure after parts were saved but before any row was inserted
     * leaves none of them behind.
     */
    @Test
    fun save_persistFailsBeforeTheRowIsInserted_leavesNoTemporaryParts() {
        var armed = true
        mms.afterWrite = {
            if (armed && mms.parts.isNotEmpty()) {
                armed = false
                mms.failNextWrite = IllegalStateException("disk full")
            }
        }

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1)), file("note.pdf", byteArrayOf(2))))

        assertEquals(listOf("DRAFT_WRITE_FAILED"), result.errors)
        assertTrue(mms.rows.isEmpty())
        assertTrue("no part is left under the temporary id", mms.parts.isEmpty())
    }

    /**
     * Purpose: a row inserted into the recipients' thread whose read + seen
     * update then fails is removed from that thread, which the provider then
     * recomputes.
     */
    @Test
    fun save_updateFailsForARowInAnotherThread_removesThatRow() {
        threads.threadForRecipients = 9L
        mms.rows += MmsRow(id = 70, threadId = 9, box = 1)
        mms.failDraftRowUpdate = IllegalStateException("disk full")

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("DRAFT_WRITE_FAILED"), result.errors)
        assertEquals(listOf(70L), mms.rows.map { it.id })
        assertEquals(
            listOf(ProviderWrite("content://sms/conversations/9", "_id = ?", listOf("-1"))),
            sms.deletes,
        )
    }

    /** Purpose: a provider exception while persisting surfaces as a failed write. */
    @Test
    fun save_providerThrows_isDraftWriteFailed() {
        mms.writeFailure = IllegalStateException("disk full")

        assertEquals(listOf("DRAFT_WRITE_FAILED"), save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1)))).errors)
    }

    /** Purpose: when nothing can be read, not even the new draft's thread, the draft is left and the failure says so. */
    @Test
    fun save_providerStopsAnsweringQueries_isDraftWriteFailedAndSaysTheDraftRemains() {
        mms.queryFailureAfterWrite = IllegalStateException("provider died")

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("DRAFT_WRITE_FAILED"), result.errors)
        assertTrue(result.messages.single(), result.messages.single().contains("could not be removed"))
    }

    /** Purpose: a part whose data can't be read back is unverified, and the new draft is removed. */
    @Test
    fun save_partDataUnreadable_isVerifyFailedAndRemovesTheDraft() {
        mms.afterWrite = { if (mms.rowUpdates.isNotEmpty()) mms.failPartReads = true }

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("VERIFY_FAILED"), result.errors)
        assertTrue(mms.rows.isEmpty())
    }

    /** Purpose: a persister whose write left the unfinished draft in place and cannot remove it says so. */
    @Test
    fun save_unfinishedDraftCannotBeRemoved_isSaidInTheError() {
        mms.afterWrite = { if (mms.insertedDrafts.isNotEmpty() && mms.rowUpdates.isEmpty()) mms.writeFailure = IllegalStateException("disk full") }

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("DRAFT_WRITE_FAILED"), result.errors)
        assertTrue(result.messages.single(), result.messages.single().contains("could not be removed"))
        assertEquals(1, mms.rows.size)
    }

    // ── verification ──────────────────────────────────────────────────

    /** Purpose: a store that keeps other bytes than were written (corrupted or substituted) is unverified. */
    @Test
    fun save_corruptedAttachmentData_isIncompleteAndRemovesTheDraft() {
        mms.afterWrite = {
            mms.parts.firstOrNull { it.contentType == "image/jpeg" }?.let {
                if (mms.rowUpdates.isNotEmpty()) mms.storedDataOverride[it.id] = byteArrayOf(9, 9, 9)
            }
        }

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1, 2, 3))))

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), result.errors)
        assertTrue(mms.rows.isEmpty())
    }

    /** Purpose: a store that truncates an attachment (a stream that failed on close) is unverified. */
    @Test
    fun save_truncatedAttachmentData_isIncompleteAndRemovesTheDraft() {
        mms.afterWrite = {
            mms.parts.firstOrNull { it.contentType == "image/jpeg" }?.let {
                if (mms.rowUpdates.isNotEmpty()) mms.storedDataOverride[it.id] = byteArrayOf(1)
            }
        }

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1, 2, 3))))

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), result.errors)
        assertTrue(mms.rows.isEmpty())
    }

    /** Purpose: a provider that counts the read + seen update but keeps the flags would count the draft as unread. */
    @Test
    fun save_readAndSeenFlagsIgnored_isIncompleteAndRemovesTheDraft() {
        mms.ignoreReadSeenUpdate = true

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), result.errors)
        assertTrue(mms.rows.isEmpty())
    }

    /** Purpose: a named subscription is stored, and verified. */
    @Test
    fun save_namedSubscription_isStoredAndVerified() {
        val result = invoke(saveCall(body = "caption", paths = listOf(file("photo.jpg", byteArrayOf(1))), subscriptionId = 4))

        assertEquals(1, result.successes.size)
        assertEquals(4L, mms.rows.single().subId)
    }

    /** Purpose: a provider that stores another SIM than the one named leaves the save unverified. */
    @Test
    fun save_providerStoresAnotherSubscription_isIncompleteAndRemovesTheDraft() {
        mms.forcedSubId = 7L

        val result = invoke(saveCall(body = "caption", paths = listOf(file("photo.jpg", byteArrayOf(1))), subscriptionId = 4))

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), result.errors)
        assertTrue(mms.rows.isEmpty())
    }

    /** Purpose: a recipient row the provider did not keep leaves the draft unaddressed, so the save is unverified. */
    @Test
    fun save_recipientRowMissing_isIncompleteAndRemovesTheDraft() {
        mms.failingAddresses += ADDRESS

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), result.errors)
        assertTrue(mms.rows.isEmpty())
    }

    /** Purpose: a draft the provider mangled costs the thread's earlier drafts nothing: they go only once the new one is verified. */
    @Test
    fun save_contentUnverified_keepsTheEarlierDrafts() {
        sms.rows += SmsRow(id = 2, threadId = 5, type = 3, body = "old text", date = 10)
        mms.rows += MmsRow(id = 60, threadId = 5, box = 3)
        mms.ignoreReadSeenUpdate = true

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1))))

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), result.errors)
        assertEquals(listOf(60L), mms.rows.map { it.id })
        assertEquals(listOf(2L), sms.rows.map { it.id })
    }

    /** Purpose: a provider with no subscription column never stores the SIM, so the save is not verified against it. */
    @Test
    fun save_providerWithoutASubscriptionColumn_isNotVerifiedAgainstTheSim() {
        mms.noSubIdColumn = true

        val result = invoke(saveCall(body = "caption", paths = listOf(file("photo.jpg", byteArrayOf(1))), subscriptionId = 4))

        assertEquals(1, result.successes.size)
    }

    /** Purpose: a text attachment is kept by the provider as text; one it cannot keep faithfully fails verification. */
    @Test
    fun save_textAttachmentThatIsNotUtf8_isIncompleteAndRemovesTheDraft() {
        val latin1 = file("note.txt", byteArrayOf(0x63, 0xE9.toByte()))

        val result = save("5", null, listOf(latin1))

        assertEquals(listOf("DRAFT_WRITE_INCOMPLETE"), result.errors)
        assertTrue(mms.rows.isEmpty())
    }

    /** Purpose: a UTF-8 text attachment is saved and verified as text. */
    @Test
    fun save_utf8TextAttachment_isSaved() {
        val note = file("note.txt", "café".toByteArray())

        val result = save("5", null, listOf(note))

        assertEquals(1, result.successes.size)
        assertEquals("café", mms.parts.single { it.contentType == "text/plain" }.text)
    }

    // ── size bounds ───────────────────────────────────────────────────

    /** Purpose: a file over the per-attachment bound is refused before anything is read or written. */
    @Test
    fun save_attachmentTooLarge_isInvalidArgumentBeforeAnyRead() {
        val huge = sparseFile("huge.jpg", 16L * 1024 * 1024 + 1)

        val result = save("5", "caption", listOf(file("photo.jpg", byteArrayOf(1)), huge))

        assertEquals(listOf("INVALID_ARGUMENT"), result.errors)
        assertEquals(0, sms.writeCount() + mms.writeCount())
        assertFalse(result.messages.single(), result.messages.single().contains(huge))
    }

    /** Purpose: attachments that are each within the bound but too large together are refused the same way. */
    @Test
    fun save_attachmentsTooLargeTogether_isInvalidArgumentBeforeAnyRead() {
        val parts = List(3) { sparseFile("part$it.jpg", 12L * 1024 * 1024) }

        val result = save("5", "caption", parts)

        assertEquals(listOf("INVALID_ARGUMENT"), result.errors)
        assertEquals(0, sms.writeCount() + mms.writeCount())
    }

    /** Purpose: a file that fits the little room left in the total is read, down to the last byte. */
    @Test
    fun readBounded_limitBelowTheChunkSize_readsAFileThatFits() {
        val tiny = File(file("tiny.bin", byteArrayOf(7)))

        assertArrayEquals(byteArrayOf(7), drafts.readBounded(tiny, limit = 1))
        assertArrayEquals(byteArrayOf(7), drafts.readBounded(tiny, limit = 1000))
    }

    /** Purpose: with no room left, an empty file is read and any byte is refused. */
    @Test
    fun readBounded_zeroLimit_acceptsAnEmptyFileAndRefusesAByte() {
        assertEquals(0, drafts.readBounded(File(file("empty.bin", ByteArray(0))), limit = 0).size)

        val failure = runCatching { drafts.readBounded(File(file("one.bin", byteArrayOf(1))), limit = 0) }
            .exceptionOrNull()
        assertTrue(failure.toString(), failure?.message?.contains("exceeded its share") == true)
    }

    /** Purpose: a file one byte over the room left is refused, not truncated. */
    @Test
    fun readBounded_fileOneByteOverTheLimit_isRefused() {
        val failure = runCatching { drafts.readBounded(File(file("two.bin", byteArrayOf(1, 2))), limit = 1) }
            .exceptionOrNull()

        assertTrue(failure.toString(), failure?.message?.contains("exceeded its share") == true)
    }

    // ── helpers ───────────────────────────────────────────────────────

    private fun file(name: String, bytes: ByteArray): String = files.newFile(name).apply { writeBytes(bytes) }.path

    /** A file of [size] bytes that takes no disk and is never read in full. */
    private fun sparseFile(name: String, size: Long): String =
        files.newFile(name).apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(size) } }.path

    private fun save(threadId: String, body: String?, paths: List<String>) =
        invoke(saveCall(threadId, body, paths))

    private fun saveCall(
        threadId: String = "5",
        body: String?,
        paths: List<String>,
        subscriptionId: Int? = null,
    ) = MethodCall(
        "saveMmsDraft",
        mapOf(
            "threadId" to threadId,
            "addresses" to listOf(ADDRESS),
            "body" to body,
            "attachmentPaths" to paths,
            "subscriptionId" to subscriptionId,
        ),
    )

    private fun roleManager(): ShadowRoleManager =
        Shadow.extract(context.getSystemService(RoleManager::class.java))

    private fun invoke(call: MethodCall): DraftCallResult =
        DraftCallResult().also { drafts.onMethodCall(call, it) }

    private companion object {
        const val ADDRESS = "+15555550100"
    }
}
