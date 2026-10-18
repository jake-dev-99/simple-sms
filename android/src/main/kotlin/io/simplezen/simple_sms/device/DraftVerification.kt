package io.simplezen.simple_sms.device

/** One native SMS draft row (`type = MESSAGE_TYPE_DRAFT`) as read back from the provider. */
data class SmsDraftRow(val id: Long, val body: String?, val date: Long?, val subscriptionId: Long? = null)

/**
 * What a save writes: the draft [body] and, when the caller names one, the
 * [subscriptionId] (SIM) the conversation uses. Without one the provider
 * stamps the default SMS subscription on insert and an update keeps the
 * row's own.
 */
data class SmsDraftContent(val body: String, val subscriptionId: Int? = null)

/** One part of a native MMS draft: its `ct` and, for a text part, its `text`. */
data class MmsDraftPart(val contentType: String, val text: String?)

/** One native MMS draft row (`msg_box = 3`) with its parts in `_id` order, as read back from the provider. */
data class MmsDraftRow(val id: Long, val date: Long?, val parts: List<MmsDraftPart>)

/**
 * One attachment of an MMS draft: its source [path] and the raw [bytes] read
 * from it, sent as [mimeType] without recompression.
 */
data class MmsDraftAttachment(val path: String, val mimeType: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is MmsDraftAttachment &&
                path == other.path && mimeType == other.mimeType && bytes.contentEquals(other.bytes)
            )

    override fun hashCode(): Int = 31 * (31 * path.hashCode() + mimeType.hashCode()) + bytes.contentHashCode()

    /** The size only: the bytes are message content. */
    override fun toString(): String = "MmsDraftAttachment(path=$path, mimeType=$mimeType, bytes=${bytes.size})"
}

/**
 * What an MMS save writes: the optional draft [body] (a `text/plain` part when
 * not blank), the [attachments] in order and, when the caller names one, the
 * [subscriptionId] (SIM) the conversation uses.
 */
data class MmsDraftContent(
    val body: String?,
    val attachments: List<MmsDraftAttachment>,
    val subscriptionId: Int? = null,
)

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
 * Whether the [readBack] of the thread's drafts confirms a save of [content]
 * as row [draftId]: that row is the thread's only draft, holds exactly the
 * body, and carries the subscription when one was named. Anything else — an
 * extra draft the provider kept or another app added, a missing row, a
 * different body or subscription — is unverified.
 */
fun isDraftSaved(readBack: List<SmsDraftRow>, draftId: Long, content: SmsDraftContent): Boolean =
    readBack.singleOrNull()?.let { row ->
        row.id == draftId &&
            row.body == content.body &&
            (content.subscriptionId == null || row.subscriptionId == content.subscriptionId.toLong())
    } ?: false

/**
 * Whether the thread's drafts after an MMS save of [content] as row [draftId]
 * confirm it: [mmsDrafts] is exactly that row and the thread holds no
 * [smsDrafts]. The row's parts, SMIL aside, are the attachments' MIME types in
 * order, then a `text/plain` part holding exactly the body when it is not
 * blank. Anything else — another draft, a missing row, a part the provider
 * dropped or reordered, a different body — is unverified.
 */
fun isMmsDraftSaved(
    mmsDrafts: List<MmsDraftRow>,
    smsDrafts: Int,
    draftId: Long,
    content: MmsDraftContent,
): Boolean {
    val row = mmsDrafts.singleOrNull() ?: return false
    if (row.id != draftId || smsDrafts != 0) return false
    val parts = row.parts.filter { it.contentType != SMIL_CONTENT_TYPE }
    val expectedTypes = content.attachments.map { it.mimeType } +
        listOfNotNull(TEXT_CONTENT_TYPE.takeIf { !content.body.isNullOrBlank() })
    if (parts.map { it.contentType } != expectedTypes) return false
    return content.body.isNullOrBlank() || parts.last().text == content.body
}

private const val SMIL_CONTENT_TYPE = "application/smil"
private const val TEXT_CONTENT_TYPE = "text/plain"

/** Verified outcome of discarding a thread's drafts. */
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

/**
 * Classify a discard of a thread's drafts from the rows it deleted across both
 * tables and the SMS and MMS draft rows its read-back still found.
 */
fun classifyThreadDiscard(deletedRows: Int, remainingSms: Int, remainingMms: Int): DraftDiscardOutcome =
    classifyDiscard(deletedRows, remainingSms + remainingMms)
