package io.simplezen.simple_sms.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Purpose: pin which draft a save keeps and when a draft write counts as verified (UNFY-213). */
class DraftVerificationTest {

    /** Purpose: a thread with no draft has nothing to keep, so the save inserts. */
    @Test
    fun draftToKeep_noDrafts_isNull() {
        assertNull(draftToKeep(emptyList()))
    }

    /** Purpose: keep the newest draft by date, then by id; an undated row is oldest. */
    @Test
    fun draftToKeep_isTheNewestByDateThenId() {
        val undated = SmsDraftRow(id = 9, body = "a", date = null)
        val older = SmsDraftRow(id = 8, body = "b", date = 100)
        val newerLowId = SmsDraftRow(id = 3, body = "c", date = 200)
        val newerHighId = SmsDraftRow(id = 4, body = "d", date = 200)

        assertEquals(older, draftToKeep(listOf(undated, older)))
        assertEquals(newerHighId, draftToKeep(listOf(undated, newerHighId, older, newerLowId)))
    }

    /** Purpose: verified only when the saved row is the thread's one draft with exactly the saved body. */
    @Test
    fun isDraftSaved_requiresTheOnlyDraftWithTheSavedBody() {
        val saved = SmsDraftRow(id = 7, body = "see you", date = 1)

        assertTrue(isDraftSaved(listOf(saved), draftId = 7, content = SmsDraftContent("see you")))
        assertFalse(isDraftSaved(emptyList(), draftId = 7, content = SmsDraftContent("see you")))
        assertFalse(isDraftSaved(listOf(saved), draftId = 8, content = SmsDraftContent("see you")))
        assertFalse(isDraftSaved(listOf(saved), draftId = 7, content = SmsDraftContent("see you ")))
        assertFalse(isDraftSaved(listOf(saved.copy(body = null)), draftId = 7, content = SmsDraftContent("see you")))
        assertFalse(
            isDraftSaved(listOf(saved, SmsDraftRow(id = 9, body = "x", date = 2)), draftId = 7, content = SmsDraftContent("see you")),
        )
    }

    /** Purpose: a named subscription must be the saved row's; none named accepts the provider's. */
    @Test
    fun isDraftSaved_checksANamedSubscription() {
        val saved = SmsDraftRow(id = 7, body = "hi", date = 1, subscriptionId = 2)

        assertTrue(isDraftSaved(listOf(saved), draftId = 7, content = SmsDraftContent("hi", subscriptionId = 2)))
        assertTrue(isDraftSaved(listOf(saved), draftId = 7, content = SmsDraftContent("hi")))
        assertFalse(isDraftSaved(listOf(saved), draftId = 7, content = SmsDraftContent("hi", subscriptionId = 1)))
        assertFalse(
            isDraftSaved(listOf(saved.copy(subscriptionId = null)), draftId = 7, content = SmsDraftContent("hi", 2)),
        )
    }

    /** Purpose: any remaining draft fails the discard; otherwise the deleted count tells discarded from absent. */
    @Test
    fun classifyDiscard() {
        assertEquals(DraftDiscardOutcome.DISCARDED, classifyDiscard(deletedRows = 1, remainingDrafts = 0))
        assertEquals(DraftDiscardOutcome.ALREADY_ABSENT, classifyDiscard(deletedRows = 0, remainingDrafts = 0))
        assertEquals(DraftDiscardOutcome.INCOMPLETE, classifyDiscard(deletedRows = 1, remainingDrafts = 1))
        assertEquals(DraftDiscardOutcome.INCOMPLETE, classifyDiscard(deletedRows = 0, remainingDrafts = 1))
    }

    /** Purpose: the thread's drafts across both tables, as a draft of [types] after one SMIL part. */
    private fun mmsRow(id: Long, vararg types: String, text: String? = null) = MmsDraftRow(
        id = id,
        date = 1,
        parts = listOf(MmsDraftPart("application/smil", "<smil/>")) +
            types.map { MmsDraftPart(it, if (it == "text/plain") text else null) },
    )

    private fun attachment(mimeType: String) = MmsDraftAttachment("/cache/file", mimeType, byteArrayOf(1))

