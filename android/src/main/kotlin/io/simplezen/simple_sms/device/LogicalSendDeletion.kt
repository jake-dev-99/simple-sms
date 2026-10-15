package io.simplezen.simple_sms.device

import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.simplezen.simple_permissions_android.PermissionDeniedException
import io.simplezen.simple_permissions_android.PermissionGuards
import io.simplezen.simple_query.ContentQuery
import io.simplezen.simple_sms.messaging.OutboundSendCorrelations

/**
 * Purpose: delete a bounded logical send and retain confirmations until the host journals them.
 * Only device-local identities are stored, never message content or recipient numbers (UNFY-346).
 */
internal class LogicalSendDeletion(private val context: Context) {
    private val store = OutboundSendCorrelations(context)

    /** Purpose: expose the additive contract on the existing destructive background channel. */
    fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            val args = call.arguments as? Map<*, *> ?: throw IllegalArgumentException("Expected deletion arguments")
            when (call.method) {
                "deleteSend" -> {
                    val token = token(args["clientCorrelationId"])
                    val thread = thread(args["threadId"])
                    val anchor = store.canonical(args["messageUri"] as? String
                        ?: throw IllegalArgumentException("messageUri is required"))
                    result.success(delete(token, thread, anchor))
                }
                "pendingSendDeletions" -> {
                    val limit = args["limit"] as? Int ?: throw IllegalArgumentException("limit is required")
                    require(limit in 1..64) { "limit must be between 1 and 64" }
                    val thread = args["threadId"]?.let { thread(it) }
                    val after = args["afterClientCorrelationId"]?.let { token(it) }
                    result.success(pending(thread, after, limit))
                }
                "acknowledgeSendDeletion" -> {
                    val token = token(args["clientCorrelationId"])
                    val raw = args["messageUris"] as? List<*> ?: throw IllegalArgumentException("messageUris is required")
                    require(raw.size in 1..64) { "Acknowledge between 1 and 64 confirmations" }
                    val uris = raw.map { store.canonical(it as? String ?: throw IllegalArgumentException("Invalid message URI")) }
                    acknowledge(token, uris)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        } catch (e: PermissionDeniedException) {
            result.error("NOT_DEFAULT_SMS_APP", e.message, null)
        } catch (e: IllegalArgumentException) {
            result.error("INVALID_ARGUMENT", e.message, null)
        } catch (e: Exception) {
            result.error("DELETE_FAILED", e.message, null)
        }
    }

    /** Purpose: serialize row capture, durable cancellation and provider mutation against every engine's send. */
    private fun delete(token: String, thread: String, anchor: String): Map<String, Any?> =
        synchronized(ProviderWriteLock) { synchronized(OutboundSendCorrelations.lock) {
            PermissionGuards.requireRoleHeld(context, RoleManager.ROLE_SMS)
            capture(token, thread, anchor)
            var failure: String? = null
            var message: String? = null
            try {
                val targets = store.transaction { db -> deletionRows(db, token) }
                for (row in targets) {
                    if (row.confirmed) continue
                    PermissionGuards.requireRoleHeld(context, RoleManager.ROLE_SMS)
                    val current = current(row.uri)
                    val owns = store.transaction { db -> owner(db, row.uri) == token }
                    if (current != null && current.generation == row.generation && owns) {
                        check(current.thread == thread) { "Provider row moved to another thread" }
                        context.contentResolver.delete(Uri.parse(row.uri), "date = ? AND thread_id = ?",
                            arrayOf(row.generation, thread))
                        val after = current(row.uri)
                        if (after != null && after.generation == row.generation && after.thread == thread) {
                            throw IncompleteDelete()
                        }
                    }
                    // A missing/reused generation proves this captured occurrence is gone.
                    // Commit separately: a later provider/SQLite failure cannot roll this receipt back.
                    store.transaction { db ->
                        val values = ContentValues().apply { put("confirmed", 1) }
                        check(db.update("send_deletion_rows", values, "token=? AND uri=?",
                            arrayOf(token, row.uri)) == 1) { "Lost deletion plan row" }
                        db.delete("correlations", "uri=? AND token=? AND generation=?",
                            arrayOf(row.uri, token, row.generation))
                    }
                }
                store.transaction { db ->
                    val values = ContentValues().apply { put("complete", 1) }
                    check(db.update("send_deletions", values, "token=?", arrayOf(token)) == 1)
                }
            } catch (e: SQLiteException) {
                // The provider may already have committed: preserve the captured plan and fail closed.
                throw e
            } catch (e: PermissionDeniedException) {
                failure = "NOT_DEFAULT_SMS_APP"; message = e.message
            } catch (e: IncompleteDelete) {
                failure = "DELETE_INCOMPLETE"; message = "The provider retained a captured send row"
            } catch (e: Exception) {
                failure = "DELETE_FAILED"; message = e.message
            }
            store.transaction { db -> receipt(db, token) }.toMutableMap().apply {
                if (failure != null) { put("failureCode", failure); put("failureMessage", message) }
            }
        } }

