package io.simplezen.simple_sms.messaging

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.BaseColumns
import io.simplezen.simple_query.ContentQuery
import org.json.JSONArray

/** Exact, durable identities only; never stores recipients or message content. */
internal class OutboundSendCorrelations(
    private val preferences: SharedPreferences,
    private val rowExists: (String) -> Boolean,
) {
    constructor(context: Context) : this(context.getSharedPreferences(
        "outbound_send_correlations", Context.MODE_PRIVATE), { row ->
        val rows = ContentQuery.queryOrNull(context, row, arrayOf(BaseColumns._ID))
            ?: error("Could not verify outbound correlation row $row")
        rows.isNotEmpty()
    })

    fun record(uri: Uri, token: String?) = synchronized(preferences) {
        require(token == null || token.isNotBlank()) { "clientCorrelationId must not be blank" }
        val row = canonical(uri.toString())
        val previous = preferences.getString("row:$row", null)
        if (token == null && previous == null) return@synchronized
        val editor = preferences.edit()
        if (previous != token) forgetRow(editor, row)
        if (token != null) {
            editor.putString("row:$row", token)
            writeAttempts(editor, token, attempts(token).filter { it != row } + row)
        }
        commit(editor)
    }

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
                    writeAttempts(editor, token, history.dropLast(1))
                    commit(editor)
                    latest = canonical(preferences.getString("job:$token", null)
                        ?: error("Cannot recover outbound send attempt order"))
                }
                put(uri, mapOf("clientCorrelationId" to token,
                    "latestMessageUri" to latest))
            }
        }
    }

    private fun forgetRow(editor: SharedPreferences.Editor, row: String) {
        val token = preferences.getString("row:$row", null)
        editor.remove("row:$row")
        if (token != null) {
            writeAttempts(editor, token, attempts(token).filter { it != row })
        }
    }

    private fun attempts(token: String): List<String> {
        val raw = preferences.getString("attempts:$token", null)
        // The unreleased first registry recorded only its current attempt.
        // Preserve that known identity without inventing earlier ordering.
        if (raw == null) return listOfNotNull(preferences.getString("job:$token", null)?.let(::canonical))
        val array = JSONArray(raw)
        return List(array.length()) { canonical(array.getString(it)) }.also {
            check(it.distinct().size == it.size) { "Duplicate outbound send attempts" }
        }
    }

    private fun writeAttempts(editor: SharedPreferences.Editor, token: String, rows: List<String>) {
        if (rows.isEmpty()) {
            editor.remove("job:$token")
            editor.remove("attempts:$token")
        } else {
            editor.putString("job:$token", rows.last())
            editor.putString("attempts:$token", JSONArray(rows).toString())
        }
    }

    private fun commit(editor: SharedPreferences.Editor) {
        check(editor.commit()) {
            "Could not persist outbound send correlations"
        }
    }

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
