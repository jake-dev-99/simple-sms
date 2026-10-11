import 'dart:math';

import 'package:simple_query/simple_query.dart';

/// Most ids bound into one `IN (...)` filter. SQLite before 3.32 (older
/// Android releases) rejects a statement with more than 999 bind arguments;
/// 500 leaves headroom for the request's other filters (UNFY-253).
const int maxIdsPerQuery = 500;

/// The Android backend's `ORDER BY` when a request names no sort keys.
const List<QuerySort> _providerDefaultSort = <QuerySort>[
  QuerySort(field: '_id'),
];

/// Purpose: runs [request] through `simple_query`, splitting an `inList`
/// filter longer than [maxIdsPerQuery] into chunk queries whose rows are
/// merged in the request's order and paged as the single query would page
/// them.
///
/// The merge reproduces SQLite's order exactly only for integer columns, so
/// the request is split only when every sort column — the provider's
/// `_id ASC` default when it names none — is in [numericSortColumns], and it
/// pages by `limit`/`offset` rather than a cursor. Otherwise it runs as one
/// query, as before. Chunks run one after another, each capped at
/// `offset + limit` rows; the first failure fails the call. Duplicate ids are
/// queried once, matching `IN` set semantics.
///
/// @param request the query, as it would be sent unsplit.
/// @param numericSortColumns the queried table's columns that hold integers.
/// @returns the selected rows.
/// @throws [ArgumentError] when more than one `inList` filter exceeds
/// [maxIdsPerQuery]; [StateError] when a row holds a non-integer value in a
/// numeric sort column; whatever `simple_query` throws for a chunk.
Future<List<QueryRecord>> queryWithChunkedIdList(
  QueryRequest request, {
  required Set<String> numericSortColumns,
}) async {
  final oversized = request.filters.where(_isOversizedIdList).toList();
  if (oversized.isEmpty || !_canMerge(request, numericSortColumns)) {
    return (await SimpleQuery.instance.query(request)).records;
  }
  if (oversized.length > 1) {
    throw ArgumentError.value(
      request,
      'request',
      'has more than one inList filter longer than $maxIdsPerQuery',
    );
  }
  final idFilter = oversized.single;
  final rows = <QueryRecord>[];
  for (final chunk in _chunkedIds(idFilter)) {
    final response = await SimpleQuery.instance.query(
      _chunkRequest(request, idFilter: idFilter, ids: chunk),
    );
    rows.addAll(response.records);
  }
  return _mergeAndPage(rows, request);
}

/// Purpose: tells whether [filter] is an id list too long for one query.
///
/// @param filter a request filter.
/// @returns whether it is an `inList` of more than [maxIdsPerQuery] values.
/// @throws nothing.
bool _isOversizedIdList(QueryFilterCondition filter) =>
    filter.operator == QueryFilterOperator.inList &&
    switch (filter.value) {
      final List<Object?> ids => ids.length > maxIdsPerQuery,
      _ => false,
    };

/// Purpose: tells whether chunk results can be merged into exactly the order
/// and page the single query would return.
///
/// @param request the unsplit query.
/// @param numericSortColumns the table's integer columns.
/// @returns whether every sort column is numeric and paging is offset-based.
/// @throws nothing.
bool _canMerge(QueryRequest request, Set<String> numericSortColumns) {
  final sort = request.sort.isEmpty ? _providerDefaultSort : request.sort;
  final sortsNumerically = sort.every(
    (key) => numericSortColumns.contains(key.field),
  );
  return sortsNumerically && request.page?.cursor == null;
}

/// Purpose: splits an id list into deduplicated chunks of at most
/// [maxIdsPerQuery].
///
/// @param idFilter an oversized `inList` filter.
/// @returns the chunks, in first-occurrence order.
/// @throws [StateError] when the filter's value is not a list.
List<List<Object?>> _chunkedIds(QueryFilterCondition idFilter) {
  final ids = switch (idFilter.value) {
    final List<Object?> values => <Object?>{...values}.toList(),
    final other => throw StateError('inList value is not a List: $other'),
  };
  return [
    for (var start = 0; start < ids.length; start += maxIdsPerQuery)
      ids.sublist(start, min(start + maxIdsPerQuery, ids.length)),
  ];
}

/// Purpose: builds one chunk's query — the request with [idFilter] narrowed
/// to [ids] and paging reduced to a top-`offset + limit` cap.
///
/// @param request the unsplit query.
/// @param idFilter the oversized `inList` filter being split.
/// @param ids this chunk's ids.
/// @returns the chunk query.
/// @throws nothing.
QueryRequest _chunkRequest(
  QueryRequest request, {
  required QueryFilterCondition idFilter,
  required List<Object?> ids,
}) {
  final limit = request.page?.limit;
  return request.copyWith(
    filters: [
      for (final filter in request.filters)
        identical(filter, idFilter) ? filter.copyWith(value: ids) : filter,
    ],
    page:
        limit == null
            ? null
            : QueryPage(limit: (request.page?.offset ?? 0) + limit),
  );
}

/// Purpose: orders merged chunk rows as SQLite would order the single query
/// and applies its `LIMIT`/`OFFSET` (offset only alongside a limit, as the
/// backend sends it).
///
/// @param rows every chunk's rows, in chunk order.
/// @param request the unsplit query.
/// @returns the page the single query would return.
/// @throws [StateError] when a row holds a non-integer value in a sort column.
List<QueryRecord> _mergeAndPage(List<QueryRecord> rows, QueryRequest request) {
  final sort = request.sort.isEmpty ? _providerDefaultSort : request.sort;
  final indexed = rows.indexed.toList();
  indexed.sort((a, b) {
    final byKeys = _compareRows(a.$2, b.$2, sort);
    // SQLite leaves full ties unordered; chunk order keeps this deterministic.
    return byKeys != 0 ? byKeys : a.$1.compareTo(b.$1);
  });
  final ordered = indexed.map((entry) => entry.$2);
  final limit = request.page?.limit;
  if (limit == null) return ordered.toList(growable: false);
  return ordered
      .skip(request.page?.offset ?? 0)
      .take(limit)
      .toList(growable: false);
}

/// Purpose: compares two rows by integer sort keys, `NULL` lowest.
///
/// @param a the left row.
/// @param b the right row.
/// @param sort the `ORDER BY` keys, all integer columns.
/// @returns negative, zero, or positive.
/// @throws [StateError] when a sort cell is not an integer.
int _compareRows(QueryRecord a, QueryRecord b, List<QuerySort> sort) {
  for (final key in sort) {
    final comparison = switch ((
      _integerCell(a, key.field),
      _integerCell(b, key.field),
    )) {
      (null, null) => 0,
      (null, _) => -1,
      (_, null) => 1,
      (final int left, final int right) => left.compareTo(right),
    };
    if (comparison != 0) {
      return key.direction == QuerySortDirection.ascending
          ? comparison
          : -comparison;
    }
  }
  return 0;
}

/// Purpose: reads an integer sort cell, accepting the stringified integers
/// some OEM providers return.
///
/// @param row a provider row.
/// @param column an integer column.
/// @returns the value, or null for `NULL`.
/// @throws [StateError] when the cell is neither null nor an integer.
int? _integerCell(QueryRecord row, String column) {
  final value = row[column];
  if (value == null) return null;
  if (value is int) return value;
  if (value is String) {
    final parsed = int.tryParse(value.trim());
    if (parsed != null) return parsed;
  }
  throw StateError(
    'Cannot merge chunked rows: column "$column" holds non-integer $value',
  );
}
