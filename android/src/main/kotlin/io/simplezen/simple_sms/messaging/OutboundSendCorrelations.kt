package io.simplezen.simple_sms.messaging

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.BaseColumns
import io.simplezen.simple_query.ContentQuery
import org.json.JSONArray

// Purpose: recover send identity without matching private message data.
/**
 * @param preferences Durable metadata shared by this app's engines.
 * @param rowExists Verifies provider existence without reading message content.
 * @returns A registry of exact identities and recorded attempt order.
 * @throws IllegalStateException If metadata, provider availability, or retention cannot be verified.
 */
internal class OutboundSendCorrelations(
    private val preferences: SharedPreferences,
    private val rowExists: (String) -> Boolean,
) {
    // Purpose: keep retry metadata within a predictable allocation budget.
    /**
     * @param None These limits are fixed for this registry format.
     * @returns The live-attempt and encoded-history limits.
     * @throws None Reading the limits has no side effects.
     */
    companion object {
        // A logical send keeps at most 64 live attempts; none are silently evicted.
        private const val MAX_RETAINED_ATTEMPTS = 64
        private const val MAX_ENCODED_HISTORY_LENGTH = 4096
    }

    // Purpose: keep provider reads in the shared query layer.
    /**
     * @param context The owning application's context.
     * @returns A registry backed by this device's preferences and provider.
     * @throws SecurityException If application storage cannot be accessed.
     */
    constructor(context: Context) : this(context.getSharedPreferences(
        "outbound_send_correlations", Context.MODE_PRIVATE), { row ->
        val rows = ContentQuery.queryOrNull(context, row, arrayOf(BaseColumns._ID))
            ?: error("Could not verify outbound correlation row $row")
        rows.isNotEmpty()
    })

    // Purpose: reject exhausted retry history before a sender creates a row.
    /**
     * @param token The logical send identity, or null for an uncorrelated send.
     * @returns Unit once there is retention space for another attempt.
     * @throws IllegalArgumentException If the token is blank.
     * @throws IllegalStateException If all 64 retained attempts are live or verification fails.
     * @throws SecurityException If provider access is denied.
     */
    fun requireAttemptCapacity(token: String?) = synchronized(preferences) {
        if (token == null) return@synchronized
        require(token.isNotBlank()) { "clientCorrelationId must not be blank" }
        ensureCapacity(token, null)
    }

    // Purpose: make identity durable before the radio can accept the attempt.
    /**
     * @param uri The newly persisted provider row.
     * @param token Its logical send identity, or null to clear prior ownership.
     * @returns Unit after the row identity and attempt order commit together.
     * @throws IllegalArgumentException If the identity is malformed.
     * @throws IllegalStateException If retention, history, or durable persistence fails.
     * @throws SecurityException If provider verification is denied at the retention limit.
     * @throws org.json.JSONException If the stored attempt history is malformed.
     */
    fun record(uri: Uri, token: String?) = synchronized(preferences) {
        require(token == null || token.isNotBlank()) { "clientCorrelationId must not be blank" }
        val row = canonical(uri.toString())
        val previous = preferences.getString("row:$row", null)
        if (token == null && previous == null) return@synchronized
        if (token != null) ensureCapacity(token, row)
        val editor = preferences.edit()
        if (previous != token) forgetRow(editor, row)
        if (token != null) {
            editor.putString("row:$row", token)
            editor.writeAttempts(token, attempts(token).filter { it != row } + row)
        }
        commit(editor)
    }

    // Purpose: recover surviving identities without redirecting to reused rows.
    /**
     * @param uris The exact provider rows requested by the consumer.
     * @returns Correlations keyed by requested URI with the latest surviving attempt.
     * @throws IllegalArgumentException If a URI is malformed.
     * @throws IllegalStateException If history, provider availability, or persistence is unverifiable.
     * @throws SecurityException If provider access is denied.
     * @throws org.json.JSONException If the stored attempt history is malformed.
     */
    fun lookup(uris: List<String>): Map<String, Map<String, String>> = synchronized(preferences) {
        buildMap {
            for (uri in uris) {
                val row = canonical(uri)
                val token = preferences.getString("row:$row", null) ?: continue
                if (!rowExists(row)) {
                    val editor = preferences.edit()
                    forgetRow(editor, row)
                    commit(editor)
                    continue
                }
                var latest = canonical(preferences.getString("job:$token", null)
                    ?: error("Incomplete outbound send correlation"))
                check(attempts(token).lastOrNull() == latest) {
                    "Inconsistent outbound send attempts"
                }
                // Keep older attempts attached to their job, choosing the last
                // surviving attempt by recorded handoff order, never by IDs.
                while (preferences.getString("row:$latest", null) != token ||
                    (latest != row && !rowExists(latest))) {
                    val editor = preferences.edit()
                    val history = attempts(token)
                    check(history.lastOrNull() == latest) { "Inconsistent outbound send attempts" }
                    if (preferences.getString("row:$latest", null) == token) {
                        editor.remove("row:$latest")
                    }
                    editor.writeAttempts(token, history.dropLast(1))
                    commit(editor)
                    latest = canonical(preferences.getString("job:$token", null)
                        ?: error("Cannot recover outbound send attempt order"))
                }
                put(uri, mapOf("clientCorrelationId" to token,
                    "latestMessageUri" to latest))
            }
        }
    }

    // Purpose: reclaim only absent attempts, retaining every surviving identity.
    /**
     * @param token The logical send whose retention budget is checked.
     * @param rowToReuse A row already tracked by this job, or null for a new row.
     * @returns Unit when another attempt fits the bounded history.
     * @throws IllegalStateException If 64 live attempts remain, history is invalid, or storage fails.
     * @throws SecurityException If provider access is denied.
     */
    private fun ensureCapacity(token: String, rowToReuse: String?) {
        val history = attempts(token)
        if (rowToReuse in history || history.size < MAX_RETAINED_ATTEMPTS) return
        val absent = history.filter {
            preferences.getString("row:$it", null) != token || !rowExists(it)
        }.toSet()
        if (absent.isNotEmpty()) {
            val editor = preferences.edit()
            for (row in absent) {
                if (preferences.getString("row:$row", null) == token) editor.remove("row:$row")
            }
            editor.writeAttempts(token, history.filterNot { it in absent })
            commit(editor)
        }
        check(history.size - absent.size < MAX_RETAINED_ATTEMPTS) {
            "Outbound send correlation retention limit of 64 live attempts reached"
        }
    }

    // Purpose: prevent a deleted or reassigned URI from retaining old ownership.
    /**
     * @param editor The pending atomic metadata edit.
     * @param row The canonical URI losing ownership.
     * @returns Unit after queuing removal and the surviving ordered history.
     * @throws IllegalStateException If the stored history is inconsistent.
     */
    private fun forgetRow(editor: SharedPreferences.Editor, row: String) {
        val token = preferences.getString("row:$row", null)
        editor.remove("row:$row")
        if (token != null) {
            editor.writeAttempts(token, attempts(token).filter { it != row })
        }
    }

    // Purpose: preserve recorded order while rejecting corrupt or oversized history.
    /**
     * @param token The logical send identity.
     * @returns At most 64 canonical URIs in recorded handoff order.
     * @throws IllegalStateException If history is oversized or contains duplicates.
     * @throws IllegalArgumentException If a stored URI is malformed.
     * @throws org.json.JSONException If stored JSON is malformed.
     */
    private fun attempts(token: String): List<String> {
        val raw = preferences.getString("attempts:$token", null)
        // The unreleased first registry recorded only its current attempt.
        // Preserve that known identity without inventing earlier ordering.
        if (raw == null) return listOfNotNull(preferences.getString("job:$token", null)?.let(::canonical))
        check(raw.length <= MAX_ENCODED_HISTORY_LENGTH) { "Oversized outbound send history" }
        val array = JSONArray(raw)
        check(array.length() <= MAX_RETAINED_ATTEMPTS) { "Oversized outbound send history" }
        return List(array.length()) { canonical(array.getString(it)) }.also {
            check(it.distinct().size == it.size) { "Duplicate outbound send attempts" }
        }
    }

    // Purpose: commit the current pointer and bounded history as one metadata edit.
    /**
     * @param token The logical send identity.
     * @param rows Its ordered surviving attempts.
     * @returns Unit after queuing history and pointer changes on this editor.
     * @throws IllegalStateException If the history exceeds the retention limit.
     */
    private fun SharedPreferences.Editor.writeAttempts(token: String, rows: List<String>) {
        check(rows.size <= MAX_RETAINED_ATTEMPTS) { "Oversized outbound send history" }
        if (rows.isEmpty()) {
            remove("job:$token")
            remove("attempts:$token")
        } else {
            putString("job:$token", rows.last())
            putString("attempts:$token", JSONArray(rows).toString())
        }
    }

    // Purpose: prevent a successful send registration from outrunning durable storage.
    /**
     * @param editor The metadata transaction to persist synchronously.
     * @returns Unit only after persistence succeeds.
     * @throws IllegalStateException If the durable write fails.
     */
    private fun commit(editor: SharedPreferences.Editor) {
        check(editor.commit()) {
            "Could not persist outbound send correlations"
        }
    }

    // Purpose: prevent ambiguous row IDs from crossing SMS and MMS tables.
    /**
     * @param raw An exact provider row URI or a supported box-qualified alias.
     * @returns The canonical table-qualified URI.
     * @throws IllegalArgumentException If the URI does not identify a positive SMS/MMS row.
     */
    private fun canonical(raw: String): String {
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