    /** Purpose: verified only when the one MMS draft holds the attachments in order, then the body, and no SMS draft remains. */
    @Test
    fun isMmsDraftSaved_requiresTheOnlyDraftWithItsAttachmentsAndBody() {
        val content = MmsDraftContent("caption", listOf(attachment("image/jpeg"), attachment("application/pdf")))
        val saved = mmsRow(7, "image/jpeg", "application/pdf", "text/plain", text = "caption")

        assertTrue(isMmsDraftSaved(listOf(saved), smsDrafts = 0, draftId = 7, content = content))
        assertFalse(isMmsDraftSaved(emptyList(), 0, 7, content))
        assertFalse(isMmsDraftSaved(listOf(saved), 1, 7, content))
        assertFalse(isMmsDraftSaved(listOf(saved), 0, 8, content))
        assertFalse(isMmsDraftSaved(listOf(saved, mmsRow(9, "image/jpeg")), 0, 7, content))
        // A dropped, reordered or extra part, and a changed body.
        assertFalse(isMmsDraftSaved(listOf(mmsRow(7, "image/jpeg", "text/plain", text = "caption")), 0, 7, content))
        assertFalse(
            isMmsDraftSaved(listOf(mmsRow(7, "application/pdf", "image/jpeg", "text/plain", text = "caption")), 0, 7, content),
        )
        assertFalse(
            isMmsDraftSaved(
                listOf(mmsRow(7, "image/jpeg", "application/pdf", "text/plain", "text/plain", text = "caption")),
                0,
                7,
                content,
            ),
        )
        assertFalse(
            isMmsDraftSaved(listOf(mmsRow(7, "image/jpeg", "application/pdf", "text/plain", text = "other")), 0, 7, content),
        )
        assertFalse(isMmsDraftSaved(listOf(saved.copy(parts = saved.parts.dropLast(1))), 0, 7, content))
    }

    /** Purpose: a blank or missing body has no text part, and a text part then fails verification. */
    @Test
    fun isMmsDraftSaved_withoutABody_expectsNoTextPart() {
        val photo = listOf(attachment("image/jpeg"))
        val saved = mmsRow(7, "image/jpeg")

        assertTrue(isMmsDraftSaved(listOf(saved), 0, 7, MmsDraftContent(null, photo)))
        assertTrue(isMmsDraftSaved(listOf(saved), 0, 7, MmsDraftContent("  ", photo)))
        assertFalse(isMmsDraftSaved(listOf(mmsRow(7, "image/jpeg", "text/plain", text = "x")), 0, 7, MmsDraftContent(null, photo)))
    }

    /** Purpose: the attachment's bytes are compared by content, and its string form never carries them. */
    @Test
    fun mmsDraftAttachment_comparesBytesByContentAndHidesThem() {
        val a = MmsDraftAttachment("/cache/a", "image/jpeg", byteArrayOf(1, 2))

        assertEquals(a, MmsDraftAttachment("/cache/a", "image/jpeg", byteArrayOf(1, 2)))
        assertEquals(a.hashCode(), MmsDraftAttachment("/cache/a", "image/jpeg", byteArrayOf(1, 2)).hashCode())
        assertFalse(a == MmsDraftAttachment("/cache/a", "image/jpeg", byteArrayOf(1, 3)))
        assertFalse(a == MmsDraftAttachment("/cache/a", "image/png", byteArrayOf(1, 2)))
        assertEquals("MmsDraftAttachment(path=/cache/a, mimeType=image/jpeg, bytes=2)", a.toString())
    }

    /** Purpose: a draft left in either table fails the discard; otherwise the deleted count tells discarded from absent. */
    @Test
    fun classifyThreadDiscard() {
        assertEquals(DraftDiscardOutcome.DISCARDED, classifyThreadDiscard(deletedRows = 2, remainingSms = 0, remainingMms = 0))
        assertEquals(DraftDiscardOutcome.ALREADY_ABSENT, classifyThreadDiscard(0, 0, 0))
        assertEquals(DraftDiscardOutcome.INCOMPLETE, classifyThreadDiscard(1, 1, 0))
        assertEquals(DraftDiscardOutcome.INCOMPLETE, classifyThreadDiscard(1, 0, 1))
        assertEquals(DraftDiscardOutcome.INCOMPLETE, classifyThreadDiscard(0, 1, 1))
    }
}