    /** Purpose: capture all attempts by the bounded token index, committing the fence before writes. */
    private fun capture(token: String, thread: String, anchor: String) = store.transaction { db ->
        db.rawQuery("SELECT thread_id FROM send_deletions WHERE token=?", arrayOf(token)).use {
            if (it.moveToFirst()) {
                require(it.getString(0) == thread) { "Deletion belongs to another thread" }
                return@transaction
            }
        }
        require(owner(db, anchor) == token) { "The selected row no longer belongs to this logical send" }
        val history = store.rows(db, token)
        check(history.isNotEmpty()) { "Missing native send history" }
        val verified = history.map { row ->
            val live = current(row.getValue("uri"))
            if (live != null && live.generation == row.getValue("generation")) {
                check(live.thread == thread) { "A send attempt belongs to another thread" }
            }
            row to (live == null || live.generation != row.getValue("generation"))
        }
        // This bound is on outstanding host confirmations, not on total user history.
        db.rawQuery("SELECT COUNT(*) FROM send_deletions WHERE pending=1", null).use {
            check(it.moveToFirst() && it.getInt(0) < 64) { "Acknowledge pending send deletions before creating another" }
        }
        db.insertOrThrow("send_deletions", null, ContentValues().apply {
            put("token", token); put("thread_id", thread); put("anchor", anchor)
        })
        for ((row, absent) in verified) {
            db.insertOrThrow("send_deletion_rows", null, ContentValues().apply {
                put("token", token); put("uri", row.getValue("uri")); put("generation", row.getValue("generation"))
                put("ordinal", row.getValue("ordinal").toLong()); put("confirmed", if (absent) 1 else 0)
            })
        }
    }

