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

    private fun attachment(mimeType: String, vararg bytes: Byte) =
        MmsDraftAttachment("/cache/file", mimeType, if (bytes.isEmpty()) byteArrayOf(1) else bytes)

    /** The part the provider keeps for [attachment]: its text for a text part, else its data. */
    private fun partOf(attachment: MmsDraftAttachment) =
        if (attachment.mimeType == "text/plain") {
            MmsDraftPart(attachment.mimeType, String(attachment.bytes))
        } else {
            MmsDraftPart(attachment.mimeType, null, attachment.bytes)
        }

    /** The draft row a faithful provider holds after saving [content] to [ADDRESSES] as row [id]. */
    private fun savedRow(id: Long, content: MmsDraftContent) = MmsDraftRow(
        id = id,
        date = 1,
        read = 1,
        seen = 1,
        subscriptionId = content.subscriptionId?.toLong(),
        recipients = ADDRESSES,
        parts = listOf(MmsDraftPart("application/smil", "<smil/>")) +
            content.attachments.map(::partOf) +
            listOfNotNull(content.body?.takeIf { it.isNotBlank() }?.let { MmsDraftPart("text/plain", it) }),
    )

    private fun verified(rows: List<MmsDraftRow>, content: MmsDraftContent, smsDrafts: Int = 0, draftId: Long = 7) =
        isMmsDraftSaved(rows, smsDrafts, draftId, content, ADDRESSES)

    private val twoAttachments = MmsDraftContent(
        "caption",
        listOf(attachment("image/jpeg", 1, 2), attachment("application/pdf", 3, 4)),
    )

    /** Purpose: verified only when the one MMS draft holds the attachments in order, then the body, and no SMS draft remains. */
    @Test
    fun isMmsDraftSaved_requiresTheOnlyDraftWithItsAttachmentsAndBody() {
        val content = twoAttachments
        val saved = savedRow(7, content)

        assertTrue(verified(listOf(saved), content))
        assertFalse(verified(emptyList(), content))
        assertFalse(verified(listOf(saved), content, smsDrafts = 1))
        assertFalse(verified(listOf(saved), content, draftId = 8))
        assertFalse(verified(listOf(saved, savedRow(9, MmsDraftContent(null, listOf(attachment("image/jpeg"))))), content))
        // A dropped, reordered or extra part, and a changed body.
        assertFalse(verified(listOf(saved.copy(parts = saved.parts.filterNot { it.contentType == "application/pdf" })), content))
        assertFalse(
            verified(listOf(saved.copy(parts = listOf(saved.parts[0], saved.parts[2], saved.parts[1], saved.parts[3]))), content),
        )
        assertFalse(verified(listOf(saved.copy(parts = saved.parts + MmsDraftPart("text/plain", "caption"))), content))
        assertFalse(verified(listOf(saved.copy(parts = saved.parts.dropLast(1) + MmsDraftPart("text/plain", "other"))), content))
        assertFalse(verified(listOf(saved.copy(parts = saved.parts.dropLast(1))), content))
    }

    /** Purpose: a blank or missing body has no text part, and a text part then fails verification. */
    @Test
    fun isMmsDraftSaved_withoutABody_expectsNoTextPart() {
        val photo = listOf(attachment("image/jpeg"))
        val saved = savedRow(7, MmsDraftContent(null, photo))

        assertTrue(verified(listOf(saved), MmsDraftContent(null, photo)))
        assertTrue(verified(listOf(saved), MmsDraftContent("  ", photo)))
        assertFalse(verified(listOf(saved.copy(parts = saved.parts + MmsDraftPart("text/plain", "x"))), MmsDraftContent(null, photo)))
    }

    /** Purpose: corrupted or substituted attachment data is unverified even when the part types match. */
    @Test
    fun isMmsDraftSaved_comparesEveryAttachmentsData() {
        val content = twoAttachments
        val saved = savedRow(7, content)
        val photo = saved.parts[1]

        assertFalse(verified(listOf(saved.copy(parts = saved.parts.map { if (it === photo) MmsDraftPart(it.contentType, null, byteArrayOf(1)) else it })), content))
        assertFalse(verified(listOf(saved.copy(parts = saved.parts.map { if (it === photo) MmsDraftPart(it.contentType, null, byteArrayOf(1, 3)) else it })), content))
        assertFalse(verified(listOf(saved.copy(parts = saved.parts.map { if (it === photo) MmsDraftPart(it.contentType, null, null) else it })), content))
    }

    /** Purpose: a text attachment is kept by the provider as text, so its text is compared. */
    @Test
    fun isMmsDraftSaved_comparesATextAttachmentsText() {
        val content = MmsDraftContent(null, listOf(attachment("text/plain", 'h'.code.toByte(), 'i'.code.toByte())))
        val saved = savedRow(7, content)

        assertTrue(verified(listOf(saved), content))
        assertFalse(verified(listOf(saved.copy(parts = listOf(saved.parts[0], MmsDraftPart("text/plain", "ho")))), content))
    }

    /** Purpose: text that is not UTF-8 decodes lossily on both sides, so it only counts as kept when it round-trips. */
    @Test
    fun isMmsDraftSaved_aTextAttachmentThatIsNotUtf8_isUnverified() {
        val latin1 = MmsDraftContent(null, listOf(MmsDraftAttachment("/cache/n.txt", "text/plain", byteArrayOf(0x63, 0xE9.toByte()))))
        val saved = savedRow(7, latin1)

        assertFalse(verified(listOf(saved), latin1))
    }

    /** Purpose: a draft the provider left unread or unseen would count toward unread state, so it is unverified. */
    @Test
    fun isMmsDraftSaved_requiresReadAndSeen() {
        val content = twoAttachments
        val saved = savedRow(7, content)

        assertFalse(verified(listOf(saved.copy(read = 0)), content))
        assertFalse(verified(listOf(saved.copy(read = null)), content))
        assertFalse(verified(listOf(saved.copy(seen = 0)), content))
        assertFalse(verified(listOf(saved.copy(seen = null)), content))
    }

    /** Purpose: a named subscription must be the row's; an unnamed one is not compared. */
    @Test
    fun isMmsDraftSaved_requiresTheNamedSubscription() {
        val named = twoAttachments.copy(subscriptionId = 4)
        val saved = savedRow(7, named)

        assertTrue(verified(listOf(saved), named))
        assertFalse(verified(listOf(saved.copy(subscriptionId = 3)), named))
        assertFalse(verified(listOf(saved.copy(subscriptionId = null)), named))
        assertTrue(verified(listOf(saved.copy(subscriptionId = 3)), twoAttachments))
    }

    /** Purpose: the draft must be addressed to exactly the requested recipients. */
    @Test
    fun isMmsDraftSaved_requiresTheRequestedRecipients() {
        val content = twoAttachments
        val saved = savedRow(7, content)

        assertFalse(verified(listOf(saved.copy(recipients = emptyList())), content))
        assertFalse(verified(listOf(saved.copy(recipients = listOf("+15555550100"))), content))
        assertFalse(verified(listOf(saved.copy(recipients = ADDRESSES + "+15555550102")), content))
        assertFalse(verified(listOf(saved.copy(recipients = listOf("+15555550100", "+15555550102"))), content))
        // The same numbers in another order, or formatted differently, are the same recipients.
        assertTrue(verified(listOf(saved.copy(recipients = ADDRESSES.reversed())), content))
        assertTrue(verified(listOf(saved.copy(recipients = listOf("+1 (555) 555-0100", "+15555550101"))), content))
        // A letter makes an address a name, not a number: only equality matches.
        assertFalse(
            isMmsDraftSaved(
                listOf(saved.copy(recipients = listOf("a15555550100@x.org"))),
                0,
                7,
                content,
                listOf("b15555550100@x.org"),
            ),
        )
    }

    /** Purpose: an MMS part's string form never carries its text or data. */
    @Test
    fun mmsDraftPart_comparesDataByContentAndHidesIt() {
        val a = MmsDraftPart("image/jpeg", null, byteArrayOf(1, 2))

        assertEquals(a, MmsDraftPart("image/jpeg", null, byteArrayOf(1, 2)))
        assertEquals(a.hashCode(), MmsDraftPart("image/jpeg", null, byteArrayOf(1, 2)).hashCode())
        assertFalse(a == MmsDraftPart("image/jpeg", null, byteArrayOf(1, 3)))
        assertFalse(a == MmsDraftPart("image/jpeg", "t", byteArrayOf(1, 2)))
        assertEquals("MmsDraftPart(contentType=image/jpeg, text=null, bytes=2)", a.toString())
        assertEquals("MmsDraftPart(contentType=text/plain, text=6, bytes=null)", MmsDraftPart("text/plain", "secret").toString())
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

    private companion object {
        val ADDRESSES = listOf("+15555550100", "+15555550101")
    }
}
