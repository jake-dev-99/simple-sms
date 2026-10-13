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
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Native SMS draft writes (ADR-0016, UNFY-213): save or discard the single
 * `MESSAGE_TYPE_DRAFT` row of an existing thread through `ContentResolver`.
 *
 * Each write requires the default-SMS role and is read back: a save succeeds
 * with the draft's native `_id` only when that row is the thread's one draft
 * and holds the saved body (and the named subscription); a discard succeeds with `"discarded"` or
 * `"alreadyAbsent"` only when no draft remains. Otherwise it fails with a code
 * the Dart side mirrors as `DraftWriteFailureReason`. The writes and their
 * read-backs are blocking provider I/O, so the plugin registers this handler
 * on a background task queue; each holds [ProviderWriteLock] throughout.
 *
 * SMS text drafts only; MMS drafts (attachments) are UNFY-281.
 */
class DraftActions(val context: Context) : MethodChannel.MethodCallHandler {
    companion object {
        private const val TAG = "DraftActions"

        private val SMS_URI = MessageTable.SMS.contentUri()
        private const val DRAFT_TYPE = Telephony.Sms.MESSAGE_TYPE_DRAFT

        private const val DRAFTS_IN_THREAD =
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ?"

        /**
         * One draft row, re-qualified by thread and type: the SMS `_id` can be
         * reused once a row is deleted, so a stale id alone could hit another
         * message (UNFY-307).
         */
        private const val DRAFT_BY_ID =
            "${Telephony.Sms._ID} = ? AND ${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ?"

        /** Within a conversation URI, which supplies the thread: its drafts, and all but one of them. */
        private const val DRAFTS = "${Telephony.Sms.TYPE} = ?"
        private const val DRAFTS_EXCEPT = "${Telephony.Sms.TYPE} = ? AND ${Telephony.Sms._ID} != ?"

        private val DRAFT_PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.SUBSCRIPTION_ID,
        )
    }

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
            "discardSmsDraft" -> {
                val threadId = positiveRowId(call.arguments)
                if (threadId == null) {
                    result.error(
                        "INVALID_ARGUMENT",
                        "discardSmsDraft requires a positive numeric thread id; got ${call.arguments}",
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
     * Save [content] as [threadId]'s draft: update the thread's newest draft
     * in place and delete any others, or insert one when it has none.
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
            draftsIn(threadId)
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
            draftsIn(threadId) to threadListed(threadId)
        } catch (e: Exception) {
            result.error(
                "VERIFY_FAILED",
                "Saved draft $draftId of thread $threadId but could not read it back: ${e.javaClass.name}",
                bodySafeTrace(e),
            )
            return
        }
        if (!stillListed) {
            removeOrphanedDraft(result, draftId, threadId)
            return
        }
        if (!isDraftSaved(readBack, draftId, content)) {
            result.error(
                "DRAFT_WRITE_INCOMPLETE",
                "Thread $threadId does not hold exactly saved draft $draftId " +
                    "(subscription ${content.subscriptionId ?: "unnamed"}); read back draft rows " +
                    "${readBack.map { "${it.id} (subscription ${it.subscriptionId})" }}",
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
     * it is gone, and report the thread as not found.
     */
    private fun removeOrphanedDraft(result: MethodChannel.Result, draftId: Long, threadId: Long) {
        val remains = try {
            context.contentResolver.delete(
                SMS_URI,
                DRAFT_BY_ID,
                arrayOf(draftId.toString(), threadId.toString(), DRAFT_TYPE.toString()),
            )
            draftsIn(threadId).any { it.id == draftId }
        } catch (e: Exception) {
            result.error(
                "DRAFT_WRITE_FAILED",
                "Thread $threadId was removed during the save; its orphaned draft $draftId " +
                    "could not be removed: ${e.javaClass.name}",
                bodySafeTrace(e),
            )
            return
        }
        if (remains) {
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
     * Updates the newest draft in place, or inserts one when the thread has
     * none or that draft vanished since it was read; then, when the thread
     * held other drafts, deletes every draft but this one in a single call.
     * The read-back confirms the outcome.
     */
    private fun writeDraft(threadId: Long, content: SmsDraftContent, drafts: List<SmsDraftRow>): Long? {
        val now = System.currentTimeMillis()
        val draftId = draftToKeep(drafts)?.id?.takeIf { updateDraft(it, threadId, content, now) }
            ?: insertDraft(threadId, content, now)
            ?: return null
        if (drafts.any { it.id != draftId }) {
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
     * Delete every draft of [threadId] and confirm none remains. Deleting
     * through the conversation URI makes the provider recompute the thread's
     * snippet and date from what remains (AOSP `SmsProvider` recomputes only
     * threads that still match a table-URI delete's filter, so the discarded
     * draft would stay the conversation's preview). As with any native delete,
     * the provider then removes the thread itself when no message remains —
     * discarding the draft of a draft-only thread deletes that conversation.
     */
    private fun discardDraft(result: MethodChannel.Result, threadId: Long) {
        val deletedRows = try {
            PermissionGuards.requireRoleHeld(context, RoleManager.ROLE_SMS)
            context.contentResolver.delete(conversationUri(threadId), DRAFTS, arrayOf(DRAFT_TYPE.toString()))
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
            draftsIn(threadId)
        } catch (e: Exception) {
            result.error(
                "VERIFY_FAILED",
                "Deleted $deletedRows draft rows of thread $threadId but could not read it back: ${e.javaClass.name}",
                bodySafeTrace(e),
            )
            return
        }
        Log.d(TAG, "Discarded $deletedRows draft rows of thread $threadId, ${remaining.size} remain")
        when (classifyDiscard(deletedRows, remaining.size)) {
            DraftDiscardOutcome.DISCARDED -> result.success("discarded")
            DraftDiscardOutcome.ALREADY_ABSENT -> result.success("alreadyAbsent")
            DraftDiscardOutcome.INCOMPLETE -> result.error(
                "DRAFT_WRITE_INCOMPLETE",
                "Thread $threadId still holds draft rows ${remaining.map { it.id }}",
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

    /**
     * `content://sms/conversations/<threadId>`: a delete through it is confined
     * to the thread and recomputes the thread's snippet and date.
     */
    private fun conversationUri(threadId: Long): Uri =
        ContentUris.withAppendedId(Telephony.Sms.Conversations.CONTENT_URI, threadId)
}
