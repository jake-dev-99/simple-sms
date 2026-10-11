package io.simplezen.simple_sms.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the verified-delete classification and id validation (UNFY-244).
 *
 * The read-back is authoritative: any row that existed before the delete and is
 * still there is a failed delete regardless of how many rows the delete
 * reported, so a provider that ignored the write (non-default SMS app, OEM
 * failure) is never reported as already absent — and messages that arrived
 * mid-delete are told apart from those survivors. An empty read-back proves
 * absence only when the target could be seen at all, so a target the
 * per-user subscription filter may be hiding is never reported as already
 * absent either.
 *
 * Plain JUnit: both functions are pure Kotlin (the handler is exercised in
 * [DestructiveActionsTest]).
 */
class DeleteVerificationTest {
    @Test
    fun rowsDeleted_nothingLeft_isDeleted() {
        assertEquals(
            DeleteOutcome.DELETED,
            classifyDelete(3, DeleteReadBack(survivingRows = 0, absenceProvable = true)),
        )
    }

    @Test
    fun nothingDeleted_nothingLeft_isAlreadyAbsent() {
        assertEquals(
            DeleteOutcome.ALREADY_ABSENT,
            classifyDelete(0, DeleteReadBack(survivingRows = 0, absenceProvable = true)),
        )
    }

    @Test
    fun nothingDeleted_rowsSurvive_isIncomplete() {
        // The silently ignored write: 0 rows reported while the target survives.
        assertEquals(
            DeleteOutcome.INCOMPLETE,
            classifyDelete(0, DeleteReadBack(survivingRows = 2, absenceProvable = true)),
        )
    }

    @Test
    fun rowsDeleted_rowsSurvive_isIncomplete() {
        // A partly applied delete.
        assertEquals(
            DeleteOutcome.INCOMPLETE,
            classifyDelete(4, DeleteReadBack(survivingRows = 1, absenceProvable = true)),
        )
    }

    @Test
    fun onlyNewArrivals_threadListed_isNewMessagesArrived() {
        // Everything that existed is gone; messages that arrived mid-delete remain.
        assertEquals(
            DeleteOutcome.NEW_MESSAGES_ARRIVED,
            classifyDelete(
                4,
                DeleteReadBack(
                    survivingRows = 0,
                    arrivedRows = 1,
                    threadRowListed = true,
                    absenceProvable = true,
                ),
            ),
        )
    }

    @Test
    fun arrivalsUnderAnUnlistedThread_isIncomplete() {
        // Orphaned messages the conversation list can't show: not a live thread.
        assertEquals(
            DeleteOutcome.INCOMPLETE,
            classifyDelete(
                4,
                DeleteReadBack(
                    survivingRows = 0,
                    arrivedRows = 1,
                    threadRowListed = false,
                    absenceProvable = true,
                ),
            ),
        )
    }

    @Test
    fun survivorsWinOverArrivals_isIncomplete() {
        // New arrivals never mask a failed delete.
        assertEquals(
            DeleteOutcome.INCOMPLETE,
            classifyDelete(
                4,
                DeleteReadBack(
                    survivingRows = 1,
                    arrivedRows = 2,
                    threadRowListed = true,
                    absenceProvable = true,
                ),
            ),
        )
    }

    @Test
    fun threadRowListedWithNoMessages_isIncomplete() {
        // Messages gone but the thread row the list reads was never pruned.
        assertEquals(
            DeleteOutcome.INCOMPLETE,
            classifyDelete(
                3,
                DeleteReadBack(
                    survivingRows = 0,
                    threadRowListed = true,
                    absenceProvable = true,
                ),
            ),
        )
    }

    @Test
    fun nothingDeleted_nothingLeft_absenceUnprovable_isAbsenceUnverified() {
        // A message invisible before the delete: gone, or hidden by the
        // provider's per-user subscription filter — the read-back can't tell.
        assertEquals(
            DeleteOutcome.ABSENCE_UNVERIFIED,
            classifyDelete(0, DeleteReadBack(survivingRows = 0, absenceProvable = false)),
        )
    }

    @Test
    fun rowsDeleted_nothingLeft_absenceUnprovable_isDeleted() {
        // Rows the delete removed were visible to it, so the delete proves itself.
        assertEquals(
            DeleteOutcome.DELETED,
            classifyDelete(1, DeleteReadBack(survivingRows = 0, absenceProvable = false)),
        )
    }

    @Test
    fun survivorsWinOverUnprovableAbsence_isIncomplete() {
        assertEquals(
            DeleteOutcome.INCOMPLETE,
            classifyDelete(0, DeleteReadBack(survivingRows = 1, absenceProvable = false)),
        )
    }

    @Test
    fun positiveNumericId_isAccepted() {
        assertEquals(42L, positiveRowId("42"))
    }

    @Test
    fun idsMatchingNoRow_areRejected() {
        assertNull(positiveRowId("0"))
        assertNull(positiveRowId("-3"))
        assertNull(positiveRowId(""))
        assertNull(positiveRowId("6f1c2e7a-uuid"))
        assertNull(positiveRowId(null))
        assertNull(positiveRowId(42))
    }
}
