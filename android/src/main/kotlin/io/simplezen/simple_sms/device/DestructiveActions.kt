package io.simplezen.simple_sms.device

import android.app.role.RoleManager
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.Telephony
import android.util.Log
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.simplezen.simple_permissions_android.PermissionDeniedException
import io.simplezen.simple_permissions_android.PermissionGuards

/**
 * Irrecoverable thread + message deletion with verified outcomes (UNFY-244).
 *
 * Each delete succeeds with `"deleted"` or `"alreadyAbsent"` only when a
 * read-back finds nothing left (or, for a thread, `"newMessagesArrived"` when
 * only messages that arrived during the delete remain); otherwise it fails
 * with a code the Dart side mirrors as `DeleteFailureReason`. The delete plus
 * its read-backs are blocking provider I/O, so the plugin registers this
 * handler on a background task queue rather than the platform main thread,
 * and each delete holds [ProviderWriteLock] so no draft save interleaves.
 */
class DestructiveActions(val context: Context) : MethodChannel.MethodCallHandler {
    companion object {
        private const val TAG = "DestructiveActions"

        private const val SMS_IN_THREAD = "${Telephony.Sms.THREAD_ID} = ?"
        private const val MMS_IN_THREAD = "${Telephony.Mms.THREAD_ID} = ?"

        /** `Telephony.Sms.DATE` and `Telephony.Mms.DATE` are both `date`. */
        private const val DATE = Telephony.Sms.DATE
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "deleteSend", "pendingSendDeletions", "acknowledgeSendDeletion" ->
                LogicalSendDeletion(context).onMethodCall(call, result)
            "deleteThread" -> {
                val threadId = positiveRowId(call.arguments)
                if (threadId == null) {
                    result.error(
                        "INVALID_ARGUMENT",
                        "deleteThread requires a positive numeric thread id; got ${call.arguments}",
                        null,
                    )
                    return
                }
                synchronized(ProviderWriteLock) {
                    verifiedDelete(
                        result,
                        target = "thread $threadId",
                        mark = { threadSnapshot(threadId) },
                        delete = { deleteThread(threadId) },
                        readBack = { snapshot -> readBackThread(threadId, snapshot) },
                    )
                }
            }
            "deleteMessage" -> {
                val args = call.arguments as? Map<*, *>
                val messageId = positiveRowId(args?.get("messageId"))
                val table = messageTableFor(args?.get("channel") as? String)
                if (messageId == null || table == null) {
                    result.error(
                        "INVALID_ARGUMENT",
                        "deleteMessage requires a positive numeric messageId + channel " +
                            "(sms|mms); got messageId=${args?.get("messageId")} " +
                            "channel=${args?.get("channel")}",
                        null,
                    )
                    return
                }
                // The native `_id` is unique only within its own table, so the
                // channel-derived table targets the one right message (UNFY-213).
                // A single message has no later arrivals to tell apart, so it
                // needs no snapshot: any row at its URI survived.
                val messageUri = ContentUris.withAppendedId(table.contentUri(), messageId)
                // Deleting a thread's last message removes the thread, so this
                // holds the lock a draft save takes too.
                synchronized(ProviderWriteLock) {
                    verifiedDelete(
                        result,
                        target = "${table.name} message $messageId",
                        mark = {},
                        delete = { context.contentResolver.delete(messageUri, null, null) },
                        readBack = {
                            DeleteReadBack(survivingRows = queryRows(context, messageUri, arrayOf(BaseColumns._ID)).size)
                        },
                    )
                }
            }
            else -> result.notImplemented()
        }
    }

    /**
     * Take the target's pre-delete snapshot ([mark]), run [delete], read the
     * target back with [readBack], and report the verified outcome on [result].
     *
     * Only the default SMS app may write the provider: Android drops any other
     * app's delete as 0 rows, which would read back as an already-absent
     * target, so the role is asserted before anything is read or deleted.
     */
    private fun <M> verifiedDelete(
        result: MethodChannel.Result,
        target: String,
        mark: () -> M,
        delete: () -> Int,
        readBack: (M) -> DeleteReadBack,
    ) {
        val taken = try {
            PermissionGuards.requireRoleHeld(context, RoleManager.ROLE_SMS)
            mark()
        } catch (e: PermissionDeniedException) {
            result.error("NOT_DEFAULT_SMS_APP", e.message, null)
            return
        } catch (e: Exception) {
            result.error(
                "DELETE_FAILED",
                "Could not read $target before deleting; nothing was deleted: ${e.message}",
                e.stackTraceToString(),
            )
            return
        }
        val deletedRows = try {
            delete()
        } catch (e: Exception) {
            result.error("DELETE_FAILED", "Failed to delete $target: ${e.message}", e.stackTraceToString())
            return
        }
        val found = try {
            readBack(taken)
        } catch (e: Exception) {
            // The delete ran but can't be confirmed — the provider returned no
            // cursor or the read was refused.
            result.error(
                "VERIFY_FAILED",
                "Deleted $deletedRows rows for $target but could not read it back: ${e.message}",
                e.stackTraceToString(),
            )
            return
        }
        Log.d(TAG, "Deleted $target: $deletedRows rows, read back $found")
        when (classifyDelete(deletedRows, found)) {
            DeleteOutcome.DELETED -> result.success("deleted")
            DeleteOutcome.ALREADY_ABSENT -> result.success("alreadyAbsent")
            DeleteOutcome.NEW_MESSAGES_ARRIVED -> result.success("newMessagesArrived")
            DeleteOutcome.INCOMPLETE -> result.error(
                "DELETE_INCOMPLETE",
                "$target was not fully deleted: $found",
                null,
            )
        }
    }

    /** One message row's identity: its `_id` plus its `date` (see [DeleteReadBack]). */
    private data class RowKey(val id: Long, val date: Long?)

    /**
     * A thread's messages, taken before deleting it: each SMS by `(_id, date)`
     * and each MMS by `_id` alone. The `pdu` table's `_id` is AUTOINCREMENT, so
     * it is never reused — and MMS `date` can't be part of the key, because it
     * is rewritten after insert (`InboundMmsPersister` swaps in the local
     * receive time).
     */
    private data class ThreadSnapshot(val sms: Set<RowKey>, val mmsIds: Set<Long>)

    private fun threadSnapshot(threadId: Long): ThreadSnapshot {
        val args = arrayOf(threadId.toString())
        return ThreadSnapshot(
            sms = rowKeys(Telephony.Sms.CONTENT_URI, SMS_IN_THREAD, args).toSet(),
            mmsIds = rowKeys(Telephony.Mms.CONTENT_URI, MMS_IN_THREAD, args).map { it.id }.toSet(),
        )
    }

    /**
     * Delete [threadId] through the conversation URI. The provider removes the
     * thread's SMS + MMS in one call and then prunes thread rows left without
     * messages — even when no message matched, which per-table SMS/MMS deletes
     * skip (AOSP `MmsSmsProvider`), leaving an empty thread the conversation
     * list would still show.
     */
    private fun deleteThread(threadId: Long): Int = context.contentResolver.delete(
        ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, threadId),
        null,
        null,
    )

    /**
     * Read [threadId] back: its SMS and MMS rows split against [snapshot] into
     * survivors and new arrivals, plus whether the thread row the
     * conversation list reads is still listed.
     */
    private fun readBackThread(threadId: Long, snapshot: ThreadSnapshot): DeleteReadBack {
        val args = arrayOf(threadId.toString())
        val sms = rowKeys(Telephony.Sms.CONTENT_URI, SMS_IN_THREAD, args)
        val mms = rowKeys(Telephony.Mms.CONTENT_URI, MMS_IN_THREAD, args)
        val surviving = sms.count { it in snapshot.sms } + mms.count { it.id in snapshot.mmsIds }
        return DeleteReadBack(
            survivingRows = surviving,
            arrivedRows = sms.size + mms.size - surviving,
            threadRowListed = rowKeys(SIMPLE_CONVERSATIONS_URI, "${BaseColumns._ID} = ?", args).isNotEmpty(),
        )
    }

    /**
     * The `(_id, date)` of rows at [uri] matching [selection]. A non-numeric
     * value throws instead of reading as fewer rows; a null `date` stays null,
     * so it still matches only itself.
     */
    private fun rowKeys(
        uri: Uri,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
    ): List<RowKey> = queryRows(context, uri, arrayOf(BaseColumns._ID, DATE), selection, selectionArgs).map { row ->
        RowKey(
            id = providerLong(row[BaseColumns._ID], BaseColumns._ID, uri)
                ?: throw IllegalStateException("Missing ${BaseColumns._ID} from $uri"),
            date = providerLong(row[DATE], DATE, uri),
        )
    }
}
