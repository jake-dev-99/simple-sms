/// Test-only read harness: in-memory Android content-provider tables served
/// through the `simple_query` platform interface (UNFY-253).
///
/// Every `SimpleQuery.instance` read in this plugin delegates to
/// [SimpleQueryPlatform.instance], so swapping that one instance makes the
/// plugin's singleton lookups (`ConversationLookup`, `MessageLookup`,
/// `ContactLookup`, `AttachmentExtractor`) unit-testable without injection.
library;

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simple_query_platform_interface/simple_query_platform_interface.dart';
import 'package:simple_query_platform_interface/testing.dart';

/// Purpose: names the Android provider URIs the plugin reads, so a test
/// seeds exactly the table production code queries.
///
/// The production constants are file-private by design; restating them here
/// pins the provider contract — a silent URI change in `lib/` fails the
/// tests that seed these tables instead of reading an empty fake.
abstract final class ProviderUris {
  /// `Telephony.Sms` rows.
  static const String sms = 'content://sms';

  /// `Telephony.Mms` rows.
  static const String mms = 'content://mms';

  /// `Telephony.Mms.Part` rows, keyed to their message by `mid`.
  static const String mmsParts = 'content://mms/part';

  /// One row per thread; `_id` is the thread id and there is no `thread_id`.
  static const String conversations =
      'content://mms-sms/conversations?simple=true';

  /// Purpose: builds the per-message MMS address table URI.
  ///
  /// @param mmsId the owning MMS `_id`.
  /// @returns the `content://mms/{mmsId}/addr` URI.
  /// @throws nothing.
  static String mmsAddresses(int mmsId) => 'content://mms/$mmsId/addr';

  /// Purpose: builds the canonical-address URI for a thread recipient id.
  ///
  /// @param recipientId one id from a conversation's `recipient_ids`.
  /// @returns the `content://mms-sms/canonical-address/{id}` URI.
  /// @throws nothing.
  static String canonicalAddress(String recipientId) =>
      'content://mms-sms/canonical-address/$recipientId';

  /// Purpose: builds the contacts phone-filter URI for an address.
  ///
  /// @param address the phone number being resolved.
  /// @returns the `data/phones/filter/{address}` URI, the address encoded as
  /// one path segment.
  /// @throws nothing.
  static String phoneLookup(String address) =>
      'content://com.android.contacts/data/phones/filter/'
      '${Uri.encodeComponent(address)}';

  /// Purpose: builds the contacts email-filter URI for an `@` address.
  ///
  /// @param address the email-shaped address being resolved.
  /// @returns the `data/emails/filter/{address}` URI, the address encoded as
  /// one path segment.
  /// @throws nothing.
  static String emailLookup(String address) =>
      'content://com.android.contacts/data/emails/filter/'
      '${Uri.encodeComponent(address)}';
}

/// Purpose: a [SimpleQueryPlatform] whose `query` evaluates each request
/// against seeded in-memory tables the way the Android backend's SQL would.
///
/// Filters are ANDed with SQLite semantics. A cell's Dart type stands for its
/// storage class: an int or double cell compares a numeric-looking argument as
/// a number, while a String cell compares it as text (so `'+1555…'` never
/// equals `'1555…'`). `NULL` never matches, `contains` is SQLite's
/// `LIKE '%value%'` (`%`/`_` wildcards, ASCII case-insensitive), and an empty
/// `inList` is rejected as `invalidQuery`, as the Dart backend does. Sorting
/// defaults to `_id ASC` as the backend's does, ties fall back to rowid
/// (`_id`) order, then `limit`/`offset` paging applies. A filter or sort on a
/// column the table lacks fails as the device does — a [PlatformException]
/// carrying SQLite's `no such column` — the OEM-schema bug class (e.g.
/// `thread_id` on the conversations view). Inject other provider failures
/// with [failQueriesTo] the same way: native failures reach Dart as
/// [PlatformException], Dart-side ones (e.g. `permissionDenied`) as
/// [SimpleQueryError].
///
/// Reading a URI nothing was seeded for throws [StateError], so every table a
/// code path reads must be declared; seed `const []` for an empty table.
/// Requests are recorded by [FakeSimpleQueryPlatform.queryRequests].
final class FakeContentProviderPlatform extends FakeSimpleQueryPlatform {
  final Map<String, _ProviderTable> _tables = <String, _ProviderTable>{};
  final Map<String, Object> _failures = <String, Object>{};

