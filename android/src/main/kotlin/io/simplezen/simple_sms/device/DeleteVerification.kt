package io.simplezen.simple_sms.device

/**
 * Verified outcome of a native delete (UNFY-244).
 *
 * A bare delete row count cannot tell a target that was already gone from a
 * delete the provider ignored or only partly applied (Android drops provider
 * writes from a non-default SMS app as 0 rows, and OEM providers can fail the
 * same way). Every delete is therefore followed by a read-back of the target,
 * and the two classify into exactly one of these states.
 */
enum class DeleteOutcome {
    /** At least one row was deleted and nothing remains. */
    DELETED,

    /**
     * No messages were deleted and nothing remains — the target had nothing to
     * delete (a thread with no messages may still have had its empty thread
     * row pruned by the provider).
     */
    ALREADY_ABSENT,

    /**
     * Every row that existed before the delete is gone, but new messages
     * arrived in the thread while it ran and the thread is listed again,
     * holding only those. Thread deletes only.
     */
    NEW_MESSAGES_ARRIVED,

    /**
     * Rows that existed before the delete remain (the provider ignored or
     * partly applied it), the thread row survived with no messages, or new
     * messages reference a thread row that is no longer listed. Surfaced as
     * an error, never as success, so the caller keeps its local record of the
     * target; retrying the thread delete removes rows by `thread_id`.
     */
    INCOMPLETE,
}

/**
 * What the read-back after a delete found.
 *
 * Thread rows are compared against a snapshot of the rows the thread held
 * before the delete, so messages inserted while the delete and its sequential
 * read-backs run are told apart from rows the delete failed to remove. An SMS
 * is matched by `(_id, date)`: the `sms` table's `_id` is a plain
 * `INTEGER PRIMARY KEY`, so after the newest messages are deleted a new one can
 * reuse an `_id`, but it carries its own receive `date`. An MMS is matched by
 * `_id` alone (`pdu._id` is AUTOINCREMENT, and MMS `date` is rewritten after
 * insert).
 */
data class DeleteReadBack(
    /** Rows that existed before the delete: they survived it. */
    val survivingRows: Int,
    /** Rows that were not there before: messages that arrived mid-delete. */
    val arrivedRows: Int = 0,
    /** The thread row the conversation list reads is still listed. */
    val threadRowListed: Boolean = false,
)

/**
 * Classify a delete from the rows it removed ([deletedRows]) and what the
 * [readBack] found. Surviving rows always mean failure, whatever else arrived.
 */
fun classifyDelete(deletedRows: Int, readBack: DeleteReadBack): DeleteOutcome = when {
    readBack.survivingRows > 0 -> DeleteOutcome.INCOMPLETE
    readBack.arrivedRows > 0 && readBack.threadRowListed -> DeleteOutcome.NEW_MESSAGES_ARRIVED
    // Arrivals under an unlisted thread row, or a listed row with no messages:
    // the conversation list and the messages disagree, so nothing is verified.
    readBack.arrivedRows > 0 || readBack.threadRowListed -> DeleteOutcome.INCOMPLETE
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
