package io.simplezen.simple_sms.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the verified-delete classification and id validation (UNFY-244).
 *
 * The read-back is authoritative: any row left is a failed delete regardless of
 * how many rows the delete reported, so a provider that ignored the write
 * (non-default SMS app, OEM failure) is never reported as already absent.
 *
 * Plain JUnit: both functions are pure Kotlin (the handler is exercised in
 * [DestructiveActionsTest]).
 */
class DeleteVerificationTest {
    @Test
    fun rowsDeleted_noneRemaining_isDeleted() {
        assertEquals(DeleteOutcome.DELETED, classifyDelete(deletedRows = 3, remainingRows = 0))
    }

    @Test
    fun nothingDeleted_noneRemaining_isAlreadyAbsent() {
        assertEquals(DeleteOutcome.ALREADY_ABSENT, classifyDelete(deletedRows = 0, remainingRows = 0))
    }

    @Test
    fun nothingDeleted_rowsRemaining_isIncomplete() {
        // The silently ignored write: 0 rows reported while the target survives.
        assertEquals(DeleteOutcome.INCOMPLETE, classifyDelete(deletedRows = 0, remainingRows = 2))
    }

    @Test
    fun rowsDeleted_rowsRemaining_isIncomplete() {
        // A partly applied delete, or a row that landed mid-delete.
        assertEquals(DeleteOutcome.INCOMPLETE, classifyDelete(deletedRows = 4, remainingRows = 1))
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