  /// Purpose: installs a fresh fake as [SimpleQueryPlatform.instance] for the
  /// current test and restores the previous instance when the test ends.
  ///
  /// @returns the installed fake, ready to seed.
  /// @throws whatever `addTearDown` throws when called outside a test body or
  /// `setUp` callback.
  static FakeContentProviderPlatform install() {
    final original = SimpleQueryPlatform.instance;
    final fake = FakeContentProviderPlatform();
    SimpleQueryPlatform.instance = fake;
    addTearDown(() => SimpleQueryPlatform.instance = original);
    return fake;
  }

  /// Purpose: appends rows to the table at [contentUri], creating it first.
  ///
  /// @param contentUri the exact URI production code queries.
  /// @param rows flat provider rows, as a cursor yields them; each is copied,
  /// so later edits to the caller's maps do not leak in.
  /// @param columns extra column names the table has beyond the keys of
  /// [rows] — needed to filter an empty table without `no such column`.
  /// @returns nothing.
  /// @throws [ArgumentError] for a cell a cursor cannot hold (anything but
  /// null, int, double, String, or Uint8List); nothing is seeded then.
  void seed(
    String contentUri,
    Iterable<QueryRecord> rows, {
    Iterable<String> columns = const <String>[],
  }) {
    final table = _tables[contentUri] ?? _ProviderTable();
    table.insertAll(rows: rows, columns: columns);
    _tables[contentUri] = table;
  }

  /// Purpose: makes every later query to [contentUri] throw [error], to
  /// exercise a caller's failure path.
  ///
  /// @param contentUri the exact URI whose reads should fail.
  /// @param error the object each matching query throws.
  /// @returns nothing.
  /// @throws nothing.
  void failQueriesTo(String contentUri, Object error) {
    _failures[contentUri] = error;
  }

  /// Purpose: lists the recorded requests sent to one URI, in call order.
  ///
  /// @param contentUri the exact URI to match.
  /// @returns the matching requests.
  /// @throws [ArgumentError] when a recorded request carries no content URI.
  List<QueryRequest> requestsTo(String contentUri) => queryRequests
      .where((request) => _contentUriOf(request) == contentUri)
      .toList(growable: false);

  /// Purpose: records the request, then answers it from the seeded table.
  ///
  /// @param request the platform-specific query production code issued.
  /// @returns the matching rows, sorted and paged per [request].
  /// @throws the error registered by [failQueriesTo] for the URI;
  /// [StateError] when no table was seeded for the URI; [PlatformException]
  /// for a missing column; [SimpleQueryError] for an empty `inList`;
  /// [UnsupportedError] for a projection, a cursor page, or `LIKE` on a blob,
  /// which no plugin read uses.
  @override
  Future<QueryResult> query(QueryRequest request) async {
    await super.query(request);
    final contentUri = _contentUriOf(request);
    final failure = _failures[contentUri];
    if (failure != null) throw failure;
    final table = _tables[contentUri];
    if (table == null) {
      throw StateError('No fake provider table seeded for $contentUri');
    }
    return QueryResult(records: table.select(request));
  }
}

/// Purpose: reads the content URI every plugin read carries in
/// `platformData`.
///
/// @param request the recorded or incoming request.
/// @returns the `contentUri` value.
/// @throws [ArgumentError] when `contentUri` is absent or not a String.
String _contentUriOf(QueryRequest request) {
  if (request.platformData?['contentUri'] case final String contentUri) {
    return contentUri;
  }
  throw ArgumentError.value(
    request.platformData,
    'request.platformData',
    'carries no contentUri String',
  );
}

/// Purpose: one provider table — its rows and its column set.
final class _ProviderTable {
  final List<QueryRecord> _rows = <QueryRecord>[];
  final Set<String> _columns = <String>{};

