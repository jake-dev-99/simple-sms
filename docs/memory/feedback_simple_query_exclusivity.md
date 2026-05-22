---
name: All Android queries route through simple-query
description: Hard rule — simple-sms (and related packages) must not query ContentProviders directly; every read goes through the simple_query package (Dart QueryRequest, Kotlin ContentQuery).
type: feedback
---
All Android ContentProvider reads in `simple-sms` (Dart and Kotlin sides) must route through the `simple_query` package. Never drop to `ContentResolver.query`, raw `MethodChannel`-based cursor reads, or any bespoke query helper.

**Why:** The user enforces a layering contract across the four sister packages (simple-sms, simple-permissions, simple-query, simple-telephony). simple_query owns the query transport, schema canonicalization, and binary-handle lifecycle. Bypassing it splinters the contract, duplicates cursor/type-coercion logic, and breaks consistency for consumers that depend on the domain canonicalisation. The user has flagged this explicitly with "don't go rogue on this."

**How to apply:**
- In Dart: use `SimpleQuery.instance.query(QueryRequest(...))` / `openBinary` / `closeBinary`. Raw column access goes via `QueryDomain.platformSpecific` + `platformData: {'contentUri': ...}`.
- In Kotlin: use `io.simplezen.simple_query.ContentQuery.query(context, uri, projection, selection, selectionArgs, sortOrder)`. `Query.kt`'s `getCursorData` already does this — stay consistent.
- Binary bytes (openInputStream on a part) are the ONE documented exception, and only for the bytes stream, not for the content-type probe (which still goes through simple_query). See `Query.kt:queryToFile` for the pattern.
- When adding a new query, check whether the URI is already routed through simple_query elsewhere; reuse the call site rather than duplicating.
