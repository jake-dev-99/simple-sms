package io.simplezen.simple_sms.device

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import io.simplezen.simple_query.ContentQuery

/**
 * Read helpers shared by the verified native writes (deletes, drafts). Every
 * read routes through simple_query and fails loudly rather than reading an
 * unreachable provider or a malformed value as "no rows".
 */

/** The `threads` table view the conversation list reads. */
internal val SIMPLE_CONVERSATIONS_URI: Uri = Telephony.Threads.CONTENT_URI
    .buildUpon().appendQueryParameter("simple", "true").build()

/**
 * The rows at [uri] matching [selection], read via simple_query's
 * `queryOrNull`. A null cursor (provider unreachable) throws instead of
 * reading as zero rows.
 */
internal fun queryRows(
    context: Context,
    uri: Uri,
    projection: Array<String>,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
    sortOrder: String? = null,
): List<Map<String, Any?>> = ContentQuery.queryOrNull(
    context,
    uri.toString(),
    projection = projection,
    selection = selection,
    selectionArgs = selectionArgs,
    sortOrder = sortOrder,
) ?: throw IllegalStateException("Provider returned no cursor for $uri")

/** [value] as a Long (providers may return numbers as text); null stays null. */
internal fun providerLong(value: Any?, column: String, uri: Uri): Long? = when (value) {
    null -> null
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
        ?: throw IllegalStateException("Non-numeric $column \"$value\" from $uri")
    else -> throw IllegalStateException("Non-numeric $column $value from $uri")
}
