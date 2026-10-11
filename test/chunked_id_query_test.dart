import 'package:flutter_test/flutter_test.dart';
import 'package:simple_query_platform_interface/simple_query_platform_interface.dart';
import 'package:simple_sms_native/android.dart';
import 'package:simple_sms_native/src/android/services/chunked_id_query.dart';

import 'support/fake_content_provider_platform.dart';

// queryWithChunkedIdList must return exactly what one unsplit query would,
// while never binding more than maxIdsPerQuery ids per statement (UNFY-253).
void main() {
  late FakeContentProviderPlatform provider;
  const uri = 'content://fake/messages';
  const integerColumns = {'_id', 'date'};

  setUp(() => provider = FakeContentProviderPlatform.install());

  // Dates deliberately disagree with id order, with ties, so the merge has
  // to re-sort across chunks.
  List<Map<String, Object?>> seedRows(int count) => [
    for (var id = 1; id <= count; id++) {'_id': id, 'date': (id * 7919) % 97},
  ];

  QueryRequest request({
    required List<String> ids,
    List<QuerySort> sort = const [],
    QueryPage? page,
  }) => QueryRequest(
    domain: QueryDomain.platformSpecific,
    filters: [
      QueryFilterCondition(
        field: '_id',
        operator: QueryFilterOperator.inList,
        value: ids,
      ),
    ],
    sort: sort,
    page: page,
    platformData: const {'contentUri': uri},
  );

  List<int> idListSizes() => [
    for (final sent in provider.requestsTo(uri))
      switch (sent.filters.single.value) {
        final List<Object?> ids => ids.length,
        final other => throw StateError('expected an id list: $other'),
      },
  ];

  List<String> idsUpTo(int count) => [
    for (var id = 1; id <= count; id++) '$id',
  ];

  test('sends a request within the limit unchanged', () async {
    provider.seed(uri, seedRows(10));
    final unsplit = request(
      ids: idsUpTo(maxIdsPerQuery),
      page: QueryPage(limit: 3),
    );

    final rows = await queryWithChunkedIdList(
      unsplit,
      numericSortColumns: integerColumns,
    );

    expect(provider.requestsTo(uri), [same(unsplit)]);
    expect(rows.map((r) => r['_id']), [1, 2, 3]);
  });

  test(
    'merges chunks into exactly the sorted, paged result of one query',
    () async {
      const count = 1200;
      final table = seedRows(count);
      provider.seed(uri, table);
      const newest = [
        QuerySort(field: 'date', direction: QuerySortDirection.descending),
        QuerySort(field: '_id', direction: QuerySortDirection.descending),
      ];
      int dateOf(int id) => (id * 7919) % 97;
      final expected =
          ([for (var id = 1; id <= count; id++) id]..sort((a, b) {
            final byDate = dateOf(b).compareTo(dateOf(a));
            return byDate != 0 ? byDate : b.compareTo(a);
          })).skip(5).take(10).toList();

      final rows = await queryWithChunkedIdList(
        request(
          ids: idsUpTo(count),
          sort: newest,
          page: QueryPage(limit: 10, offset: 5),
        ),
        numericSortColumns: integerColumns,
      );

      expect(rows.map((r) => r['_id']), expected);
      expect(idListSizes(), [500, 500, 200]);
      expect(
        provider.requestsTo(uri).map((r) => (r.page?.limit, r.page?.offset)),
        everyElement((15, null)),
      );
    },
  );

  test('with no sort keys, merges by the provider default _id ASC', () async {
    provider.seed(uri, seedRows(700).reversed.toList());

    final rows = await queryWithChunkedIdList(
      request(ids: idsUpTo(700).reversed.toList()),
      numericSortColumns: integerColumns,
    );

    expect(rows.map((r) => r['_id']), [for (var id = 1; id <= 700; id++) id]);
  });

  test('queries duplicate ids once, as IN does', () async {
    provider.seed(uri, seedRows(600));

    final rows = await queryWithChunkedIdList(
      request(ids: [...idsUpTo(600), ...idsUpTo(300)]),
      numericSortColumns: integerColumns,
    );

    expect(idListSizes(), [500, 100]);
    expect(rows, hasLength(600));
  });

  test('merges stringified integers and puts NULL lowest', () async {
    provider.seed(uri, [
      for (var id = 1; id <= 600; id++)
        {'_id': id, 'date': id == 600 ? null : '${1000 - id}'},
    ]);

    final rows = await queryWithChunkedIdList(
      request(ids: idsUpTo(600), sort: const [QuerySort(field: 'date')]),
      numericSortColumns: integerColumns,
    );

    expect(rows.first['_id'], 600);
    expect(rows.skip(1).first['_id'], 599);
    expect(rows.last['_id'], 1);
  });

  test(
    'keeps one unsplit query when a sort column is not an integer column',
    () async {
      provider.seed(uri, [
        for (var id = 1; id <= 600; id++) {'_id': id, 'name': 'n$id'},
      ]);
      final unsplit = request(
        ids: idsUpTo(600),
        sort: const [QuerySort(field: 'name')],
      );

      await queryWithChunkedIdList(unsplit, numericSortColumns: integerColumns);

      expect(provider.requestsTo(uri), [same(unsplit)]);
    },
  );

  test('keeps one unsplit query for cursor paging', () async {
    provider.seed(uri, seedRows(600));
    final unsplit = request(
      ids: idsUpTo(600),
      page: const QueryPage.cursor(cursor: '1'),
    );

    // The fake does not model cursors; reaching it at all proves no split.
    await expectLater(
      queryWithChunkedIdList(unsplit, numericSortColumns: integerColumns),
      throwsUnsupportedError,
    );
    expect(provider.requestsTo(uri), [same(unsplit)]);
  });

  test('rejects two oversized id lists before querying', () async {
    provider.seed(uri, seedRows(1), columns: const ['thread_id']);
    final twoLists = request(ids: idsUpTo(600)).copyWith(
      filters: [
        QueryFilterCondition(
          field: '_id',
          operator: QueryFilterOperator.inList,
          value: idsUpTo(600),
        ),
        QueryFilterCondition(
          field: 'thread_id',
          operator: QueryFilterOperator.inList,
          value: idsUpTo(600),
        ),
      ],
    );

    await expectLater(
      queryWithChunkedIdList(twoLists, numericSortColumns: integerColumns),
      throwsArgumentError,
    );
    expect(provider.requestsTo(uri), isEmpty);
  });

  test(
    'fails loudly on a non-integer value in an integer sort column',
    () async {
      provider.seed(uri, [
        for (var id = 1; id <= 600; id++)
          {'_id': id, 'date': id == 7 ? 'x' : id},
      ]);

      await expectLater(
        queryWithChunkedIdList(
          request(ids: idsUpTo(600), sort: const [QuerySort(field: 'date')]),
          numericSortColumns: integerColumns,
        ),
        throwsStateError,
      );
    },
  );

  test('stops at the first failed chunk', () async {
    final failure = const SimpleQueryError(
      code: SimpleQueryErrorCode.transientFailure,
      message: 'busy',
    );
    provider
      ..seed(uri, seedRows(1200))
      ..failQueriesTo(uri, failure);

    await expectLater(
      queryWithChunkedIdList(
        request(ids: idsUpTo(1200)),
        numericSortColumns: integerColumns,
      ),
      throwsA(same(failure)),
    );
    expect(provider.requestsTo(uri), hasLength(1));
  });

  group('list APIs route oversized id filters through chunking', () {
    test('listSms returns the exact page across chunks', () async {
      provider.seed(ProviderUris.sms, [
        for (var id = 1; id <= 1200; id++)
          {'_id': id, 'thread_id': 1, 'date': id * 1000, 'type': 1},
      ]);

      final page = await LookupService().listSms(
        filter: SmsFilter(ids: [for (var id = 1; id <= 1200; id++) id]),
        sort: SmsSort.newestFirst,
        limit: 3,
        offset: 2,
      );

      expect(page.map((m) => m.id), [1198, 1197, 1196]);
      expect(provider.requestsTo(ProviderUris.sms), hasLength(3));
    });

    test(
      'listContacts chunks an id-sorted request but not a name-sorted one',
      () async {
        const contacts = 'content://com.android.contacts/contacts';
        provider.seed(
          contacts,
          [
            for (var id = 1; id <= 600; id++)
              {'_id': id, 'display_name': 'c$id'},
          ],
          columns: const ['last_time_contacted'],
        );
        final ids = [for (var id = 1; id <= 600; id++) id];

        final byId = await LookupService().listContacts(
          filter: ContactFilter(ids: ids),
          sort: const ContactSort(field: ContactSortField.id),
        );
        expect(byId, hasLength(600));
        expect(provider.requestsTo(contacts), hasLength(2));

        await LookupService().listContacts(filter: ContactFilter(ids: ids));
        expect(provider.requestsTo(contacts), hasLength(3));
      },
    );

    test('listConversations chunks an oversized thread-id filter', () async {
      provider.seed(
        ProviderUris.conversations,
        [
          for (var id = 1; id <= 600; id++)
            {'_id': id, 'recipient_ids': '11', 'message_count': 1, 'date': id},
        ],
        columns: const ['archived'],
      );

      final conversations = await LookupService().listConversations(
        filter: ConversationFilter(
          threadIds: [for (var t = 1; t <= 600; t++) t],
        ),
        enrich: false,
      );

      expect(conversations.first.threadId, 600);
      expect(conversations, hasLength(600));
      expect(provider.requestsTo(ProviderUris.conversations), hasLength(2));
    });

    test('a repeated message type is bound once', () async {
      provider.seed(
        ProviderUris.sms,
        const [],
        columns: const ['_id', 'type', 'date'],
      );

      await LookupService().listSms(
        filter: const SmsFilter(
          types: [SmsMessageType.inbox, SmsMessageType.inbox],
        ),
      );

      expect(
        provider.requestsTo(ProviderUris.sms).single.filters.single.value,
        ['1'],
      );
    });
  });
}
