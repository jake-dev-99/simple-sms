package io.simplezen.simple_sms.device

import io.simplezen.simple_sms.mms.codec.TEXT_STORED_CONTENT_TYPES

/** One native SMS draft row (`type = MESSAGE_TYPE_DRAFT`) as read back from the provider. */
data class SmsDraftRow(val id: Long, val body: String?, val date: Long?, val subscriptionId: Long? = null)

/**
 * What a save writes: the draft [body] and, when the caller names one, the
 * [subscriptionId] (SIM) the conversation uses. Without one the provider
 * stamps the default SMS subscription on insert and an update keeps the
 * row's own.
 */
data class SmsDraftContent(val body: String, val subscriptionId: Int? = null)

/**
 * One part of a native MMS draft: its `ct`, its `text` for a part the provider
 * keeps as text (`text/plain`, `text/html`) and, for any other part, its raw
 * [bytes] as read back from the part's data.
 */
class MmsDraftPart(val contentType: String, val text: String?, val bytes: ByteArray? = null) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is MmsDraftPart &&
                contentType == other.contentType && text == other.text && bytes.contentEqualsNullable(other.bytes)
            )

    override fun hashCode(): Int = 31 * (31 * contentType.hashCode() + (text?.hashCode() ?: 0)) + (bytes?.contentHashCode() ?: 0)

    /** The sizes only: the text and the bytes are message content. */
    override fun toString(): String = "MmsDraftPart(contentType=$contentType, text=${text?.length}, bytes=${bytes?.size})"
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) this == other else contentEquals(other)

/**
 * One native MMS draft row (`msg_box = 3`) as read back from the provider: its
 * [read] and [seen] flags, its [subscriptionId], the addresses of its `TO`
 * [recipients] and its parts in `_id` order.
 */
data class MmsDraftRow(
    val id: Long,
    val date: Long?,
    val read: Long?,
    val seen: Long?,
    val subscriptionId: Long?,
    val recipients: List<String>,
    val parts: List<MmsDraftPart>,
)

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
 * Whether the thread's drafts after an MMS save of [content] to [addresses] as
 * row [draftId] confirm it: [mmsDrafts] is exactly that row, the thread holds
 * no [smsDrafts], and the row holds the content ([isMmsDraftContentSaved]).
 * Anything else — another draft, a missing row — is unverified.
 */
fun isMmsDraftSaved(
    mmsDrafts: List<MmsDraftRow>,
    smsDrafts: Int,
    draftId: Long,
    content: MmsDraftContent,
    addresses: List<String>,
): Boolean {
    val row = mmsDrafts.singleOrNull() ?: return false
    return isOnlyDraft(mmsDrafts.map { it.id }, smsDrafts, draftId) &&
        isMmsDraftContentSaved(row, content, addresses)
}

/**
 * Whether the thread holds no draft but MMS draft [draftId]: [mmsDraftIds] is exactly
 * that row and there are no [smsDrafts].
 */
fun isOnlyDraft(mmsDraftIds: List<Long>, smsDrafts: Int, draftId: Long): Boolean =
    mmsDraftIds == listOf(draftId) && smsDrafts == 0

/**
 * Whether [row] holds a save of [content] to [addresses]: it is read and seen,
 * carries the subscription when one was named, and is addressed to exactly
 * [addresses]. Its parts, SMIL aside, are the attachments in order, each with
 * the type and the content saved, then a `text/plain` part holding exactly the
 * body when it is not blank. Anything else — a flag the provider ignored, a
 * recipient it dropped, a part it dropped, reordered or truncated, a different
 * body — is unverified. A save can check this before it removes the thread's
 * earlier drafts.
 */
fun isMmsDraftContentSaved(row: MmsDraftRow, content: MmsDraftContent, addresses: List<String>): Boolean {
    if (row.read != 1L || row.seen != 1L) return false
    if (content.subscriptionId != null && row.subscriptionId != content.subscriptionId.toLong()) return false
    if (!sameAddresses(row.recipients, addresses)) return false
    val parts = row.parts.filter { it.contentType != SMIL_CONTENT_TYPE }
    val expectedTypes = content.attachments.map { it.mimeType } +
        listOfNotNull(TEXT_CONTENT_TYPE.takeIf { !content.body.isNullOrBlank() })
    if (parts.map { it.contentType } != expectedTypes) return false
    if (!content.attachments.zip(parts).all { (attachment, part) -> holds(part, attachment) }) return false
    return content.body.isNullOrBlank() || parts.last().text == content.body
}

/** Whether [part] holds [attachment]'s content: as text where the provider keeps that type as text, else as bytes. */
private fun holds(part: MmsDraftPart, attachment: MmsDraftAttachment): Boolean =
    if (attachment.mimeType in TEXT_STORED_CONTENT_TYPES) {
        // Bytes that are not UTF-8 decode lossily, to the same text on both sides: they must
        // survive the round trip to have been kept.
        val text = String(attachment.bytes, Charsets.UTF_8)
        text.toByteArray(Charsets.UTF_8).contentEquals(attachment.bytes) && part.text == text
    } else {
        part.bytes?.contentEquals(attachment.bytes) == true
    }

/**
 * Whether [stored] names the same recipients as [requested], each once: an
 * address is the same when equal or when both carry the same digits (a phone
 * number the provider formatted differently).
 */
private fun sameAddresses(stored: List<String>, requested: List<String>): Boolean {
    val unmatched = stored.toMutableList()
    val wanted = requested.distinct()
    if (unmatched.size != wanted.size) return false
    return wanted.all { address ->
        val index = unmatched.indexOfFirst { sameAddress(it, address) }
        index >= 0 && unmatched.removeAt(index).let { true }
    }
}

private fun sameAddress(a: String, b: String): Boolean {
    if (a == b) return true
    val digitsA = a.filter(Char::isDigit)
    return digitsA.isNotEmpty() && digitsA == b.filter(Char::isDigit) && a.none(Char::isLetter) && b.none(Char::isLetter)
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