    /** Purpose: page only unacknowledged plans through their index without provider/history reads. */
    private fun pending(thread: String?, after: String?, limit: Int): List<Map<String, Any?>> = store.transaction { db ->
        val where = mutableListOf("pending=1")
        val args = mutableListOf<String>()
        if (thread != null) { where.add("thread_id=?"); args.add(thread) }
        if (after != null) { where.add("token>?"); args.add(after) }
        val tokens = db.rawQuery("SELECT token FROM send_deletions WHERE ${where.joinToString(" AND ")} " +
            "ORDER BY token LIMIT ?", (args + limit.toString()).toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        tokens.map { receipt(db, it) }
    }

    /** Purpose: retain each confirmed identity until the host's durable journal write has succeeded. */
    private fun acknowledge(token: String, uris: List<String>) = store.transaction { db ->
        for (uri in uris) {
            db.rawQuery("SELECT confirmed FROM send_deletion_rows WHERE token=? AND uri=?", arrayOf(token, uri)).use {
                require(it.moveToFirst() && it.getInt(0) == 1) { "Cannot acknowledge an unconfirmed deletion" }
            }
        }
        for (uri in uris) {
            db.update("send_deletion_rows", ContentValues().apply { put("acked", 1) },
                "token=? AND uri=?", arrayOf(token, uri))
        }
        val complete = receipt(db, token)["complete"] == true
        val unacked = db.rawQuery("SELECT COUNT(*) FROM send_deletion_rows WHERE token=? AND acked=0", arrayOf(token)).use {
            check(it.moveToFirst()); it.getInt(0)
        }
        if (complete && unacked == 0) {
            db.update("send_deletions", ContentValues().apply { put("pending", 0) }, "token=?", arrayOf(token))
            // Retain bounded row identities for idempotent acknowledgements, and the
            // cancellation marker so a late retry never reaches the radio.
        }
    }

    /** Purpose: return exact, unacknowledged confirmations and resumable operation identity. */
    private fun receipt(db: SQLiteDatabase, token: String): Map<String, Any?> {
        val head = db.rawQuery("SELECT thread_id, anchor, complete FROM send_deletions WHERE token=?", arrayOf(token)).use {
            check(it.moveToFirst()) { "Unknown deletion job" }
            Triple(it.getString(0), it.getString(1), it.getInt(2) == 1)
        }
        val confirmed = deletionRows(db, token).filter { it.confirmed && !it.acked }
        return mapOf("clientCorrelationId" to token, "threadId" to head.first, "messageUri" to head.second,
            "complete" to head.third, "confirmedMessageUris" to confirmed.map { it.uri },
            "confirmedGenerations" to confirmed.associate { it.uri to it.generation })
    }

    /** Purpose: retain native attempt ordering, placing every actual latest-attempt member last. */
    private fun deletionRows(db: SQLiteDatabase, token: String): List<Target> =
        db.rawQuery("SELECT uri, generation, confirmed, acked FROM send_deletion_rows WHERE token=? " +
            "ORDER BY ordinal ASC, uri ASC LIMIT 65", arrayOf(token)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    check(size < 64) { "Oversized send deletion plan" }
                    add(Target(cursor.getString(0), cursor.getString(1), cursor.getInt(2) == 1, cursor.getInt(3) == 1))
                }
            }
        }

    /** Purpose: identify the current native ledger owner without matching text or recipients. */
    private fun owner(db: SQLiteDatabase, uri: String): String? =
        db.rawQuery("SELECT token FROM correlations WHERE uri=?", arrayOf(uri)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    /** Purpose: verify raw date generation and thread through the shared query layer. */
    private fun current(uri: String): Current? {
        val result = ContentQuery.queryOrNull(context, uri, arrayOf("_id", "date", "thread_id"))
            ?: error("Cannot verify provider row $uri")
        check(result.size <= 1) { "Ambiguous provider row $uri" }
        val row = result.firstOrNull() ?: return null
        check(row["_id"]?.toString() == Uri.parse(uri).lastPathSegment) { "Unexpected provider row identity" }
        val generation = row["date"]?.toString()?.toLongOrNull() ?: error("Missing provider generation for $uri")
        val thread = row["thread_id"]?.toString()?.toLongOrNull() ?: error("Missing provider thread for $uri")
        check(thread > 0) { "Invalid provider thread for $uri" }
        return Current(generation.toString(), thread.toString())
    }

    /** Purpose: validate caller-owned correlation identity without silently substituting another job. */
    private fun token(value: Any?): String = (value as? String)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("clientCorrelationId must be nonblank")
    /** Purpose: validate channel arguments before creating a durable deletion intent. */
    private fun thread(value: Any?): String = positiveRowId(value)?.toString()
        ?: throw IllegalArgumentException("threadId must be positive")

    private data class Current(val generation: String, val thread: String)
    private data class Target(val uri: String, val generation: String, val confirmed: Boolean, val acked: Boolean)
    private class IncompleteDelete : IllegalStateException()
}
