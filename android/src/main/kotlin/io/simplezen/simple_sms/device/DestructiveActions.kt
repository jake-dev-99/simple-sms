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
import io.simplezen.simple_query.ContentQuery

/**
 * Irrecoverable thread + message deletion with verified outcomes (UNFY-244).
 *
 * Each delete succeeds with `"deleted"` or `"alreadyAbsent"` only when a
 * read-back finds nothing left; otherwise it fails with a code the Dart side
 * mirrors as `DeleteFailureReason`. The delete plus its read-backs are blocking
 * provider I/O, so the plugin registers this handler on a background task
 * queue rather than the platform main thread.
 */
class DestructiveActions(val context: Context) : MethodChannel.MethodCallHandler {
    companion object {
        private const val TAG = "DestructiveActions"

        /** The `threads` table view the conversation list reads. */
        private val SIMPLE_CONVERSATIONS_URI: Uri = Telephony.Threads.CONTENT_URI
            .buildUpon().appendQueryParameter("simple", "true").build()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
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
                verifiedDelete(
                    result,
                    target = "thread $threadId",
                    delete = { deleteThread(threadId) },
                    countRemaining = { countThreadRows(threadId) },
                )
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
                val messageUri = ContentUris.withAppendedId(table.contentUri(), messageId)
                verifiedDelete(
                    result,
                    target = "${table.name} message $messageId",
                    delete = { context.contentResolver.delete(messageUri, null, null) },
                    countRemaining = { countRows(messageUri) },
                )
            }
            else -> result.notImplemented()
        }
    }

    /**
     * Run [delete], read the target back with [countRemaining], and report the
     * verified outcome on [result].
     *
     * Only the default SMS app may write the provider: Android drops any other
     * app's delete as 0 rows, which would read back as an already-absent
     * target, so the role is asserted before anything is deleted.
     */
    private fun verifiedDelete(
        result: MethodChannel.Result,
        target: String,
        delete: () -> Int,
        countRemaining: () -> Int,
    ) {
        val deletedRows = try {
            PermissionGuards.requireRoleHeld(context, RoleManager.ROLE_SMS)
            delete()
        } catch (e: PermissionDeniedException) {
            result.error("NOT_DEFAULT_SMS_APP", e.message, null)
            return
        } catch (e: Exception) {
            result.error("DELETE_FAILED", "Failed to delete $target: ${e.message}", e.stackTraceToString())
            return
        }
        val remainingRows = try {
            countRemaining()
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
        Log.d(TAG, "Deleted $target: $deletedRows rows, $remainingRows remaining")
        when (classifyDelete(deletedRows, remainingRows)) {
            DeleteOutcome.DELETED -> result.success("deleted")
            DeleteOutcome.ALREADY_ABSENT -> result.success("alreadyAbsent")
            DeleteOutcome.INCOMPLETE -> result.error(
                "DELETE_INCOMPLETE",
                "$target still has $remainingRows rows after delete",
                null,
            )
        }
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

    /** SMS + MMS rows, plus the thread row the conversation list reads, still in [threadId]. */
    private fun countThreadRows(threadId: Long): Int {
        val args = arrayOf(threadId.toString())
        return countRows(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID} = ?", args) +
            countRows(Telephony.Mms.CONTENT_URI, "${Telephony.Mms.THREAD_ID} = ?", args) +
            countRows(SIMPLE_CONVERSATIONS_URI, "${BaseColumns._ID} = ?", args)
    }

    /**
     * Rows at [uri] matching [selection], read via simple_query's `queryOrNull`.
     * A null cursor (provider unreachable) throws instead of reading as zero
     * rows, so the handler reports the delete as unverified.
     */
    private fun countRows(
        uri: Uri,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
    ): Int = ContentQuery.queryOrNull(
        context,
        uri.toString(),
        projection = arrayOf(BaseColumns._ID),
        selection = selection,
        selectionArgs = selectionArgs,
    )?.size ?: throw IllegalStateException("Provider returned no cursor for $uri")
}
