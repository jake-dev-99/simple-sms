package io.simplezen.simple_sms.device

import android.app.role.RoleManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.simplezen.simple_permissions_android.PermissionDeniedException
import io.simplezen.simple_permissions_android.PermissionGuards
import io.simplezen.simple_sms.messaging.getMimeType
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Native draft writes (ADR-0016, UNFY-213, UNFY-281): save or discard the one
 * draft of an existing thread through `ContentResolver`. A thread holds one
 * draft across both tables: an SMS text draft (`MESSAGE_TYPE_DRAFT`) or an MMS
 * draft with attachments (`msg_box = 3`). Every save removes the thread's
 * drafts of the other kind, and a discard removes both.
 *
 * Each write requires the default-SMS role and is read back: a save succeeds
 * with the draft's native `_id` only when that row is the thread's one draft
 * and holds the saved body (and the named subscription, or the attachments); a
 * discard succeeds with `"discarded"` or `"alreadyAbsent"` only when no draft
 * remains in either table. Otherwise it fails with a code the Dart side mirrors
 * as `DraftWriteFailureReason`. The writes and their read-backs are blocking
 * provider I/O, so the plugin registers this handler on a background task
 * queue; each holds [ProviderWriteLock] throughout.
 *
 * Discarding the draft of a draft-only thread deletes the conversation, for an
 * MMS draft as for an SMS one.
 */
class DraftActions(val context: Context) : MethodChannel.MethodCallHandler {
    companion object {
        private const val TAG = "DraftActions"

        private val SMS_URI = MessageTable.SMS.contentUri()
        private const val DRAFT_TYPE = Telephony.Sms.MESSAGE_TYPE_DRAFT

        private val MMS_URI = MessageTable.MMS.contentUri()
        private val MMS_PART_URI = Uri.withAppendedPath(MMS_URI, "part")
        private const val MMS_DRAFT_BOX = Telephony.Mms.MESSAGE_BOX_DRAFTS

        /** The most attachments an MMS draft carries, as many as a send (`AttachmentProtocol.maxAttachments`). */
        private const val MAX_ATTACHMENTS = 8

        private const val DRAFTS_IN_THREAD =
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ?"

        /**
         * One draft row, re-qualified by thread and type: the SMS `_id` can be
         * reused once a row is deleted, so a stale id alone could hit another
         * message (UNFY-307).
         */
        private const val DRAFT_BY_ID =
            "${Telephony.Sms._ID} = ? AND ${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ?"

        private const val MMS_DRAFTS_IN_THREAD =
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} = ?"

        /** One MMS draft, re-qualified by thread and box for the same reason as [DRAFT_BY_ID]. */
        private const val MMS_DRAFT_BY_ID =
            "${Telephony.Mms._ID} = ? AND ${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} = ?"

        private const val MMS_BY_ID = "${Telephony.Mms._ID} = ?"
        private const val PARTS_OF_MMS = "${Telephony.Mms.Part.MSG_ID} = ?"

        /**
         * Within a conversation URI, a selection no row matches. The delete still
         * makes the provider recompute the thread, which removes it when empty.
         */
        private const val NO_ROW = "${Telephony.Sms._ID} = ?"
        private const val NO_ROW_ID = "-1"

        /** Within a conversation URI, which supplies the thread: its drafts, and all but one of them. */
        private const val DRAFTS = "${Telephony.Sms.TYPE} = ?"
        private const val DRAFTS_EXCEPT = "${Telephony.Sms.TYPE} = ? AND ${Telephony.Sms._ID} != ?"