  /// Purpose: appends copies of [rows] and widens the column set, all or
  /// nothing.
  ///
  /// @param rows provider rows to store.
  /// @param columns column names beyond the keys of [rows].
  /// @returns nothing.
  /// @throws [ArgumentError] for a cell a cursor cannot hold (anything but
  /// null, int, double, String, or Uint8List), before any row is stored.
  void insertAll({
    required Iterable<QueryRecord> rows,
    required Iterable<String> columns,
  }) {
    final incoming = rows.toList(growable: false);
    for (final row in incoming) {
      row.forEach(_requireCursorValue);
    }
    for (final row in incoming) {
      _rows.add(_copyRow(row));
      _columns.addAll(row.keys);
    }
    _columns.addAll(columns);
  }

  /// Purpose: evaluates [request] as `SELECT * … WHERE … ORDER BY … LIMIT`.
  ///
  /// With no sort keys the order is `_id ASC`, the Android backend's default.
  /// Rows that tie on every key fall back to rowid (`_id`) order, then
  /// insertion order.
  ///
  /// @param request the incoming query.
  /// @returns copies of the selected rows.
  /// @throws as [FakeContentProviderPlatform.query] does for a malformed or
  /// unmodelled request.
  List<QueryRecord> select(QueryRequest request) {
    final sort = request.sort.isEmpty ? _defaultSort : request.sort;
    _validate(request, sort);
    final predicates = request.filters.map(_predicateFor).toList();
    final matches = <(int, QueryRecord)>[
      for (final (index, row) in _rows.indexed)
        if (predicates.every((matches) => matches(row))) (index, row),
    ];
    matches.sort((a, b) {
      final byKeys = _compareBySort(a.$2, b.$2, sort);
      if (byKeys != 0) return byKeys;
      final byRowid = _compareBySort(a.$2, b.$2, _defaultSort);
      return byRowid != 0 ? byRowid : a.$1.compareTo(b.$1);
    });
    return _paginate(
      matches.map((match) => match.$2),
      request.page,
    ).map(_copyRow).toList(growable: false);
  }

  /// Purpose: rejects what the real backend rejects, and what this fake does
  /// not model, before any row is read.
  ///
  /// @param request the incoming query.
  /// @param sort the effective sort keys, including the backend default.
  /// @returns nothing.
  /// @throws [PlatformException] for a column the table lacks;
  /// [UnsupportedError] for a projection or cursor page.
  void _validate(QueryRequest request, List<QuerySort> sort) {
    if (request.projection != null) {
      throw UnsupportedError('Fake provider tables do not model projection');
    }
    if (request.page?.cursor != null) {
      throw UnsupportedError('Fake provider tables do not model cursors');
    }
    final fields = <String>[
      for (final filter in request.filters) filter.field,
      for (final key in sort) key.field,
    ];
    for (final field in fields) {
      if (!_columns.contains(field)) throw _noSuchColumn(field);
    }
  }
}

/// The Android backend's `ORDER BY` when a request names no sort keys; also
/// rowid order, since provider tables key on `_id INTEGER PRIMARY KEY`.
const List<QuerySort> _defaultSort = <QuerySort>[QuerySort(field: '_id')];

/// SQLite's test for TEXT that converts losslessly to a number when compared
/// against a numeric column; anything else (hex, words) stays TEXT.
final RegExp _sqlNumericText = RegExp(
  r'^\s*[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?\s*$',
);

/// Purpose: rejects a cell value no Android cursor can produce, so seeded
/// tables stay flat and every copy is a full copy.
///
/// @param column the cell's column name, for the error.
/// @param value the cell value.
/// @returns nothing.
/// @throws [ArgumentError] for anything but null, int, double, String, or
/// Uint8List.
void _requireCursorValue(String column, Object? value) {
  if (value == null || value is num || value is String || value is Uint8List) {
    return;
  }
  throw ArgumentError.value(value, column, 'is not a cursor cell value');
}

/// Purpose: copies a flat provider row, including its blob cells.
///
/// @param row a row holding only cursor cell values.
/// @returns an independent copy.
/// @throws nothing.
QueryRecord _copyRow(QueryRecord row) => row.map(
  (column, value) =>
      MapEntry(column, value is Uint8List ? Uint8List.fromList(value) : value),
);

