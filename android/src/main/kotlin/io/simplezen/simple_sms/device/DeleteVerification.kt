package io.simplezen.simple_sms.device

/**
 * Verified outcome of a native delete (UNFY-244).
 *
 * A bare delete row count cannot tell a target that was already gone from a
 * delete the provider ignored or only partly applied (Android drops provider
 * writes from a non-default SMS app as 0 rows, and OEM providers can fail the
 * same way). Every delete is therefore followed by a read-back of the target,
 * and the pair classifies into exactly one of these states.
 */
enum class DeleteOutcome {
    /** At least one row was deleted and none remain. */
    DELETED,

    /**
     * No messages were deleted and nothing remains — the target had nothing to
     * delete (a thread with no messages may still have had its empty thread
     * row pruned by the provider).
     */
    ALREADY_ABSENT,

    /**
     * Rows remain after the delete: the provider ignored or partly applied it,
     * or a row landed mid-delete. Surfaced as an error, never as success, so
     * the caller keeps its local record of the target.
     */
    INCOMPLETE,
}

/**
 * Classify a delete from the rows it removed ([deletedRows]) and the rows the
 * read-back still found for the target ([remainingRows]).
 */
fun classifyDelete(deletedRows: Int, remainingRows: Int): DeleteOutcome = when {
    remainingRows > 0 -> DeleteOutcome.INCOMPLETE
    deletedRows > 0 -> DeleteOutcome.DELETED
    else -> DeleteOutcome.ALREADY_ABSENT
}

/**
 * [raw] as a positive provider row id (`_id` / `thread_id`), or null.
 *
 * Anything else — an app-side UUID, an empty string — matches no rows, so a
 * delete by it would read back as an already-absent target.
 */
fun positiveRowId(raw: Any?): Long? = (raw as? String)?.toLongOrNull()?.takeIf { it > 0 }
