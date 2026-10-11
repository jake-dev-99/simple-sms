import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:simple_query_platform_interface/simple_query_platform_interface.dart';

import 'support/fake_content_provider_platform.dart';

// The harness stands in for the Android backend's SQL, so its semantics are
// pinned here once rather than re-proven by every test that relies on them.
void main() {
  late FakeContentProviderPlatform provider;
  const uri = 'content://fake/table';

  setUp(() => provider = FakeContentProviderPlatform.install());

  Future<List<Object?>> ids(
    List<QueryFilterCondition> filters, {
    List<QuerySort> sort = const [],
    QueryPage? page,
  }) async {
    final result = await provider.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        filters: filters,
        sort: sort,
        page: page,
        platformData: const {'contentUri': uri},
      ),
    );
    return result.records.map((row) => row['_id']).toList();
  }

  QueryFilterCondition where(
    String field,
    QueryFilterOperator operator,
    Object value,
  ) => QueryFilterCondition(field: field, operator: operator, value: value);

  test('install swaps the platform instance for this test only', () {
    expect(SimpleQueryPlatform.instance, same(provider));
  });

  test(
    'string-bound arguments compare numerically and NULL never matches',
    () async {
      provider.seed(uri, [
        {'_id': 1, 'date': 9},
        {'_id': 2, 'date': 10},
        {'_id': 3, 'date': null},
      ]);
      expect(await ids([where('date', QueryFilterOperator.lessThan, '10')]), [
        1,
      ]);
      expect(await ids([where('date', QueryFilterOperator.notEquals, '10')]), [
        1,
      ]);
      expect(
        await ids([
          where('date', QueryFilterOperator.inList, ['10', '11']),
        ]),
        [2],
      );
    },
  );

  test('contains is a case-insensitive substring match', () async {
    provider.seed(uri, [
      {'_id': 1, 'ct': 'text/plain'},
      {'_id': 2, 'ct': 'image/JPEG'},
    ]);
    expect(await ids([where('ct', QueryFilterOperator.contains, 'jpeg')]), [2]);
  });

  test('sorts by every key with NULL lowest, then pages', () async {
    provider.seed(uri, [
      {'_id': 1, 'date': 5},
      {'_id': 2, 'date': 7},
      {'_id': 3, 'date': 7},
      {'_id': 4, 'date': null},
    ]);
    const newest = [
      QuerySort(field: 'date', direction: QuerySortDirection.descending),
      QuerySort(field: '_id', direction: QuerySortDirection.descending),
    ];
    expect(await ids(const [], sort: newest), [3, 2, 1, 4]);
    expect(
      await ids(const [], sort: newest, page: QueryPage(limit: 2, offset: 1)),
      [2, 1],
    );
  });

  test('returns copies, so callers cannot mutate the seeded table', () async {
    provider.seed(uri, [
      {'_id': 1},
    ]);
    final first = await provider.query(
      const QueryRequest(
        domain: QueryDomain.platformSpecific,
        platformData: {'contentUri': uri},
      ),
    );
    first.records.single['_id'] = 99;
    expect(await ids(const []), [1]);
  });

  test(
    'a missing column fails like SQLite instead of matching nothing',
    () async {
      provider.seed(uri, [
        {'_id': 1},
      ]);
      await expectLater(
        ids([where('thread_id', QueryFilterOperator.equals, '1')]),
        throwsA(
          isA<SimpleQueryError>()
              .having((e) => e.code, 'code', SimpleQueryErrorCode.invalidQuery)
              .having((e) => e.message, 'message', contains('thread_id')),
        ),
      );
    },
  );

  test('declared columns let an empty table be filtered', () async {
    provider.seed(uri, const [], columns: const ['mid']);
    expect(await ids([where('mid', QueryFilterOperator.equals, '1')]), isEmpty);
  });

  test('an empty inList is rejected as the backend rejects it', () async {
    provider.seed(uri, [
      {'_id': 1},
    ]);
    await expectLater(
      ids([where('_id', QueryFilterOperator.inList, const <String>[])]),
      throwsA(isA<SimpleQueryError>()),
    );
  });

  test('an unseeded URI is a test bug, not an empty result', () async {
    await expectLater(ids(const []), throwsStateError);
  });

  test(
    'failQueriesTo throws the given error and still records the request',
    () async {
      final failure = Exception('provider down');
      provider
        ..seed(uri, const [])
        ..failQueriesTo(uri, failure);
      await expectLater(ids(const []), throwsA(same(failure)));
      expect(provider.requestsTo(uri), hasLength(1));
    },
  );

  test('requestsTo filters the recorded requests by URI', () async {
    provider
      ..seed(uri, const [])
      ..seed('content://fake/other', const []);
    await ids(const []);
    await provider.query(
      const QueryRequest(
        domain: QueryDomain.platformSpecific,
        platformData: {'contentUri': 'content://fake/other'},
      ),
    );
    expect(provider.requestsTo(uri), hasLength(1));
    expect(provider.queryRequests, hasLength(2));
  });

  test('projection and cursor paging are rejected, not ignored', () async {
    provider.seed(uri, const []);
    await expectLater(
      provider.query(
        const QueryRequest(
          domain: QueryDomain.platformSpecific,
          projection: ['_id'],
          platformData: {'contentUri': uri},
        ),
      ),
      throwsUnsupportedError,
    );
    await expectLater(
      ids(const [], page: const QueryPage.cursor(cursor: 'next')),
      throwsUnsupportedError,
    );
  });

  test('a TEXT cell compares as text, never coerced to a number', () async {
    provider.seed(uri, [
      {'_id': 1, 'address': '+15550001111'},
      {'_id': 2, 'address': '15550001111'},
      {'_id': 3, 'address': '9'},
      {'_id': 4, 'address': '10'},
    ]);
    expect(
      await ids([where('address', QueryFilterOperator.equals, '15550001111')]),
      [2],
    );
    // Text order: '10' < '9'.
    expect(await ids([where('address', QueryFilterOperator.lessThan, '9')]), [
      1,
      2,
      4,
    ]);
  });

  test('a numeric cell converts only a numeric literal, not hex', () async {
    provider.seed(uri, [
      {'_id': 16},
    ]);
    expect(await ids([where('_id', QueryFilterOperator.equals, '16.0')]), [16]);
    expect(await ids([where('_id', QueryFilterOperator.equals, '0x10')]), []);
  });

  test('contains honours LIKE wildcards', () async {
    provider.seed(uri, [
      {'_id': 1, 'ct': 'image/jpeg'},
      {'_id': 2, 'ct': 'image_jpeg'},
    ]);
    expect(await ids([where('ct', QueryFilterOperator.contains, 'image_')]), [
      1,
      2,
    ]);
    expect(await ids([where('ct', QueryFilterOperator.contains, 'e%g')]), [
      1,
      2,
    ]);
  });

  test('no sort keys means _id ASC; full ties keep insertion order', () async {
    provider.seed(uri, [
      {'_id': 3, 'mid': 1},
      {'_id': 1, 'mid': 2},
      {'_id': 2, 'mid': 1},
    ]);
    expect(await ids(const []), [1, 2, 3]);
    expect(await ids(const [], sort: const [QuerySort(field: 'mid')]), [
      3,
      2,
      1,
    ]);
  });

  test('seed rejects a value no cursor can hold', () {
    expect(
      () => provider.seed(uri, [
        {
          '_id': 1,
          'parts': [1, 2],
        },
      ]),
      throwsArgumentError,
    );
  });

  test('blob cells are copied in and out', () async {
    final blob = Uint8List.fromList([1, 2]);
    provider.seed(uri, [
      {'_id': 1, 'data15': blob},
    ]);
    blob[0] = 9;
    Future<Object?> read() async =>
        (await provider.query(
          const QueryRequest(
            domain: QueryDomain.platformSpecific,
            platformData: {'contentUri': uri},
          ),
        )).records.single['data15'];
    final first = await read();
    if (first is! Uint8List) fail('expected a blob cell');
    first[1] = 9;
    expect(await read(), [1, 2]);
  });
}