/// Purpose: compiles one SQL condition into a row predicate, binding its
/// value as a string the way the Android backend binds `selectionArgs`.
///
/// Compiled once per request, so an empty `inList` is rejected even when no
/// row would be read, as the backend rejects it before querying.
///
/// @param filter the condition.
/// @returns a predicate that is always false for a `NULL` cell.
/// @throws [SimpleQueryError] for an empty `inList`.
bool Function(QueryRecord) _predicateFor(QueryFilterCondition filter) {
  final argument = filter.value?.toString() ?? '';
  final test = switch (filter.operator) {
    QueryFilterOperator.equals =>
      (Object cell) => _compareToArgument(cell, argument) == 0,
    QueryFilterOperator.notEquals =>
      (Object cell) => _compareToArgument(cell, argument) != 0,
    QueryFilterOperator.greaterThan =>
      (Object cell) => _compareToArgument(cell, argument) > 0,
    QueryFilterOperator.greaterThanOrEqual =>
      (Object cell) => _compareToArgument(cell, argument) >= 0,
    QueryFilterOperator.lessThan =>
      (Object cell) => _compareToArgument(cell, argument) < 0,
    QueryFilterOperator.lessThanOrEqual =>
      (Object cell) => _compareToArgument(cell, argument) <= 0,
    QueryFilterOperator.contains => _likePredicate('%$argument%'),
    QueryFilterOperator.inList => _inListPredicate(filter),
  };
  return (row) => switch (row[filter.field]) {
    null => false,
    final Object cell => test(cell),
  };
}

/// Purpose: compiles an `inList` condition's bound arguments.
///
/// @param filter an `inList` condition.
/// @returns a cell predicate matching any listed argument.
/// @throws [SimpleQueryError] when the list is empty, as the backend does.
bool Function(Object) _inListPredicate(QueryFilterCondition filter) {
  final values = filter.value;
  if (values is! List<Object?> || values.isEmpty) {
    throw _invalidQuery('inList filter requires a non-empty List value');
  }
  final arguments = values.map((item) => item.toString()).toList();
  return (cell) =>
      arguments.any((argument) => _compareToArgument(cell, argument) == 0);
}

/// Purpose: compares a cell with a bound TEXT argument under column affinity.
///
/// A numeric cell stands for a numeric column, which converts a numeric-
/// looking argument to a number; a String cell stands for a TEXT column,
/// which compares the argument as text.
///
/// @param cell a non-null cell value.
/// @param argument the bound argument.
/// @returns negative, zero, or positive.
/// @throws nothing.
int _compareToArgument(Object cell, String argument) {
  final coerced =
      cell is num && _sqlNumericText.hasMatch(argument)
          ? num.parse(argument.trim())
          : argument;
  return _compareSqlValues(cell, coerced);
}

/// Purpose: orders two non-null SQL values the way SQLite does: numbers
/// before text before blobs, numbers numerically, text by code unit.
///
/// @param left a cell or coerced argument.
/// @param right a cell or coerced argument.
/// @returns negative, zero, or positive.
/// @throws nothing.
int _compareSqlValues(Object left, Object right) {
  final byClass = _storageClassRank(left).compareTo(_storageClassRank(right));
  if (byClass != 0) return byClass;
  return switch ((left, right)) {
    (final num l, final num r) => l.compareTo(r),
    (final String l, final String r) => l.compareTo(r),
    _ => 0, // Two blobs: no plugin read orders or filters on a blob column.
  };
}

/// Purpose: ranks a value's SQLite storage class for cross-class ordering.
///
/// @param value a non-null cell or coerced argument.
/// @returns 0 for numbers, 1 for text, 2 for blobs.
/// @throws nothing.
int _storageClassRank(Object value) => switch (value) {
  num() => 0,
  String() => 1,
  _ => 2,
};

const int _likeAnyRun = 0x25; // '%'
const int _likeAnyOne = 0x5F; // '_'
const int _asciiUpperA = 0x41;
const int _asciiUpperZ = 0x5A;
const int _asciiCaseOffset = 0x20;

