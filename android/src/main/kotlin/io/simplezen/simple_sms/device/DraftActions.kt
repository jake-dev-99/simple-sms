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
import io.simplezen.simple_sms.mms.codec.PduHeaders
import io.simplezen.simple_sms.mms.codec.TEXT_STORED_CONTENT_TYPES
import io.simplezen.simple_sms.mms.sending.Settings
import io.simplezen.simple_sms.mms.support.SubscriptionIdChecker
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
 * and holds the saved body (and the named subscription), or for an MMS draft
 * its attachments (type and bytes), flags and recipients; a
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

        /** One MMS draft by id and box in whatever thread it landed: a save finds its own stray row this way. */
        private const val MMS_DRAFT_BY_ID_ANY_THREAD =
            "${Telephony.Mms._ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} = ?"

        private const val MMS_BY_ID = "${Telephony.Mms._ID} = ?"
        private const val PARTS_OF_MMS = "${Telephony.Mms.Part.MSG_ID} = ?"
        private const val TO_ADDRESSES = "${Telephony.Mms.Addr.TYPE} = ?"

        /**
         * The most bytes one attachment, and all of a draft's, may hold. A draft is read
         * into memory whole, written, and read back to verify it, so the size is bounded
         * before any file is read. The bounds are those of a send through the app that
         * consumes this plugin (16 MiB an attachment, 32 MiB a message).
         */
        private const val MAX_ATTACHMENT_BYTES = 16L * 1024 * 1024
        private const val MAX_DRAFT_BYTES = 32L * 1024 * 1024
        private const val READ_CHUNK_BYTES = 64 * 1024
        private const val BOUNDS_MESSAGE =
            "saveMmsDraft takes attachments of at most $MAX_ATTACHMENT_BYTES bytes each and " +
                "$MAX_DRAFT_BYTES bytes in all"

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

        private val MMS_DRAFT_ID_PROJECTION = arrayOf(Telephony.Mms._ID)
        private val MMS_DRAFT_PROJECTION = arrayOf(
            Telephony.Mms._ID,
            Telephony.Mms.DATE,
            Telephony.Mms.READ,
            Telephony.Mms.SEEN,
            Telephony.Mms.SUBSCRIPTION_ID,
        )
        private val MMS_THREAD_PROJECTION = arrayOf(Telephony.Mms.THREAD_ID)
        private val ADDRESS_PROJECTION = arrayOf(Telephony.Mms.Addr.ADDRESS)
        private val PART_PROJECTION = arrayOf(
            Telephony.Mms.Part._ID,
            Telephony.Mms.Part.CONTENT_TYPE,
            Telephony.Mms.Part.TEXT,
        )
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
     * The thread must already be listed, and every file is read, within the
     * size bounds, before anything is written. The new draft must land in
     * [threadId]: the PDU persister allocates the thread from the [addresses],
     * so a draft that resolved to another thread is removed from it and the
     * save fails. A save that fails after the draft was inserted removes that
     * row, wherever it is, and says so when it cannot.
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
            requireWithinSizeBounds(attachmentPaths)
            MmsDraftContent(body, readAttachments(attachmentPaths), subscriptionId) to threadDrafts(threadId)
        } catch (e: PermissionDeniedException) {
            result.error("NOT_DEFAULT_SMS_APP", e.message, null)
            return
        } catch (e: AttachmentTooLargeException) {
            result.error("INVALID_ARGUMENT", e.message, null)
            return
        } catch (e: OutOfMemoryError) {
            result.error(
                "DRAFT_WRITE_FAILED",
                "Not enough memory to read the attachments of thread $threadId; nothing was written",
                null,
            )
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
        } catch (e: MmsDraftWriteException) {
            // The persister cleans up its temporary parts; a row it inserted is removed here.
            val inserted = e.insertedId
            val cleaned = inserted == null || removeInsertedDraft(inserted)
            result.error(
                "DRAFT_WRITE_FAILED",
                "Failed to save the attachment draft of thread $threadId" +
                    (if (cleaned) "" else " (the unfinished draft $inserted could not be removed)") +
                    ": ${e.cause?.javaClass?.name}",
                bodySafeTrace(e),
            )
            return
        }
        /**
         * Fails the save with [code] after removing the new draft. A draft that cannot be
         * removed is a failed write whatever went wrong first, and the error says so.
         */
        fun failAfterInsert(code: String, what: String, cause: Exception? = null) {
            val cleaned = removeInsertedDraft(draftId)
            result.error(
                if (cleaned) code else "DRAFT_WRITE_FAILED",
                what + if (cleaned) "; the new draft was removed" else "; the new draft $draftId could not be removed",
                cause?.let(::bodySafeTrace),
            )
        }
        val draftThreadId = try {
            mmsThreadOf(draftId)
        } catch (e: Exception) {
            failAfterInsert(
                "VERIFY_FAILED",
                "Saved attachment draft $draftId but could not read its thread: ${e.javaClass.name}",
                e,
            )
            return
        }
        if (draftThreadId != threadId) {
            failAfterInsert(
                "DRAFT_WRITE_INCOMPLETE",
                "The recipients resolve to thread $draftThreadId, not $threadId, so attachment draft $draftId " +
                    "landed in the wrong thread",
            )
            return
        }
        // The new draft's own content is verified before anything of the thread's is removed, so
        // a draft the provider mangled costs the earlier ones nothing.
        val (written, verified) = try {
            mmsDraftRow(draftId) to
                content.copy(subscriptionId = subscriptionId.takeIf { subscriptionStoredFor(it) })
        } catch (e: Exception) {
            failAfterInsert(
                "VERIFY_FAILED",
                "Saved attachment draft $draftId of thread $threadId but could not read it back: ${e.javaClass.name}",
                e,
            )
            return
        }
        if (written == null || !isMmsDraftContentSaved(written, verified, addresses)) {
            failAfterInsert(
                "DRAFT_WRITE_INCOMPLETE",
                "Attachment draft $draftId of thread $threadId does not hold its ${content.attachments.size} " +
                    "attachments, flags, recipients and content as saved; read back " +
                    (written?.let { "${it.parts.size} parts" } ?: "no such row"),
            )
            return
        }
        try {
            earlier.mmsIds.forEach { deleteMmsDraft(it, threadId) }
            if (earlier.mmsIds.isNotEmpty() || earlier.sms.isNotEmpty()) {
                context.contentResolver.delete(conversationUri(threadId), DRAFTS, arrayOf(DRAFT_TYPE.toString()))
            }
        } catch (e: Exception) {
            failAfterInsert(
                "DRAFT_WRITE_FAILED",
                "Saved attachment draft $draftId of thread $threadId but could not remove the thread's " +
                    "earlier drafts: ${e.javaClass.name}",
                e,
            )
            return
        }
        val (readBack, stillListed) = try {
            ThreadDrafts(draftsIn(threadId), mmsDraftIds(threadId)) to threadListed(threadId)
        } catch (e: Exception) {
            failAfterInsert(
                "VERIFY_FAILED",
                "Saved attachment draft $draftId of thread $threadId but could not read it back: ${e.javaClass.name}",
                e,
            )
            return
        }
        if (!stillListed) {
            removeOrphanedDraft(
                result,
                draftId,
                threadId,
                delete = { deleteMmsDraft(draftId, threadId) },
                remains = { mmsDraftIds(threadId).contains(draftId) },
            )
            return
        }
        if (!isOnlyDraft(readBack.mmsIds, readBack.sms.size, draftId)) {
            failAfterInsert(
                "DRAFT_WRITE_INCOMPLETE",
                "Thread $threadId does not hold exactly saved attachment draft $draftId; read back MMS draft " +
                    "rows ${readBack.mmsIds} and SMS draft rows ${readBack.sms.map { it.id }}",
            )
            return
        }
        Log.d(TAG, "Saved attachment draft $draftId of thread $threadId with ${content.attachments.size} attachments")
        result.success(draftId.toString())
    }

    /**
     * Remove MMS draft [draftId], which a save inserted, from the thread it
     * landed in, and have the provider recompute that thread: a delete through
     * its conversation URI removes the thread when the draft was all it held
     * (an MMS delete through the table URI never removes an empty thread). Only
     * that row goes: the recipients may have resolved to a conversation holding
     * other messages. True once the row is gone; a failure is logged and false.
     */
    private fun removeInsertedDraft(draftId: Long): Boolean = try {
        // The thread is looked up first so it can be recomputed. A failed lookup must not keep
        // the row from being deleted, but then the thread is not recomputed: not fully removed.
        var recomputable = true
        val landedIn = try {
            mmsThreadOfOrNull(draftId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the thread of the new MMS draft $draftId: ${e.javaClass.name}")
            recomputable = false
            null
        }
        context.contentResolver.delete(
            MMS_URI,
            MMS_DRAFT_BY_ID_ANY_THREAD,
            arrayOf(draftId.toString(), MMS_DRAFT_BOX.toString()),
        )
        landedIn?.let { context.contentResolver.delete(conversationUri(it), NO_ROW, arrayOf(NO_ROW_ID)) }
        mmsThreadOfOrNull(draftId) == null && recomputable
    } catch (e: Exception) {
        Log.w(TAG, "Could not remove the new MMS draft $draftId: ${e.javaClass.name}")
        false
    }

    /**
     * Whether `PduPersister` stored [subscriptionId] on the draft it persisted: only a real
     * subscription, and only on a provider with a subscription column. Otherwise the save
     * cannot be verified against it.
     */
    private fun subscriptionStoredFor(subscriptionId: Int?): Boolean =
        subscriptionId != null && subscriptionId != Settings.DEFAULT_SUBSCRIPTION_ID &&
            SubscriptionIdChecker.getInstance(context).canUseSubscriptionId()

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
        if (readBack.mmsIds.isNotEmpty() || !isDraftSaved(readBack.sms, draftId, content)) {
            result.error(
                "DRAFT_WRITE_INCOMPLETE",
                "Thread $threadId does not hold exactly saved draft $draftId " +
                    "(subscription ${content.subscriptionId ?: "unnamed"}); read back draft rows " +
                    "${readBack.sms.map { "${it.id} (subscription ${it.subscriptionId})" }} " +
                    "and MMS draft rows ${readBack.mmsIds}",
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
        drafts.mmsIds.forEach { deleteMmsDraft(it, threadId) }
        if (drafts.mmsIds.isNotEmpty() || drafts.sms.any { it.id != draftId }) {
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
            val mmsDeleted = mmsDraftIds(threadId).sumOf { deleteMmsDraft(it, threadId) }
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
                "${remaining.sms.size} SMS and ${remaining.mmsIds.size} MMS remain",
        )
        when (classifyThreadDiscard(deletedRows, remaining.sms.size, remaining.mmsIds.size)) {
            DraftDiscardOutcome.DISCARDED -> result.success("discarded")
            DraftDiscardOutcome.ALREADY_ABSENT -> result.success("alreadyAbsent")
            DraftDiscardOutcome.INCOMPLETE -> result.error(
                "DRAFT_WRITE_INCOMPLETE",
                "Thread $threadId still holds SMS draft rows ${remaining.sms.map { it.id }} " +
                    "and MMS draft rows ${remaining.mmsIds}",
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

    /** [threadId]'s drafts in both tables, the MMS ones by id only: a cleanup must not depend on a draft's parts. */
    private fun threadDrafts(threadId: Long) = ThreadDrafts(draftsIn(threadId), mmsDraftIds(threadId))

    /** The `_id`s of [threadId]'s MMS draft rows (`msg_box = 3`). A missing or non-numeric `_id` throws. */
    private fun mmsDraftIds(threadId: Long): List<Long> =
        queryRows(
            context,
            MMS_URI,
            MMS_DRAFT_ID_PROJECTION,
            MMS_DRAFTS_IN_THREAD,
            arrayOf(threadId.toString(), MMS_DRAFT_BOX.toString()),
        ).map { row ->
            providerLong(row[Telephony.Mms._ID], Telephony.Mms._ID, MMS_URI)
                ?: throw IllegalStateException("Missing ${Telephony.Mms._ID} from $MMS_URI")
        }

    /**
     * MMS draft [draftId] as a save is verified against it: flags, subscription, `TO`
     * recipients and parts in `_id` order, with the data of every part the provider does not
     * keep as text; null when there is no such draft. Only this row is read, however many
     * drafts the thread holds. A non-numeric `_id` or a part without a content type throws
     * instead of reading as fewer rows or parts.
     */
    private fun mmsDraftRow(draftId: Long): MmsDraftRow? =
        queryRows(
            context,
            MMS_URI,
            MMS_DRAFT_PROJECTION,
            MMS_DRAFT_BY_ID_ANY_THREAD,
            arrayOf(draftId.toString(), MMS_DRAFT_BOX.toString()),
        ).singleOrNull()?.let { row ->
            val id = providerLong(row[Telephony.Mms._ID], Telephony.Mms._ID, MMS_URI)
                ?: throw IllegalStateException("Missing ${Telephony.Mms._ID} from $MMS_URI")
            MmsDraftRow(
                id = id,
                date = providerLong(row[Telephony.Mms.DATE], Telephony.Mms.DATE, MMS_URI),
                read = providerLong(row[Telephony.Mms.READ], Telephony.Mms.READ, MMS_URI),
                seen = providerLong(row[Telephony.Mms.SEEN], Telephony.Mms.SEEN, MMS_URI),
                subscriptionId = providerLong(
                    row[Telephony.Mms.SUBSCRIPTION_ID],
                    Telephony.Mms.SUBSCRIPTION_ID,
                    MMS_URI,
                ),
                recipients = mmsRecipientsOf(id),
                parts = mmsPartsOf(id),
            )
        }

    /** The addresses of MMS [mmsId]'s `TO` recipients. A row without an address throws. */
    private fun mmsRecipientsOf(mmsId: Long): List<String> =
        queryRows(
            context,
            Uri.withAppendedPath(Uri.withAppendedPath(MMS_URI, mmsId.toString()), "addr"),
            ADDRESS_PROJECTION,
            TO_ADDRESSES,
            arrayOf(PduHeaders.TO.toString()),
        ).map { row ->
            row[Telephony.Mms.Addr.ADDRESS] as? String
                ?: throw IllegalStateException("Missing ${Telephony.Mms.Addr.ADDRESS} of a recipient of MMS $mmsId")
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
            val contentType = row[Telephony.Mms.Part.CONTENT_TYPE] as? String
                ?: throw IllegalStateException("Missing ${Telephony.Mms.Part.CONTENT_TYPE} of a part of MMS $mmsId")
            if (contentType in TEXT_STORED_CONTENT_TYPES) {
                MmsDraftPart(contentType, row[Telephony.Mms.Part.TEXT] as? String)
            } else {
                val partId = providerLong(row[Telephony.Mms.Part._ID], Telephony.Mms.Part._ID, MMS_PART_URI)
                    ?: throw IllegalStateException("Missing ${Telephony.Mms.Part._ID} of a part of MMS $mmsId")
                MmsDraftPart(contentType, null, readPartData(partId))
            }
        }

    /** The data of MMS part [partId], read through its content URI (a binary stream, as the plugin's rules allow). */
    private fun readPartData(partId: Long): ByteArray =
        context.contentResolver.openInputStream(ContentUris.withAppendedId(MMS_PART_URI, partId))
            ?.use { it.readBytes() }
            ?: throw IllegalStateException("The provider opened no data for part $partId")

    /** The thread MMS row [mmsId] belongs to; a missing row or thread throws. */
    private fun mmsThreadOf(mmsId: Long): Long =
        mmsThreadOfOrNull(mmsId) ?: throw IllegalStateException("MMS $mmsId has no thread in $MMS_URI")

    /** The thread MMS row [mmsId] belongs to, or null when there is no such row. */
    private fun mmsThreadOfOrNull(mmsId: Long): Long? =
        queryRows(context, MMS_URI, MMS_THREAD_PROJECTION, MMS_BY_ID, arrayOf(mmsId.toString()))
            .singleOrNull()
            ?.let {
                providerLong(it[Telephony.Mms.THREAD_ID], Telephony.Mms.THREAD_ID, MMS_URI)
                    ?: throw IllegalStateException("MMS $mmsId has no thread in $MMS_URI")
            }

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

    /**
     * Fails with [AttachmentTooLargeException] when a file of [paths] exceeds
     * [MAX_ATTACHMENT_BYTES] or all of them together exceed [MAX_DRAFT_BYTES],
     * before any is read. A missing file has no size here: reading it fails.
     */
    private fun requireWithinSizeBounds(paths: List<String>) {
        val sizes = paths.map { File(it).length() }
        if (sizes.any { it > MAX_ATTACHMENT_BYTES } || sizes.sum() > MAX_DRAFT_BYTES) {
            throw AttachmentTooLargeException("$BOUNDS_MESSAGE; got sizes $sizes")
        }
    }

    /**
     * The files at [paths] with their raw bytes and MIME types, as a send reads
     * them; a read failure throws. The bounds are enforced against the bytes
     * read, not only the sizes the file system reports: a file can grow
     * meanwhile, and a special file reports none.
     */
    private fun readAttachments(paths: List<String>): List<MmsDraftAttachment> {
        var total = 0L
        return paths.map { path ->
            val bytes = readBounded(File(path), minOf(MAX_ATTACHMENT_BYTES, MAX_DRAFT_BYTES - total))
            total += bytes.size
            MmsDraftAttachment(path, getMimeType(path) ?: "application/octet-stream", bytes)
        }
    }

    /**
     * All of [file]'s bytes, failing with [AttachmentTooLargeException] once they exceed [limit].
     * The buffer starts at the size the file system reports (within [limit]) so a file of that
     * size is read without copies; a file that is longer, or reports none, grows it. A [limit]
     * below [READ_CHUNK_BYTES], down to zero, gets a buffer no larger than itself.
     */
    internal fun readBounded(file: File, limit: Long): ByteArray {
        val initialSize = file.length().coerceIn(minOf(READ_CHUNK_BYTES.toLong(), limit), limit)
        var buffer = ByteArray(initialSize.toInt())
        var total = 0
        file.inputStream().use { input ->
            while (true) {
                if (total == buffer.size) {
                    if (total.toLong() >= limit) {
                        if (input.read() < 0) break
                        throw AttachmentTooLargeException("$BOUNDS_MESSAGE; a file exceeded its share")
                    }
                    buffer = buffer.copyOf(minOf(buffer.size.toLong() * 2, limit).toInt())
                }
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
        }
        return if (total == buffer.size) buffer else buffer.copyOf(total)
    }

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

/** A thread's native drafts in both tables: the SMS rows, and the `_id`s of the MMS ones. */
private data class ThreadDrafts(val sms: List<SmsDraftRow>, val mmsIds: List<Long>)

/** An attachment bigger than a draft may hold; the message names the bounds and the sizes, never a path. */
private class AttachmentTooLargeException(message: String) : Exception(message)
