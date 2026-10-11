package io.simplezen.simple_sms.messaging

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/** Exact, durable identities only; never stores recipients or message content. */
internal class OutboundSendCorrelations(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(
        "outbound_send_correlations", Context.MODE_PRIVATE))

    fun record(uri: Uri, token: String?) {
        if (token == null) return
        require(token.isNotBlank()) { "clientCorrelationId must not be blank" }
        val row = canonical(uri.toString())
        check(preferences.edit().putString("row:$row", token)
            .putString("job:$token", row).commit()) {
            "Could not persist outbound send correlation before radio handoff"
        }
    }

    fun lookup(uris: List<String>): Map<String, Map<String, String>> = buildMap {
        for (uri in uris) {
            val row = canonical(uri)
            val token = preferences.getString("row:$row", null) ?: continue
            val latest = preferences.getString("job:$token", null)
                ?: error("Incomplete outbound send correlation")
            put(uri, mapOf("clientCorrelationId" to token,
                "latestMessageUri" to canonical(latest)))
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
