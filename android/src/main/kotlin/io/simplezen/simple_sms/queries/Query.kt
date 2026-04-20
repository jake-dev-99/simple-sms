package io.simplezen.simple_sms.queries

import android.content.Context
import io.simplezen.simple_query.ContentQuery

/**
 * Thin Kotlin-side wrapper around `simple_query`'s [ContentQuery] helper.
 *
 * Exists only so the four internal callers (MmsDatabaseWriter, InboundSmsHandler,
 * OutboundMessagingHandler, and this file's legacy wrappers) can share a single
 * `QueryObj`-to-cursor coercion. Delegates all actual provider reads to
 * [ContentQuery] so Rule 1 ("content-provider queries route through
 * simple-query") holds at the Kotlin layer just as it does on the Dart side.
 *
 * No MethodChannel handler is attached here — Dart never invokes this class
 * directly; everything on the Dart side goes through the `simple_query`
 * plugin's own channels. The previous `MethodCallHandler` implementation
 * exposed `query` / `getDeviceInfo` / `getSimInfo` / `getFile` cases that
 * were dead from Dart's perspective (device/SIM info moved to
 * `simple_telephony_native`).
 */
data class QueryObj(
    val contentUri: String,
    val projection: List<String>? = null,
    val selection: String? = null,
    val selectionArgs: List<String>? = null,
    val sortOrder: String? = null,
)

class Query(val context: Context) {

    fun query(query: QueryObj): List<Map<String, Any?>> =
        getCursorData(context, query).map { it.toSortedMap() }

    fun getCursorData(context: Context, query: QueryObj): List<Map<String, Any?>> {
        // Delegate to simple_query's ContentQuery helper so Rule 1
        // ("content-provider queries route through simple-query")
        // holds at the Kotlin layer too, not just at the Dart API.
        //
        // Two deliberate behaviour shifts from the previous inline
        // implementation — both safe because simple-sms's internal
        // callers (MmsDatabaseWriter, InboundSmsHandler) read
        // string / long / uri columns, not BLOB bytes:
        //
        //   1. BLOB columns are null-coalesced (matches Pigeon path's
        //      behaviour so Dart + Kotlin see the same shape; for
        //      raw bytes drop to ContentResolver.openInputStream).
        //   2. FIELD_TYPE_FLOAT surfaces as Double (Pigeon default)
        //      instead of Float.
        return ContentQuery.query(
            context,
            query.contentUri,
            projection = query.projection?.toTypedArray(),
            selection = query.selection,
            selectionArgs = query.selectionArgs?.toTypedArray(),
            sortOrder = query.sortOrder,
        )
    }
}