        private val DRAFT_PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.SUBSCRIPTION_ID,
        )

        private val MMS_DRAFT_PROJECTION = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE)
        private val MMS_THREAD_PROJECTION = arrayOf(Telephony.Mms.THREAD_ID)
        private val PART_PROJECTION = arrayOf(Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT)
    }

    private val mmsWriter = MmsDraftWriter(context)

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "saveSmsDraft" -> {
                val args = call.arguments as? Map<*, *>
                val threadId = positiveRowId(args?.get("threadId"))
                val rawBody = args?.get("body")
                val body = (rawBody as? String)?.takeIf { it.isNotBlank() }
                val rawSubscriptionId = args?.get("subscriptionId")
                if (threadId == null || body == null || (rawSubscriptionId != null && rawSubscriptionId !is Int)) {
                    // The body is message content: name its state, never log it.
                    val bodyState = when {
                        rawBody !is String -> "missing"
                        rawBody.isBlank() -> "blank"
                        else -> "present"
                    }
                    result.error(
                        "INVALID_ARGUMENT",
                        "saveSmsDraft requires a positive numeric threadId, a non-blank body " +
                            "and an int subscriptionId when given; got threadId=${args?.get("threadId")} " +
                            "body=$bodyState subscriptionId=$rawSubscriptionId",
                        null,
                    )
                    return
                }
                synchronized(ProviderWriteLock) {
                    saveDraft(result, threadId, SmsDraftContent(body, rawSubscriptionId as Int?))
                }
            }
            "saveMmsDraft" -> {
                val args = call.arguments as? Map<*, *>
                val threadId = positiveRowId(args?.get("threadId"))
                val addresses = nonBlankStrings(args?.get("addresses"))
                val attachmentPaths = nonBlankStrings(args?.get("attachmentPaths"))
                val rawBody = args?.get("body")
                val rawSubscriptionId = args?.get("subscriptionId")
                if (threadId == null || addresses == null || attachmentPaths == null ||
                    attachmentPaths.size > MAX_ATTACHMENTS ||
                    (rawBody != null && rawBody !is String) ||
                    (rawSubscriptionId != null && rawSubscriptionId !is Int)
                ) {
                    // The body is message content, the addresses and paths are personal: name their state, never log them.
                    val bodyState = when {
                        rawBody == null -> "absent"
                        rawBody !is String -> "invalid"
                        rawBody.isBlank() -> "blank"
                        else -> "present"
                    }
                    result.error(
                        "INVALID_ARGUMENT",
                        "saveMmsDraft requires a positive numeric threadId, a non-empty list of addresses, " +
                            "1 to $MAX_ATTACHMENTS attachmentPaths, a string body when given and an int " +
                            "subscriptionId when given; got threadId=${args?.get("threadId")} " +
                            "addresses=${addresses?.size ?: "invalid"} attachmentPaths=${attachmentPaths?.size ?: "invalid"} " +
                            "body=$bodyState subscriptionId=$rawSubscriptionId",
                        null,
                    )
                    return
                }
                synchronized(ProviderWriteLock) {
                    saveMmsDraft(
                        result,
                        threadId,
                        addresses,
                        (rawBody as String?)?.takeIf { it.isNotBlank() },
                        attachmentPaths,
                        rawSubscriptionId as Int?,
                    )
                }
            }
            "discardDraft" -> {
                val threadId = positiveRowId(call.arguments)
                if (threadId == null) {
                    result.error(
                        "INVALID_ARGUMENT",
                        "discardDraft requires a positive numeric thread id; got ${call.arguments}",
                        null,
                    )
                    return
                }
                synchronized(ProviderWriteLock) { discardDraft(result, threadId) }
            }
            else -> result.notImplemented()
        }
    }

    /**
     * Save the attachments at [attachmentPaths] and [body] as [threadId]'s one
     * draft: persist a new MMS draft, then delete the thread's earlier MMS
     * drafts and its SMS drafts.
     *
     * The thread must already be listed, and every file is read before anything
     * is written. The new draft must land in [threadId]: the PDU persister
     * allocates the thread from the [addresses], so a draft that resolved to
     * another thread is removed from it and the save fails.
     */
    private fun saveMmsDraft(
        result: MethodChannel.Result,
        threadId: Long,
        addresses: List<String>,
        body: String?,
        attachmentPaths: List<String>,
        subscriptionId: Int?,
    ) {
        val (content, earlier) = try {
            PermissionGuards.requireRoleHeld(context, RoleManager.ROLE_SMS)
            if (!threadListed(threadId)) {
                result.error("THREAD_NOT_FOUND", "Thread $threadId is not on the device; no draft was saved", null)
                return
            }
            MmsDraftContent(body, attachmentPaths.map(::readAttachment), subscriptionId) to threadDrafts(threadId)
        } catch (e: PermissionDeniedException) {
            result.error("NOT_DEFAULT_SMS_APP", e.message, null)
            return
        } catch (e: Exception) {
            result.error(
                "DRAFT_WRITE_FAILED",
                "Could not read thread $threadId or an attachment before saving its attachment draft; " +
                    "nothing was written: ${e.javaClass.name}",
                bodySafeTrace(e),
            )
            return
        }
        val draftId = try {
            mmsWriter.persist(addresses, content)
        } catch (e: Exception) {
            // The row may have been inserted before the failure: no unread,
            // unfinished draft is left beside the earlier ones, which stay.
            val cleaned = removeUnfinishedMmsDrafts(threadId, keeping = earlier.mms.map { it.id }.toSet())
            result.error(
                "DRAFT_WRITE_FAILED",
                "Failed to save the attachment draft of thread $threadId" +
                    (if (cleaned) "" else " (an unfinished draft could not be removed)") +
                    ": ${e.javaClass.name}",
                bodySafeTrace(e),
            )
            return
        }
        val draftThreadId = try {
            mmsThreadOf(draftId)
        } catch (e: Exception) {
            result.error(
                "VERIFY_FAILED",
                "Saved attachment draft $draftId but could not read its thread: ${e.javaClass.name}",
                bodySafeTrace(e),
            )
            return
        }
        if (draftThreadId != threadId) {
            removeStrayMmsDraft(result, draftId, draftThreadId, threadId)
            return
        }
        try {
            earlier.mms.forEach { deleteMmsDraft(it.id, threadId) }
            if (earlier.mms.isNotEmpty() || earlier.sms.isNotEmpty()) {
                context.contentResolver.delete(conversationUri(threadId), DRAFTS, arrayOf(DRAFT_TYPE.toString()))
            }
        } catch (e: Exception) {
            result.error(
                "DRAFT_WRITE_FAILED",
                "Saved attachment draft $draftId of thread $threadId but could not remove the thread's " +
                    "earlier drafts: ${e.javaClass.name}",
                bodySafeTrace(e),
            )
            return
        }
        val (readBack, stillListed) = try {
            threadDrafts(threadId) to threadListed(threadId)
        } catch (e: Exception) {
            result.error(
                "VERIFY_FAILED",
                "Saved attachment draft $draftId of thread $threadId but could not read it back: ${e.javaClass.name}",
                bodySafeTrace(e),
            )
            return
        }
        if (!stillListed) {
            removeOrphanedDraft(
                result,
                draftId,
                threadId,
                delete = { deleteMmsDraft(draftId, threadId) },
                remains = { mmsDraftsIn(threadId).any { it.id == draftId } },
            )
            return
        }
        if (!isMmsDraftSaved(readBack.mms, readBack.sms.size, draftId, content)) {
            result.error(
                "DRAFT_WRITE_INCOMPLETE",
                "Thread $threadId does not hold exactly saved attachment draft $draftId with its " +
                    "${content.attachments.size} attachments; read back MMS draft rows " +
                    "${readBack.mms.map { "${it.id} (${it.parts.size} parts)" }} and SMS draft rows " +
                    "${readBack.sms.map { it.id }}",
                null,
            )
            return
        }
        Log.d(TAG, "Saved attachment draft $draftId of thread $threadId with ${content.attachments.size} attachments")
        result.success(draftId.toString())
    }

    /**
     * Delete the MMS drafts of [threadId] other than [keeping], which a save
     * that failed part-way may have left; true when none remains.
     */
    private fun removeUnfinishedMmsDrafts(threadId: Long, keeping: Set<Long>): Boolean = try {
        mmsDraftsIn(threadId).filter { it.id !in keeping }.forEach { deleteMmsDraft(it.id, threadId) }
        mmsDraftsIn(threadId).all { it.id in keeping }
    } catch (e: Exception) {
        Log.w(TAG, "Could not remove the unfinished MMS draft of thread $threadId: ${e.javaClass.name}")
        false
    }

    /**
     * Remove draft [draftId], which the persister put in thread [strayThreadId]
     * instead of [threadId], and fail the save. Only that row goes: the
     * recipients may have resolved to a conversation holding other messages. A
     * delete through the thread's conversation URI then makes the provider
     * recompute it, which removes the thread when the draft was all it held (an
     * MMS delete through the table URI never removes an empty thread).
     */
    private fun removeStrayMmsDraft(result: MethodChannel.Result, draftId: Long, strayThreadId: Long, threadId: Long) {
        val remains = try {
            deleteMmsDraft(draftId, strayThreadId)
            context.contentResolver.delete(conversationUri(strayThreadId), NO_ROW, arrayOf(NO_ROW_ID))
            mmsDraftsIn(strayThreadId).any { it.id == draftId }
        } catch (e: Exception) {
            result.error(
                "DRAFT_WRITE_FAILED",
                "The attachment draft $draftId landed in thread $strayThreadId, not $threadId, and could not " +
                    "be removed from it: ${e.javaClass.name}",
                bodySafeTrace(e),
            )
            return
        }
        if (remains) {
            result.error(
                "DRAFT_WRITE_FAILED",
                "The attachment draft $draftId landed in thread $strayThreadId, not $threadId, and remains in it",
                null,
            )
            return
        }
        result.error(
            "DRAFT_WRITE_INCOMPLETE",
            "The recipients resolve to thread $strayThreadId, not $threadId; the attachment draft was removed from it",
            null,
        )
    }

    /**
     * Save [content] as [threadId]'s draft: update the thread's newest SMS draft
     * in place and delete any others, or insert one when it has none, then
     * delete the thread's MMS drafts: the thread holds one draft.
     *
     * The thread must already be listed — a draft row whose `thread_id` names
     * no thread would be orphaned outside every conversation.
     */
    private fun saveDraft(result: MethodChannel.Result, threadId: Long, content: SmsDraftContent) {
        val drafts = try {
            PermissionGuards.requireRoleHeld(context, RoleManager.ROLE_SMS)
            if (!threadListed(threadId)) {
                result.error("THREAD_NOT_FOUND", "Thread $threadId is not on the device; no draft was saved", null)
                return
            }
            threadDrafts(threadId)
        } catch (e: PermissionDeniedException) {
            result.error("NOT_DEFAULT_SMS_APP", e.message, null)
            return
        } catch (e: Exception) {
            result.error(
                "DRAFT_WRITE_FAILED",
                "Could not read thread $threadId before saving its draft; nothing was written: ${e.javaClass.name}",
                bodySafeTrace(e),
            )
            return
        }
        val draftId = try {
            writeDraft(threadId, content, drafts)
        } catch (e: Exception) {
            result.error(
                "DRAFT_WRITE_FAILED",
                "Failed to save the draft of thread $threadId: ${e.javaClass.name}",
                bodySafeTrace(e),
            )
            return
        }
        if (draftId == null) {
            result.error(
                "DRAFT_WRITE_FAILED",
                "The provider returned no draft row id for thread $threadId",
                null,
            )
            return
        }
        // The thread is re-checked too: a thread deleted while the save ran
        // (the destructive channel runs on its own queue) would leave the
        // draft outside every conversation.
        val (readBack, stillListed) = try {
            threadDrafts(threadId) to threadListed(threadId)
        } catch (e: Exception) {
            result.error(
                "VERIFY_FAILED",
                "Saved draft $draftId of thread $threadId but could not read it back: ${e.javaClass.name}",
                bodySafeTrace(e),
            )
            return
        }
        if (!stillListed) {
            removeOrphanedDraft(
                result,
                draftId,
                threadId,
                delete = { deleteSmsDraft(draftId, threadId) },
                remains = { draftsIn(threadId).any { it.id == draftId } },
            )
            return
        }
        if (readBack.mms.isNotEmpty() || !isDraftSaved(readBack.sms, draftId, content)) {
            result.error(
                "DRAFT_WRITE_INCOMPLETE",
                "Thread $threadId does not hold exactly saved draft $draftId " +
                    "(subscription ${content.subscriptionId ?: "unnamed"}); read back draft rows " +
                    "${readBack.sms.map { "${it.id} (subscription ${it.subscriptionId})" }} " +
                    "and MMS draft rows ${readBack.mms.map { it.id }}",
                null,
            )
            return
        }
        Log.d(TAG, "Saved draft $draftId of thread $threadId")
        result.success(draftId.toString())
    }

    /**
     * Delete draft [draftId], left behind by a save whose thread [threadId]
     * was removed meanwhile by a writer outside [ProviderWriteLock], confirm
     * it is gone, and report the thread as not found. [delete] removes the
     * draft row from its table and [remains] reads whether it is still there.
     */
    private fun removeOrphanedDraft(
        result: MethodChannel.Result,
        draftId: Long,
        threadId: Long,
        delete: () -> Unit,
        remains: () -> Boolean,
    ) {
        val stillThere = try {
            delete()
            remains()
        } catch (e: Exception) {
            result.error(
                "DRAFT_WRITE_FAILED",
                "Thread $threadId was removed during the save; its orphaned draft $draftId " +
                    "could not be removed: ${e.javaClass.name}",
                bodySafeTrace(e),
            )
            return
        }
        if (stillThere) {
            result.error(
                "DRAFT_WRITE_FAILED",
                "Thread $threadId was removed during the save; its orphaned draft $draftId remains",
                null,
            )
            return
        }
        result.error("THREAD_NOT_FOUND", "Thread $threadId was removed during the save; the draft was removed", null)
    }

    /**
     * Write [content] as [threadId]'s draft given its current [drafts]; returns
     * the draft's `_id`, or null when the provider returned no row id.
     *
     * Updates the newest SMS draft in place, or inserts one when the thread
     * has none or that draft vanished since it was read; then deletes the
     * thread's MMS drafts one by one and, when the thread held other drafts,
     * every SMS draft but this one in a single conversation-URI call, which
     * runs after the MMS deletes so the provider recomputes the thread's
     * snippet from what remains. The read-back confirms the outcome.
     */
    private fun writeDraft(threadId: Long, content: SmsDraftContent, drafts: ThreadDrafts): Long? {
        val now = System.currentTimeMillis()
        val draftId = draftToKeep(drafts.sms)?.id?.takeIf { updateDraft(it, threadId, content, now) }
            ?: insertDraft(threadId, content, now)
            ?: return null
        drafts.mms.forEach { deleteMmsDraft(it.id, threadId) }
        if (drafts.mms.isNotEmpty() || drafts.sms.any { it.id != draftId }) {
            context.contentResolver.delete(
                conversationUri(threadId),
                DRAFTS_EXCEPT,
                arrayOf(DRAFT_TYPE.toString(), draftId.toString()),
            )
        }
        return draftId
    }

    /** Update draft [draftId] of [threadId] in place; false when no such draft remains. */
    private fun updateDraft(draftId: Long, threadId: Long, content: SmsDraftContent, now: Long): Boolean =
        context.contentResolver.update(
            SMS_URI,
            draftValues(content, now),
            DRAFT_BY_ID,
            arrayOf(draftId.toString(), threadId.toString(), DRAFT_TYPE.toString()),
        ) > 0

    /** Insert [threadId]'s draft; returns its `_id`, or null when the provider returned no row URI. */
    private fun insertDraft(threadId: Long, content: SmsDraftContent, now: Long): Long? {
        val uri = context.contentResolver.insert(
            SMS_URI,
            draftValues(content, now).apply {
                put(Telephony.Sms.THREAD_ID, threadId)
                put(Telephony.Sms.TYPE, DRAFT_TYPE)
            },
        ) ?: return null
        return ContentUris.parseId(uri).takeIf { it > 0 }
    }

    /**
     * A draft's body, date and named subscription, and its read + seen flags:
     * a draft is never unread or unseen, so it must not count toward the
     * conversation's unread state or notifications — including a kept draft
     * another app left unread.
     */
    private fun draftValues(content: SmsDraftContent, now: Long) = ContentValues().apply {
        put(Telephony.Sms.BODY, content.body)
        put(Telephony.Sms.DATE, now)
        put(Telephony.Sms.READ, 1)
        put(Telephony.Sms.SEEN, 1)
        content.subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
    }

    /**
     * Delete every draft of [threadId], SMS and MMS, and confirm none remains.
     * The MMS drafts go first, one by one. The conversation-URI delete of the
     * SMS drafts always runs, even for a thread without one: it makes the
     * provider recompute the thread's snippet and date from what remains (AOSP
     * `SmsProvider` recomputes only threads that still match a table-URI
     * delete's filter, so the discarded draft would stay the conversation's
     * preview). As with any native delete, the provider then removes the thread
     * itself when no message remains — discarding the draft of a draft-only
     * thread deletes that conversation.
     */
    private fun discardDraft(result: MethodChannel.Result, threadId: Long) {
        val deletedRows = try {
            PermissionGuards.requireRoleHeld(context, RoleManager.ROLE_SMS)
            val mmsDeleted = mmsDraftsIn(threadId).sumOf { deleteMmsDraft(it.id, threadId) }
            mmsDeleted + context.contentResolver.delete(conversationUri(threadId), DRAFTS, arrayOf(DRAFT_TYPE.toString()))
        } catch (e: PermissionDeniedException) {
            result.error("NOT_DEFAULT_SMS_APP", e.message, null)
            return
        } catch (e: Exception) {
            result.error(
                "DRAFT_WRITE_FAILED",
                "Failed to discard the draft of thread $threadId: ${e.javaClass.name}",
                bodySafeTrace(e),
            )
            return
        }
        val remaining = try {
            threadDrafts(threadId)
        } catch (e: Exception) {
            result.error(
                "VERIFY_FAILED",
                "Deleted $deletedRows draft rows of thread $threadId but could not read it back: ${e.javaClass.name}",
                bodySafeTrace(e),
            )
            return
        }
        Log.d(
            TAG,
            "Discarded $deletedRows draft rows of thread $threadId, " +
                "${remaining.sms.size} SMS and ${remaining.mms.size} MMS remain",
        )
        when (classifyThreadDiscard(deletedRows, remaining.sms.size, remaining.mms.size)) {
            DraftDiscardOutcome.DISCARDED -> result.success("discarded")
            DraftDiscardOutcome.ALREADY_ABSENT -> result.success("alreadyAbsent")
            DraftDiscardOutcome.INCOMPLETE -> result.error(
                "DRAFT_WRITE_INCOMPLETE",
                "Thread $threadId still holds SMS draft rows ${remaining.sms.map { it.id }} " +
                    "and MMS draft rows ${remaining.mms.map { it.id }}",
                null,
            )
        }
    }

    /**
     * [e]'s exception types and stack frames, down its cause chain, without
     * messages: a provider may echo the submitted values, so the draft body,
     * in its exception text. Each cause is visited once, so a cyclic chain ends.
     */
    private fun bodySafeTrace(e: Throwable): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        return generateSequence(e) { it.cause }.takeWhile { seen.add(it) }.joinToString("\nCaused by: ") { cause ->
            (listOf(cause.javaClass.name) + cause.stackTrace.map { "\tat $it" }).joinToString("\n")
        }
    }

    /** Whether the conversation list lists [threadId]. */
    private fun threadListed(threadId: Long): Boolean = queryRows(
        context,
        SIMPLE_CONVERSATIONS_URI,
        arrayOf(Telephony.Threads._ID),
        "${Telephony.Threads._ID} = ?",
        arrayOf(threadId.toString()),
    ).isNotEmpty()

    /** [threadId]'s SMS draft rows. A missing or non-numeric `_id` throws instead of reading as fewer rows. */
    private fun draftsIn(threadId: Long): List<SmsDraftRow> =
        queryRows(context, SMS_URI, DRAFT_PROJECTION, DRAFTS_IN_THREAD, draftsInThreadArgs(threadId)).map { row ->
            SmsDraftRow(
                id = providerLong(row[Telephony.Sms._ID], Telephony.Sms._ID, SMS_URI)
                    ?: throw IllegalStateException("Missing ${Telephony.Sms._ID} from $SMS_URI"),
                body = row[Telephony.Sms.BODY] as? String,
                date = providerLong(row[Telephony.Sms.DATE], Telephony.Sms.DATE, SMS_URI),
                subscriptionId = providerLong(
                    row[Telephony.Sms.SUBSCRIPTION_ID],
                    Telephony.Sms.SUBSCRIPTION_ID,
                    SMS_URI,
                ),
            )
        }

    private fun draftsInThreadArgs(threadId: Long) = arrayOf(threadId.toString(), DRAFT_TYPE.toString())

    /** [threadId]'s drafts in both tables. */
    private fun threadDrafts(threadId: Long) = ThreadDrafts(draftsIn(threadId), mmsDraftsIn(threadId))

    /**
     * [threadId]'s MMS draft rows (`msg_box = 3`) with their parts, in `_id`
     * order. A missing or non-numeric `_id` or a part without a content type
     * throws instead of reading as fewer rows or parts.
     */
    private fun mmsDraftsIn(threadId: Long): List<MmsDraftRow> =
        queryRows(
            context,
            MMS_URI,
            MMS_DRAFT_PROJECTION,
            MMS_DRAFTS_IN_THREAD,
            arrayOf(threadId.toString(), MMS_DRAFT_BOX.toString()),
        ).map { row ->
            val id = providerLong(row[Telephony.Mms._ID], Telephony.Mms._ID, MMS_URI)
                ?: throw IllegalStateException("Missing ${Telephony.Mms._ID} from $MMS_URI")
            MmsDraftRow(id, providerLong(row[Telephony.Mms.DATE], Telephony.Mms.DATE, MMS_URI), mmsPartsOf(id))
        }

    private fun mmsPartsOf(mmsId: Long): List<MmsDraftPart> =
        queryRows(
            context,
            MMS_PART_URI,
            PART_PROJECTION,
            PARTS_OF_MMS,
            arrayOf(mmsId.toString()),
            sortOrder = "${Telephony.Mms.Part._ID} ASC",
        ).map { row ->
            MmsDraftPart(
                contentType = row[Telephony.Mms.Part.CONTENT_TYPE] as? String
                    ?: throw IllegalStateException("Missing ${Telephony.Mms.Part.CONTENT_TYPE} of a part of MMS $mmsId"),
                text = row[Telephony.Mms.Part.TEXT] as? String,
            )
        }

    /** The thread MMS row [mmsId] belongs to; a missing row or thread throws. */
    private fun mmsThreadOf(mmsId: Long): Long =
        queryRows(context, MMS_URI, MMS_THREAD_PROJECTION, MMS_BY_ID, arrayOf(mmsId.toString()))
            .singleOrNull()
            ?.let { providerLong(it[Telephony.Mms.THREAD_ID], Telephony.Mms.THREAD_ID, MMS_URI) }
            ?: throw IllegalStateException("MMS $mmsId has no thread in $MMS_URI")

    /** Delete SMS draft [draftId] of [threadId]; the number of rows deleted. */
    private fun deleteSmsDraft(draftId: Long, threadId: Long): Int =
        context.contentResolver.delete(
            SMS_URI,
            DRAFT_BY_ID,
            arrayOf(draftId.toString(), threadId.toString(), DRAFT_TYPE.toString()),
        )

    /** Delete MMS draft [draftId] of [threadId]; the number of rows deleted. */
    private fun deleteMmsDraft(draftId: Long, threadId: Long): Int =
        context.contentResolver.delete(
            MMS_URI,
            MMS_DRAFT_BY_ID,
            arrayOf(draftId.toString(), threadId.toString(), MMS_DRAFT_BOX.toString()),
        )

    /** The file at [path] with its raw bytes and MIME type, as a send reads it; a read failure throws. */
    private fun readAttachment(path: String) = MmsDraftAttachment(
        path = path,
        mimeType = getMimeType(path) ?: "application/octet-stream",
        bytes = File(path).readBytes(),
    )

    /** [raw] as a non-empty list of non-blank strings, or null when it is anything else. */
    private fun nonBlankStrings(raw: Any?): List<String>? =
        (raw as? List<*>)?.takeIf { list -> list.isNotEmpty() && list.all { it is String && it.isNotBlank() } }
            ?.map { it as String }

    /**
     * `content://sms/conversations/<threadId>`: a delete through it is confined
     * to the thread and recomputes the thread's snippet and date.
     */
    private fun conversationUri(threadId: Long): Uri =
        ContentUris.withAppendedId(Telephony.Sms.Conversations.CONTENT_URI, threadId)
}

/** A thread's native drafts in both tables. */
private data class ThreadDrafts(val sms: List<SmsDraftRow>, val mms: List<MmsDraftRow>)
