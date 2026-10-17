package io.simplezen.simple_sms.messaging

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.net.Uri
import android.provider.BaseColumns
import io.simplezen.simple_query.ContentQuery
import java.io.File
import java.util.UUID

// Purpose: durably associate exact device rows with logical sends and native attempts.
/**
 * @param context The owning application's provider and no-backup storage context.
 * @returns A registry using indexed device-local transactions, without message content.
 * @throws IllegalStateException If storage or provider identity cannot be verified.
 */
internal class OutboundSendCorrelations(private val context: Context) {
    // Purpose: serialize engine access and bound each logical send's retained rows.
    /**
     * @param None The constants belong to this registry format.
     * @returns The storage name, retention limit, and process-wide transaction lock.
     * @throws None Reading these values has no side effects.
     */
    companion object {
        private const val MAX_RETAINED_ROWS = 64
        private const val DATABASE_NAME = "outbound_send_correlations.db"
        internal val lock = Any()
    }

    // Purpose: distinguish multiple recipient rows of one send from later retries.
    /**
     * @param token The caller's logical send identity, or null for an uncorrelated send.
     * @returns An opaque native attempt identity shared by every persisted callback.
     * @throws None Identity allocation does not touch storage or the provider.
     */
    class Attempt(val token: String?) {
        val id: String = UUID.randomUUID().toString()
    }

    // Purpose: check sufficient retention space before creating any provider rows.
    /**
     * @param token The logical send identity, or null for an uncorrelated send.
     * @param expectedRows The maximum rows this native request can persist.
     * @returns One native attempt to use for all of this request's callbacks.
     * @throws IllegalArgumentException If the request cannot fit a single bounded attempt.
     * @throws IllegalStateException If live rows exhaust retention or verification fails.
     */
    private fun beginAttempt(token: String?, expectedRows: Int): Attempt {
        if (token != null) {
            require(token.isNotBlank()) { "clientCorrelationId must not be blank" }
            require(expectedRows in 1..MAX_RETAINED_ROWS) { "Correlated send must persist at most 64 rows" }
            transaction { db ->
                requireNotDeleted(db, token)
                ensureCapacity(db, token to expectedRows)
            }
        }
        return Attempt(token)
    }

    // Purpose: keep the capacity check and synchronous native initialization together.
    /**
     * @param request The logical token and maximum provider-row budget.
     * @param action The synchronous persistence and radio-initiation operation.
     * @returns The action result after initialization completes.
     * @throws IllegalStateException If retention or provider verification fails.
     * @throws IllegalArgumentException If the request identity or budget is invalid.
     */
    fun <T> withAttempt(request: Pair<String?, Int>, action: (Attempt) -> T): T = synchronized(lock) {
        action(beginAttempt(request.first, request.second))
    }

    // Purpose: register a standalone row as a new native attempt.
    /**
     * @param uri The persisted provider URI.
     * @param token The logical send identity, or null to clear prior ownership.
     * @returns Unit only after durable persistence succeeds.
     * @throws IllegalStateException If provider identity or retention is unverifiable.
     * @throws IllegalArgumentException If the URI or token is malformed.
     */
    fun record(uri: Uri, token: String?) { recordAttemptRow(uri, Attempt(token)) }