/// Purpose: compiles a SQLite `LIKE` pattern — `%` matches any run, `_` one
/// character (code point), ASCII letters case-insensitively, the cell read as
/// text.
///
/// @param pattern the bound `LIKE` pattern.
/// @returns a cell predicate; it throws [UnsupportedError] for a blob cell,
/// whose byte-level `LIKE` this fake does not model.
/// @throws nothing.
bool Function(Object) _likePredicate(String pattern) {
  final compiled = _asciiLower(pattern).runes.toList(growable: false);
  return (cell) {
    if (cell is Uint8List) {
      throw UnsupportedError('Fake provider tables do not model LIKE on blobs');
    }
    final text = _asciiLower(cell.toString()).runes.toList(growable: false);
    return _likeMatches(text, compiled);
  };
}

/// Purpose: matches code points against a `LIKE` pattern with single-star
/// backtracking — linear in practice, never exponential.
///
/// @param text the cell's code points.
/// @param pattern the pattern's code points.
/// @returns whether the whole text matches.
/// @throws nothing.
bool _likeMatches(List<int> text, List<int> pattern) {
  var t = 0;
  var p = 0;
  var lastRun = -1;
  var resumeAt = 0;
  while (t < text.length) {
    final symbol = p < pattern.length ? pattern[p] : null;
    if (symbol == _likeAnyRun) {
      lastRun = p++;
      resumeAt = t;
    } else if (symbol == _likeAnyOne || symbol == text[t]) {
      t++;
      p++;
    } else if (lastRun >= 0) {
      p = lastRun + 1;
      t = ++resumeAt;
    } else {
      return false;
    }
  }
  while (p < pattern.length && pattern[p] == _likeAnyRun) {
    p++;
  }
  return p == pattern.length;
}

/// Purpose: lower-cases only ASCII letters, as SQLite's default `LIKE` does.
///
/// @param text any text.
/// @returns [text] with `A`–`Z` lowered.
/// @throws nothing.
String _asciiLower(String text) => String.fromCharCodes(
  text.codeUnits.map(
    (unit) =>
        unit >= _asciiUpperA && unit <= _asciiUpperZ
            ? unit + _asciiCaseOffset
            : unit,
  ),
);

/// Purpose: applies `LIMIT`/`OFFSET` to already-ordered rows.
///
/// @param rows the ordered matches.
/// @param page the requested page, or null for every row.
/// @returns the paged slice.
/// @throws nothing.
Iterable<QueryRecord> _paginate(Iterable<QueryRecord> rows, QueryPage? page) {
  final offset = page?.offset ?? 0;
  final limit = page?.limit;
  final skipped = rows.skip(offset);
  return limit == null ? skipped : skipped.take(limit);
}

/// Purpose: orders two rows by each sort key in turn, `NULL` lowest, as
/// SQLite does.
///
/// @param a the left row.
/// @param b the right row.
/// @param sort the `ORDER BY` keys.
/// @returns negative, zero, or positive.
/// @throws nothing.
int _compareBySort(QueryRecord a, QueryRecord b, List<QuerySort> sort) {
  for (final key in sort) {
    final comparison = switch ((a[key.field], b[key.field])) {
      (null, null) => 0,
      (null, _) => -1,
      (_, null) => 1,
      (final Object l, final Object r) => _compareSqlValues(l, r),
    };
    if (comparison != 0) {
      return key.direction == QuerySortDirection.ascending
          ? comparison
          : -comparison;
    }
  }
  return 0;
}

/// Purpose: builds the error a device raises for a column the provider lacks:
/// the native `SQLiteException`, which Pigeon delivers as a
/// [PlatformException] named after the exception class.
///
/// @param column the missing column.
/// @returns the device-shaped [PlatformException].
/// @throws nothing.
PlatformException _noSuchColumn(String column) => PlatformException(
  code: 'SQLiteException',
  message:
      'android.database.sqlite.SQLiteException: no such column: $column '
      '(code 1 SQLITE_ERROR)',
);

/// Purpose: builds the error the Dart backend raises for a malformed query.
///
/// @param message what was malformed.
/// @returns an `invalidQuery` [SimpleQueryError].
/// @throws nothing.
SimpleQueryError _invalidQuery(String message) => SimpleQueryError(
  code: SimpleQueryErrorCode.invalidQuery,
  message: 'fake provider: $message',
);
