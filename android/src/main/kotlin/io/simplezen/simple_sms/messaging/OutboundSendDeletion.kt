package io.simplezen.simple_sms.messaging

import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import io.simplezen.simple_permissions_android.PermissionGuards
import io.simplezen.simple_query.ContentQuery
import io.simplezen.simple_sms.device.ProviderWriteLock

/**
 * UNFY-346: bounded native logical-send deletion with write-ahead receipts.
 * Uses the same process lock and indexed history as send initialization.
 * No message content is retained. Receipts survive engine/process loss until
 * the caller acknowledges durable tombstones; provider generations protect reuse.
 */
internal class OutboundSendDeletion(private val context: Context) {
    private val store = OutboundSendCorrelations(context)

    /**
     * Fence the job durably, capture at most 64 exact identities, and delete in
     * recorded attempt order. A stale requested URI never moves the latest
     * attempt ahead of an older one. Throws on permission, storage, provider,
     * verification or budget failure, retaining every planned identity.
     */
    fun delete(scope: String, requestedUri: String, budgetMs: Long = 10_000) {
        require(scope.isNotBlank() && scope.length <= 256) { "Invalid deletion scope" }
        require(budgetMs in 1..10_000) { "Invalid deletion budget" }
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        synchronized(ProviderWriteLock) { store.locked {
            PermissionGuards.requireRoleHeld(context, RoleManager.ROLE_SMS)
            val uri = store.canonical(requestedUri)
            checkBudget(deadline)
            val plan = store.transaction { db ->
                val requestedGeneration = generation(uri)
                val token = db.rawQuery("SELECT token, generation FROM correlations WHERE uri=?", arrayOf(uri)).use { cursor ->
                    if (!cursor.moveToFirst()) null else {
                        val candidate = cursor.getString(0)
                        // Absence resumes only an already-fenced deletion. A
                        // live reused URI belongs to its current generation.
                        val matches = if (requestedGeneration != null) {
                            requestedGeneration == cursor.getString(1)
                        } else {
                            db.rawQuery("SELECT 1 FROM deleted_sends WHERE token=? AND (complete=0 OR " +
                                "EXISTS (SELECT 1 FROM deletion_rows WHERE token=? AND acked=0))",
                                arrayOf(candidate, candidate)).use { it.moveToFirst() }
                        }
                        if (matches) candidate else null
                    }
                }
                val targets = if (token == null) {
                    listOf(mapOf("uri" to uri, "generation" to (requestedGeneration ?: "absent")))
                } else {
                    store.rows(db, token).sortedBy { it.getValue("ordinal").toLong() }
                }
                // Verify the whole bounded plan before persisting a fence or
                // touching the provider. Never mistake an outage for absence.
                for (row in targets) {
                    checkBudget(deadline)
                    generation(row.getValue("uri"))
                }
                checkBudget(deadline)
                if (token != null) db.execSQL("INSERT OR IGNORE INTO deleted_sends(token) VALUES(?)", arrayOf(token))
                targets.map { row ->
                    val values = ContentValues().apply {
                        put("scope", scope)
                        put("uri", row.getValue("uri"))
                        put("generation", row.getValue("generation"))
                        put("token", token)
                    }
                    db.insertWithOnConflict("deletion_rows", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
                    db.rawQuery("SELECT id FROM deletion_rows WHERE scope=? AND uri=? AND generation=?",
                        arrayOf(scope, row.getValue("uri"), row.getValue("generation"))).use {
                        check(it.moveToFirst()) { "Deletion identity was not persisted" }
                        Planned(it.getLong(0), scope, row.getValue("uri"), row.getValue("generation"), false, token)
                    }
                }
            }
            for (row in plan) {
                checkBudget(deadline)
                if (generation(row.uri) == row.generation) {
                    checkBudget(deadline)
                    val undated = row.generation == "null"
                    context.contentResolver.delete(Uri.parse(row.uri),
                        if (undated) "date IS NULL" else "date = ?",
                        if (undated) null else arrayOf(row.generation))
                    check(generation(row.uri) != row.generation) { "Native send attempt was not deleted: ${row.uri}" }
                }
                confirm(row.id)
            }
            plan.firstOrNull()?.token?.let { markComplete(it) }
        } }
    }

    /**
     * Read/recover one bounded receipt page. Planned rows still present are
     * returned with confirmed=false; absent/reused generations confirm crash
     * recovery without repeating any provider write. Advance after every id,
     * including unconfirmed rows, to avoid starving newer receipts. The cursor
     * is traversal-local: every NEW drain must start at zero. Confirmation can
     * change an existing row, so an id is not a durable changes-since watermark.
     * Only acknowledgment removes a row from subsequent drains.
     */
    fun pending(after: Long, limit: Int): List<Map<String, Any>> = store.locked {
        require(after >= 0 && limit in 1..64) { "Invalid deletion receipt page" }
        val page = store.transaction { db ->
            db.rawQuery("SELECT id, scope, uri, generation, confirmed, token FROM deletion_rows WHERE acked=0 AND id>? ORDER BY id LIMIT ?",
                arrayOf(after.toString(), limit.toString())).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(Planned(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getInt(4) == 1, cursor.getString(5)))
                }
            }
        }
        val completedJobs = mutableMapOf<String, Boolean>()
        page.map { row ->
            val confirmed = row.confirmed || (generation(row.uri) != row.generation).also {
                if (it) confirm(row.id)
            }
            buildMap {
                put("id", row.id)
                put("scope", row.scope)
                put("messageUri", row.uri)
                put("confirmed", confirmed)
                row.token?.let { token ->
                    put("clientCorrelationId", token)
                    put("jobDeletionComplete", completedJobs.getOrPut(token) { jobComplete(token) })
                }
            }
        }
    }

    /** Consume a receipt after journaling, retaining its identity against retry replay. Idempotent. */
    fun acknowledge(id: Long) {
        require(id > 0) { "Invalid deletion receipt id" }
        store.transaction { db ->
            db.rawQuery("SELECT confirmed FROM deletion_rows WHERE id=?", arrayOf(id.toString())).use {
                check(!it.moveToFirst() || it.getInt(0) == 1) { "Cannot acknowledge an unconfirmed deletion" }
            }
            db.execSQL("UPDATE deletion_rows SET acked=1 WHERE id=?", arrayOf(id))
        }
    }

    /** Persist confirmation after provider read-back; failed commits leave the plan recoverable. */
    private fun confirm(id: Long) {
        store.transaction { db ->
            val values = ContentValues().apply { put("confirmed", 1) }
            check(db.update("deletion_rows", values, "id=?", arrayOf(id.toString())) == 1) { "Deletion receipt is missing" }
        }
    }

    /** A queue job may be removed only once every retained generation is gone. */
    private fun jobComplete(token: String): Boolean = store.transaction { db ->
        val complete = db.rawQuery("SELECT complete FROM deleted_sends WHERE token=?", arrayOf(token)).use {
            it.moveToFirst() && it.getInt(0) == 1
        }
        if (complete) return@transaction true
        val gone = store.rows(db, token).all { generation(it.getValue("uri")) != it.getValue("generation") }
        if (gone) db.execSQL("UPDATE deleted_sends SET complete=1 WHERE token=?", arrayOf(token))
        gone
    }

    /** Commit completion only after all captured generations have been verified absent. */
    private fun markComplete(token: String) {
        store.transaction { it.execSQL("UPDATE deleted_sends SET complete=1 WHERE token=?", arrayOf(token)) }
    }

    /** Read only one exact row through simple-query; null dates remain distinct from absence. */
    private fun generation(uri: String): String? {
        val rows = ContentQuery.queryOrNull(context, uri, arrayOf("_id", "date"))
            ?: error("Could not verify deletion identity: $uri")
        check(rows.size <= 1) { "Ambiguous deletion identity" }
        val row = rows.firstOrNull() ?: return null
        check(row["_id"]?.toString()?.toLongOrNull() == Uri.parse(uri).lastPathSegment?.toLongOrNull()) { "Incorrect provider deletion identity" }
        check(row.containsKey("date")) { "Provider omitted deletion generation" }
        return row["date"]?.let { value ->
            value.toString().toLongOrNull()?.toString() ?: error("Malformed deletion generation")
        } ?: "null"
    }

    /** Stop before the next irreversible write if lock wait/provider work consumed the budget. */
    private fun checkBudget(deadline: Long) {
        check(SystemClock.elapsedRealtime() < deadline) { "Native send deletion exceeded its work budget" }
    }

    private data class Planned(val id: Long, val scope: String, val uri: String, val generation: String, val confirmed: Boolean, val token: String?)
}
