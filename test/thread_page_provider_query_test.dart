import 'package:flutter_test/flutter_test.dart';
import 'package:simple_query_platform_interface/simple_query_platform_interface.dart';
import 'package:simple_sms_native/android.dart';

void main() {
  late SimpleQueryPlatform original;
  late _Provider provider;
  final at = DateTime.utc(2026, 9, 1, 12);

  setUp(() {
    original = SimpleQueryPlatform.instance;
    provider = _Provider();
    SimpleQueryPlatform.instance = provider;
  });
  tearDown(() => SimpleQueryPlatform.instance = original);

  test(
    'real query filters and sorting drain same-date, same-id SMS/MMS ties',
    () async {
      // Ascending input deliberately disagrees with the required native order.
      for (var id = 1; id <= 9; id++) {
        provider.sms.add(_sms(id, at));
        provider.mms.add(_mms(id, at));
      }
      provider.sms.add(_sms(99, at.subtract(const Duration(seconds: 1))));
      provider.mms.add({..._mms(100, at), 'm_type': 0x82}); // transport-only
      provider.sms.add({..._sms(101, at), 'thread_id': 8});
      final lookup = LookupService();
      ThreadPageCursor? cursor;
      final seen = <(SmsMmsType, int)>[];
      for (var pages = 0; pages < 10; pages++) {
        final page = await lookup.getNormalizedThreadPage(
          7,
          limit: 3,
          cursor: cursor,
        );
        seen.addAll(page.map((m) => (m.channel, m.id)));
        if (page.length < 3) break;
        cursor = ThreadPageCursor.fromMessage(page.last);
      }
      expect(seen, [
        for (var id = 9; id >= 1; id--) ...[
          (SmsMmsType.mms, id),
          (SmsMmsType.sms, id),
        ],
        (SmsMmsType.sms, 99),
      ]);
      expect(
        provider.messageQueries.map((q) => q.page?.limit),
        everyElement(3),
      );
      expect(
        provider.hydrated,
        unorderedEquals([for (var id = 1; id <= 9; id++) id]),
      );
    },
  );

  test(
    'fractional SMS cursor includes older MMS in the same second once',
    () async {
      final smsAt = at.add(const Duration(milliseconds: 500));
      provider.sms.addAll([_sms(2, smsAt), _sms(1, smsAt)]);
      provider.mms.add(_mms(7, at));
      final lookup = LookupService();
      final first = await lookup.getNormalizedThreadPage(7, limit: 1);
      final second = await lookup.getNormalizedThreadPage(
        7,
        limit: 3,
        cursor: ThreadPageCursor.fromMessage(first.single),
      );
      expect(second.map((m) => (m.channel, m.id)), [
        (SmsMmsType.sms, 1),
        (SmsMmsType.mms, 7),
      ]);
      expect(provider.hydrated, [7]);
    },
  );

  test('timestamp-only callers keep an exclusive boundary', () async {
    provider.sms.addAll([
      _sms(3, at),
      _sms(2, at.subtract(const Duration(milliseconds: 1))),
    ]);
    provider.mms.addAll([
      _mms(3, at),
      _mms(2, at.subtract(const Duration(seconds: 1))),
    ]);
    final page = await LookupService().getNormalizedThreadPage(
      7,
      limit: 5,
      before: at,
    );
    expect(page.map((m) => (m.channel, m.id)), [
      (SmsMmsType.sms, 2),
      (SmsMmsType.mms, 2),
    ]);
  });

  test(
    'invalid bounds or ambiguous cursors never query the provider',
    () async {
      final lookup = LookupService();
      expect(await lookup.getNormalizedThreadPage(7, limit: 0), isEmpty);
      await expectLater(
        lookup.getNormalizedThreadPage(7, limit: -1),
        throwsRangeError,
      );
      await expectLater(
        lookup.getNormalizedThreadPage(
          7,
          limit: 1,
          before: at,
          cursor: ThreadPageCursor(sentAt: at, id: 1, channel: SmsMmsType.sms),
        ),
        throwsArgumentError,
      );
      expect(provider.messageQueries, isEmpty);
    },
  );
}

Map<String, Object?> _sms(int id, DateTime at) => {
  '_id': id,
  'thread_id': 7,
  'date': at.millisecondsSinceEpoch,
  'type': 1,
};
Map<String, Object?> _mms(int id, DateTime at) => {
  '_id': id,
  'thread_id': 7,
  'date': at.millisecondsSinceEpoch ~/ 1000,
  'm_type': 0x84,
  'read': 1,
};

// Execute the generated typed queries against small raw provider tables. This
// exercises MessageLookup's filter/sort translation as well as page assembly.
class _Provider extends SimpleQueryPlatform {
  final sms = <Map<String, Object?>>[];
  final mms = <Map<String, Object?>>[];
  final messageQueries = <QueryRequest>[];
  final hydrated = <int>[];

  @override
  Future<QueryResult> query(QueryRequest request) async {
    final uri = request.platformData?['contentUri'];
    if (uri != 'content://sms' && uri != 'content://mms') {
      if (uri == 'content://mms/part') {
        hydrated.add(int.parse(request.filters.single.value.toString()));
      }
      return const QueryResult(records: []);
    }
    messageQueries.add(request);
    final source = uri == 'content://sms' ? sms : mms;
    final rows =
        source
            .where(
              (row) => request.filters.every((f) {
                final value = row[f.field];
                if (f.operator == QueryFilterOperator.inList) {
                  return (f.value as List).contains(value.toString());
                }
                if (value == null) return false;
                final comparison = (value as int).compareTo(
                  int.parse(f.value.toString()),
                );
                return switch (f.operator) {
                  QueryFilterOperator.equals => comparison == 0,
                  QueryFilterOperator.lessThan => comparison < 0,
                  QueryFilterOperator.lessThanOrEqual => comparison <= 0,
                  QueryFilterOperator.greaterThanOrEqual => comparison >= 0,
                  _ =>
                    throw UnsupportedError('Unexpected operator ${f.operator}'),
                };
              }),
            )
            .toList();
    rows.sort((a, b) {
      for (final sort in request.sort) {
        final comparison = (a[sort.field] as int).compareTo(
          b[sort.field] as int,
        );
        if (comparison != 0) {
          return sort.direction == QuerySortDirection.ascending
              ? comparison
              : -comparison;
        }
      }
      return 0;
    });
    return QueryResult(
      records: rows.take(request.page?.limit ?? rows.length).toList(),
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
