package io.simplezen.simple_sms.device

/** One native SMS draft row (`type = MESSAGE_TYPE_DRAFT`) as read back from the provider. */
data class SmsDraftRow(val id: Long, val body: String?, val date: Long?)

/**
 * The draft row a save keeps when the thread already holds drafts: the newest
 * by `date`, then by `_id`. Null when [drafts] is empty (the save inserts).
 *
 * A thread holds one draft (AOSP `SmsProvider` replaces a thread's drafts on
 * insert), but another SMS app or an OEM provider can leave several; the save
 * updates the newest in place, so its `_id` stays stable, and deletes the rest.
 */
fun draftToKeep(drafts: List<SmsDraftRow>): SmsDraftRow? =
    drafts.maxWithOrNull(compareBy<SmsDraftRow>({ it.date ?: Long.MIN_VALUE }, { it.id }))

/**
 * Whether the [readBack] of the thread's drafts confirms a save of [body] as
 * row [draftId]: that row is the thread's only draft and holds exactly [body].
 * Anything else — an extra draft the provider kept or another app added, a
 * missing row, a different body — is unverified.
 */
fun isDraftSaved(readBack: List<SmsDraftRow>, draftId: Long, body: String): Boolean =
    readBack.singleOrNull()?.let { it.id == draftId && it.body == body } ?: false

/** Verified outcome of discarding a thread's SMS draft. */
enum class DraftDiscardOutcome {
    /** At least one draft row was deleted and none remain. */
    DISCARDED,

    /** No draft was deleted and none remain: the thread had no draft. */
    ALREADY_ABSENT,

    /** A draft row remains (the provider ignored the delete, or one was saved meanwhile). */
    INCOMPLETE,
}

/** Classify a discard from the draft rows it deleted and those its read-back still found. */
fun classifyDiscard(deletedRows: Int, remainingDrafts: Int): DraftDiscardOutcome = when {
    remainingDrafts > 0 -> DraftDiscardOutcome.INCOMPLETE
    deletedRows > 0 -> DraftDiscardOutcome.DISCARDED
    else -> DraftDiscardOutcome.ALREADY_ABSENT
}
