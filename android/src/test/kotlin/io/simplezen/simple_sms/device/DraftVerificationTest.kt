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
}