    // Purpose: commit provider generation and attempt membership before radio handoff.
    /**
     * @param uri The exact newly persisted provider row.
     * @param attempt The native request shared by this row's sibling recipients.
     * @returns Unit after the indexed transaction commits synchronously.
     * @throws IllegalStateException If identity, retention, or durable storage fails.
     * @throws IllegalArgumentException If the URI or token is malformed.
     * @throws SecurityException If provider reads are denied.
     */
    fun recordAttemptRow(uri: Uri, attempt: Attempt) {
        val row = canonical(uri.toString())
        val token = attempt.token
        require(token == null || token.isNotBlank()) { "clientCorrelationId must not be blank" }
        transaction { db ->
            if (token == null) {
                db.delete("correlations", "uri=?", arrayOf(row))
                return@transaction
            }
            requireNotDeleted(db, token)
            val generation = generation(row) ?: error("New outbound provider row is missing: $row")
            // Old readers compare MMS dates and cannot interpret transaction identities.
            if (Uri.parse(row).authority == "mms") db.version = 3
            val history = rows(db, token)
            val ordinal = history.firstOrNull { it.getValue("attempt") == attempt.id }
                ?.getValue("ordinal")?.toLong()
                ?: ((history.maxOfOrNull { it.getValue("ordinal").toLong() } ?: 0L) + 1L)
            val addedRows = if (history.any { it.getValue("uri") == row }) 0 else 1
            ensureCapacity(db, token to addedRows)
            val values = ContentValues().apply {
                put("uri", row)
                put("token", token)
                put("attempt", attempt.id)
                put("ordinal", ordinal)
                put("generation", generation)
            }
            db.insertWithOnConflict("correlations", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                .also { check(it != -1L) { "Could not persist outbound send correlation" } }
        }
    }

    // Purpose: recover only verified row generations, preserving all latest-attempt members.
    /**
     * @param uris The exact provider row URIs requested by the consumer.
     * @returns Correlations whose latestMessageUri marks every surviving row of the latest attempt.
     * @throws IllegalArgumentException If a URI is malformed or the batch exceeds 1000 rows.
     * @throws IllegalStateException If storage or provider identity cannot be verified.
     * @throws SecurityException If provider reads are denied.
     */
    fun lookup(uris: List<String>): Map<String, Map<String, String>> {
        require(uris.size <= 1000) { "Correlation lookup is limited to 1000 rows" }
        return transaction { db ->
            buildMap {
                for (uri in uris) {
                    val row = canonical(uri)
                    val stored = db.rawQuery("SELECT token FROM correlations WHERE uri=?", arrayOf(row)).use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: continue
                    val history = rows(db, stored)
                    val requested = history.first { it.getValue("uri") == row }
                    if (!verified(db, requested)) continue
                    val latest = history.firstOrNull { verified(db, it) }
                        ?: error("Verified outbound row has no surviving attempt")
                    val latestUri = if (latest.getValue("attempt") == requested.getValue("attempt")) row
                        else latest.getValue("uri")
                    put(uri, mapOf("clientCorrelationId" to stored, "latestMessageUri" to latestUri))
                }
            }
        }
    }

    // Purpose: keep live identity retention bounded without silently dropping survivors.
    /**
     * @param db The active indexed transaction.
     * @param request The logical token and number of additional rows needed.
     * @returns Unit once sufficient space exists, pruning only invalid generations.
     * @throws IllegalStateException If live rows fill the retention budget or verification fails.
     */
    private fun ensureCapacity(db: SQLiteDatabase, request: Pair<String, Int>) {
        val history = rows(db, request.first)
        if (history.size + request.second <= MAX_RETAINED_ROWS) return
        val live = history.count { verified(db, it) }
        check(live + request.second <= MAX_RETAINED_ROWS) {
            "Outbound send correlation retention limit of 64 live rows reached"
        }
    }

    // Purpose: read one bounded logical-send history using the token index.
    /**
     * @param db The active local database transaction.
     * @param token The logical send identity to query.
     * @returns At most 64 row identities in descending recorded attempt order.
     * @throws IllegalStateException If the database violates the retention contract.
     */
    internal fun rows(db: SQLiteDatabase, token: String): List<Map<String, String>> =
        db.rawQuery("SELECT uri, attempt, ordinal, generation FROM correlations " +
            "WHERE token=? ORDER BY ordinal DESC, uri DESC LIMIT 65", arrayOf(token)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    check(size < MAX_RETAINED_ROWS) { "Oversized outbound send history" }
                    add(mapOf("uri" to cursor.getString(0), "attempt" to cursor.getString(1),
                        "ordinal" to cursor.getLong(2).toString(), "generation" to cursor.getString(3)))
                }
            }
        }

    // Purpose: invalidate reused or absent rows even if no deletion was observed.
    /**
     * @param db The active metadata transaction.
     * @param row The stored URI and occurrence identity captured before handoff.
     * @returns True only while the provider still exposes that recorded generation.
     * @throws IllegalStateException If provider availability or identity is unverifiable.
     * @throws SecurityException If provider access is denied.
     */
    private fun verified(db: SQLiteDatabase, row: Map<String, String>): Boolean {
        val uri = row.getValue("uri")
        requireKnownGeneration(uri, row.getValue("generation"))
        if (generation(uri) == row.getValue("generation")) return true
        // A deletion fence retains membership so a stale request can resume
        // a partially completed delete after lookup/restart (UNFY-346).
        db.delete("correlations", "uri=? AND token NOT IN (SELECT token FROM deleted_sends)", arrayOf(uri))
        return false
    }

    /** Reject a deleted logical identity before provider creation or handoff. */
    private fun requireNotDeleted(db: SQLiteDatabase, token: String) {
        db.rawQuery("SELECT 1 FROM deleted_sends WHERE token=?", arrayOf(token)).use {
            if (it.moveToFirst()) throw SendDeletedException()
        }
    }

    /** Share the native initialization lock with deletion, across engines. */
    internal fun <T> locked(action: () -> T): T = synchronized(lock) { action() }

    /** Read exact occurrence proof; MMS dates and row IDs cannot identify a PDU. */
    internal fun generation(row: String, allowUndated: Boolean = false): String? {
        val uri = Uri.parse(row)
        val column = if (uri.authority == "mms") "tr_id" else "date"
        val result = ContentQuery.queryOrNull(context, row, arrayOf(BaseColumns._ID, column))
            ?: error("Could not verify outbound correlation row $row")
        check(result.size <= 1) { "Ambiguous outbound provider row" }
        val values = result.firstOrNull() ?: return null
        check(values[BaseColumns._ID]?.toString() == uri.lastPathSegment) { "Unexpected provider row identity" }
        if (uri.authority == "mms") {
            val transaction = values["tr_id"] as? String
            check(!transaction.isNullOrBlank()) { "Missing MMS transaction identity: $row" }
            return "mms:$transaction"
        }
        check(values.containsKey("date")) { "Provider omitted outbound generation" }
        if (allowUndated && values["date"] == null) return "null"
        return values["date"]?.toString()?.toLongOrNull()?.toString()
            ?: error("Missing outbound provider generation: $row")
    }

    /** Never upgrade an old MMS date/row ID by adopting the current row's identity. */
    internal fun requireKnownGeneration(uri: String, generation: String) {
        check(Uri.parse(uri).authority != "mms" ||
            (generation.startsWith("mms:") && generation.removePrefix("mms:").isNotBlank())) {
            "Legacy MMS occurrence is unverified; deletion cannot safely continue"
        }
    }

    // Purpose: atomically persist indexed metadata in storage excluded from device backup.
    /**
     * @param action The metadata operation to execute in one durable transaction.
     * @returns The operation's result only after the transaction commits and closes.
     * @throws android.database.sqlite.SQLiteException If storage cannot be opened or committed.
     * @throws IllegalStateException If the database format is unsupported.
     */
    internal fun <T> transaction(action: (SQLiteDatabase) -> T): T = synchronized(lock) {
        val file = File(context.noBackupFilesDir, DATABASE_NAME)
        SQLiteDatabase.openOrCreateDatabase(file.path, null) {
            throw SQLiteDatabaseCorruptException("Outbound correlation database is corrupt")
        }.use { db ->
            db.execSQL("PRAGMA synchronous=FULL")
            db.beginTransaction()
            try {
                if (db.version == 0) {
                    db.execSQL("CREATE TABLE correlations (uri TEXT PRIMARY KEY NOT NULL, " +
                        "token TEXT NOT NULL, attempt TEXT NOT NULL, ordinal INTEGER NOT NULL, " +
                        "generation TEXT NOT NULL)")
                    db.execSQL("CREATE INDEX correlations_job ON correlations(token, ordinal DESC)")
                    db.version = 1
                }
                check(db.version in 1..3) { "Unsupported outbound correlation database version" }
                // Additive metadata remains readable without transforming rows.
                db.execSQL("CREATE TABLE IF NOT EXISTS deleted_sends (token TEXT PRIMARY KEY NOT NULL, complete INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE IF NOT EXISTS deletion_rows (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "scope TEXT NOT NULL, uri TEXT NOT NULL, generation TEXT NOT NULL, token TEXT, " +
                    "confirmed INTEGER NOT NULL DEFAULT 0, acked INTEGER NOT NULL DEFAULT 0, UNIQUE(scope, uri, generation))")
                val hasAcknowledgment = db.rawQuery("PRAGMA table_info(deletion_rows)", null).use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) if (cursor.getString(1) == "acked") found = true
                    found
                }
                if (!hasAcknowledgment) db.execSQL("ALTER TABLE deletion_rows ADD COLUMN acked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS deletion_rows_pending ON deletion_rows(acked, id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS deletion_rows_job ON deletion_rows(token, acked)")
                // Version 3 permanently fences readers that cannot understand MMS transaction proof.
                // Reversible before the first fence: v1 readers can open the
                // unchanged correlation format. Once a send is deleted, v2 is a
                // safety barrier: 0.6 rejects it before creating any provider row.
                // Never downgrade a fenced store, including older local builds
                // that incorrectly stamped fences v1. No metadata is discarded.
                fun stampDeletionBarrier() {
                    val fenced = db.rawQuery("SELECT 1 FROM deleted_sends LIMIT 1", null).use { it.moveToFirst() }
                    db.version = if (db.version == 3) 3 else if (fenced) 2 else 1
                }
                stampDeletionBarrier()

                db.execSQL("CREATE TABLE IF NOT EXISTS send_deletions (token TEXT PRIMARY KEY NOT NULL, " +
                    "thread_id TEXT NOT NULL, anchor TEXT NOT NULL, complete INTEGER NOT NULL DEFAULT 0, " +
                    "pending INTEGER NOT NULL DEFAULT 1)")
                db.execSQL("CREATE INDEX IF NOT EXISTS pending_send_deletions ON send_deletions(pending, token)")
                db.execSQL("CREATE TABLE IF NOT EXISTS send_deletion_rows (token TEXT NOT NULL, uri TEXT NOT NULL, " +
                    "generation TEXT NOT NULL, ordinal INTEGER NOT NULL, confirmed INTEGER NOT NULL DEFAULT 0, " +
                    "acked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(token, uri))")
                // Import old local-build fences once; do not scan permanent history on every send.
                db.execSQL("CREATE TABLE IF NOT EXISTS correlation_migrations (name TEXT PRIMARY KEY NOT NULL)")
                val imported = db.rawQuery("SELECT 1 FROM correlation_migrations WHERE name='shared_fence'", null)
                    .use { it.moveToFirst() }
                if (!imported) {
                    db.execSQL("INSERT OR IGNORE INTO deleted_sends(token) SELECT token FROM send_deletions")
                    db.execSQL("INSERT INTO correlation_migrations(name) VALUES('shared_fence')")
                }
                stampDeletionBarrier()
                val result = action(db)
                stampDeletionBarrier()
                db.setTransactionSuccessful()
                result
            } finally {
                db.endTransaction()
            }
        }
    }

    // Purpose: keep row IDs qualified by SMS/MMS table and reject ambiguous aliases.
    /**
     * @param raw An exact row URI or supported box-qualified alias.
     * @returns The canonical table-qualified URI.
     * @throws IllegalArgumentException If the URI does not identify a positive SMS/MMS row.
     */
    internal fun canonical(raw: String): String {
        val uri = Uri.parse(raw)
        require(uri.scheme == "content" && uri.authority in setOf("sms", "mms")
            && uri.query == null && uri.fragment == null) { "Invalid message URI" }
        val path = uri.pathSegments
        require(path.size == 1 || (path.size == 2 && path.first() in
            setOf("inbox", "outbox", "sent", "drafts", "failed", "queued"))) {
            "Invalid message URI path"
        }
        val id = path.last().toLongOrNull()
        require(id != null && id > 0) { "Invalid message row id" }
        return "content://${uri.authority}/$id"
    }
}

/** Purpose: report a durable user deletion as a terminal send rejection. */
internal class SendDeletedException : IllegalStateException("The logical send was deleted")
