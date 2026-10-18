import 'package:flutter_test/flutter_test.dart';
import 'package:simple_query_platform_interface/simple_query_platform_interface.dart';
import 'package:simple_sms_native/android.dart';

import 'support/fake_content_provider_platform.dart';

// Conversation-list enrichment (UNFY-253): participant resolution, latest
// SMS/MMS selection, and the batched latest-MMS parts hydration UNFY-250 added.
void main() {
  late FakeContentProviderPlatform provider;

  setUp(() => provider = FakeContentProviderPlatform.install());

  group('participant resolution', () {
    test(
      'resolves recipient ids to contacts, keeps unmatched numbers, and drops '
      'ids with no canonical address',
      () async {
        _seedTables(provider, threads: [_thread(7, recipientIds: '11 12 13')]);
        _seedRecipient(provider, '11', _ada.data1, contact: _ada.row);
        _seedRecipient(provider, '12', '+15550002222');
        provider.seed(
          ProviderUris.canonicalAddress('13'),
          const [],
          columns: const ['_id'],
        );

        final [conversation] = await LookupService().listConversations();

        expect(conversation.participants?.map(_describe), [
          (_ada.id, _ada.data1, 'Ada Lovelace'),
          (-1, '+15550002222', ''),
        ]);
      },
    );

    test('replaces an opaque RCS token with the MMS addresses of the thread, '
        'skipping the self placeholder and the device numbers', () async {
      const token = 'chatbot@rcs.google.com';
      LookupService.setSelfNumbers({'+15559990000'});
      addTearDown(() => LookupService.setSelfNumbers(const {}));
      _seedTables(
        provider,
        threads: [_thread(7, recipientIds: '21')],
        mms: [_mms(30, threadId: 7, seconds: 100)],
      );
      _seedRecipient(provider, '21', token);
      provider
        ..seed(ProviderUris.mmsAddresses(30), [
          _address(
            1,
            mmsId: 30,
            address: 'insert-address-token',
            type: _sender,
          ),
          _address(2, mmsId: 30, address: _grace.data1, type: _recipient),
          _address(3, mmsId: 30, address: '+15559990000', type: _recipient),
        ])
        ..seed(ProviderUris.phoneLookup(_grace.data1), [_grace.row]);

      final [conversation] = await LookupService().listConversations();

      expect(conversation.participants?.map(_describe), [
        (_grace.id, _grace.data1, 'Grace Hopper'),
      ]);
    });
  });

  group('latest message selection', () {
    test('picks the newest SMS and MMS of each thread, never another '
        "thread's", () async {
      _seedTables(
        provider,
        threads: [_thread(7), _thread(8)],
        sms: [
          _sms(1, threadId: 7, millis: 1000),
          _sms(2, threadId: 7, millis: 3000),
          _sms(3, threadId: 7, millis: 2000),
          _sms(4, threadId: 8, millis: 9000),
        ],
        mms: [
          _mms(10, threadId: 7, seconds: 1),
          _mms(11, threadId: 7, seconds: 2),
          _mms(12, threadId: 8, seconds: 5),
        ],
      );
      _seedRecipient(provider, '11', _ada.data1, contact: _ada.row);

      final byThread = await _listByThread();

      expect(byThread[7]?.latestSms?.id, 2);
      expect(byThread[7]?.latestMms?.id, 11);
      expect(byThread[8]?.latestSms?.id, 4);
      expect(byThread[8]?.latestMms?.id, 12);
    });

    test('leaves latestMms null and skips the parts query for an SMS-only '
        'inbox', () async {
      _seedTables(
        provider,
        threads: [_thread(7)],
        sms: [_sms(1, threadId: 7, millis: 1000)],
      );
      _seedRecipient(provider, '11', _ada.data1, contact: _ada.row);

      final [conversation] = await LookupService().listConversations();

      expect(conversation.latestSms?.id, 1);
      expect(conversation.latestMms, isNull);
      expect(provider.requestsTo(ProviderUris.mmsParts), isEmpty);
    });
  });

  group('batched latest-MMS parts hydration (UNFY-250)', () {
    test('hydrates every latest MMS from one mid IN query, grouped by owning '
        'message', () async {
      _seedTables(
        provider,
        threads: [_thread(7), _thread(8)],
        mms: [
          _mms(10, threadId: 7, seconds: 1),
          _mms(11, threadId: 7, seconds: 2),
          _mms(12, threadId: 8, seconds: 5),
        ],
        parts: [
          _part(100, mid: 10, contentType: 'text/plain', text: 'older'),
          _part(110, mid: 11, contentType: 'text/plain', text: 'seven'),
          _part(120, mid: 12, contentType: 'image/jpeg'),
          _part(121, mid: 12, contentType: 'text/plain', text: 'eight'),
          _part(990, mid: 99, contentType: 'text/plain', text: 'unrelated'),
        ],
      );
      _seedRecipient(provider, '11', _ada.data1, contact: _ada.row);

      final byThread = await _listByThread();

      final [partsQuery] = provider.requestsTo(ProviderUris.mmsParts);
      final [midFilter] = partsQuery.filters;
      expect(midFilter.field, 'mid');
      expect(midFilter.operator, QueryFilterOperator.inList);
      expect(midFilter.value, unorderedEquals(['11', '12']));
      expect(byThread[7]?.latestMms?.parts?.map((p) => p.id), [110]);
      expect(byThread[8]?.latestMms?.parts?.map((p) => p.id), [120, 121]);
    });

    test('resolves an empty-body latest MMS from its text/plain part, with '
        'the SMIL layout excluded', () async {
      _seedTables(
        provider,
        threads: [_thread(7)],
        mms: [_mms(11, threadId: 7, seconds: 2)],
        parts: [
          _part(
            110,
            mid: 11,
            contentType: 'application/smil',
            text:
                '<smil><body><par><text src="cid:text_0"/></par></body>'
                '</smil>',
          ),
          _part(
            111,
            mid: 11,
            contentType: 'text/plain',
            text: 'See you at 6',
            contentId: 'text_0',
          ),
          _part(112, mid: 11, contentType: 'image/jpeg'),
        ],
      );
      _seedRecipient(provider, '11', _ada.data1, contact: _ada.row);

      final [conversation] = await LookupService().listConversations();
      final latestMms = conversation.latestMms;
      if (latestMms == null) fail('expected a latest MMS for thread 7');
      final preview = NormalizedMessage.fromMms(latestMms);

      // The provider's MMS row carries no body; only the parts supply it.
      expect(latestMms.body, isEmpty);
      expect(preview.body, 'See you at 6');
      expect(preview.attachments.map((a) => a.mimeType), ['image/jpeg']);
    });

    test(
      'skips a malformed part row without dropping the rest of the batch',
      () async {
        _seedTables(
          provider,
          threads: [_thread(7)],
          mms: [_mms(11, threadId: 7, seconds: 2)],
          parts: [
            _part(110, mid: 11, contentType: ''), // no MIME: malformed
            _part(111, mid: 11, contentType: 'text/plain', text: 'kept'),
          ],
        );
        _seedRecipient(provider, '11', _ada.data1, contact: _ada.row);

        final [conversation] = await LookupService().listConversations();

        expect(conversation.latestMms?.parts?.map((p) => p.id), [111]);
      },
    );

    // Provider failures the contract (simple_query ERROR_MAPPING.md) maps to
    // these codes leave an empty preview rather than failing the list.
    for (final code in [
      SimpleQueryErrorCode.transientFailure,
      SimpleQueryErrorCode.unavailable,
    ]) {
      test('a failed parts batch (${code.name}) still returns the list, with '
          'the latest MMS unhydrated', () async {
        _seedTables(
          provider,
          threads: [_thread(7)],
          mms: [_mms(11, threadId: 7, seconds: 2)],
        );
        _seedRecipient(provider, '11', _ada.data1, contact: _ada.row);
        provider.failQueriesTo(
          ProviderUris.mmsParts,
          SimpleQueryError(code: code, message: 'provider failure'),
        );

        final [conversation] = await LookupService().listConversations();

        expect(provider.requestsTo(ProviderUris.mmsParts), hasLength(1));
        expect(conversation.latestMms?.id, 11);
        expect(conversation.latestMms?.parts, isEmpty);
      });
    }

    // A malformed query is a defect, and missing SMS access is surfaced to
    // the caller as AOSP Messaging does, so neither becomes an empty preview.
    for (final failure in const [
      SimpleQueryError(
        code: SimpleQueryErrorCode.invalidQuery,
        message: 'no such column: mid',
      ),
      SimpleQueryError(
        code: SimpleQueryErrorCode.permissionDenied,
        message: 'READ_SMS not granted',
      ),
    ]) {
      test('a failed parts batch (${failure.code.name}) propagates', () async {
        _seedTables(
          provider,
          threads: [_thread(7)],
          mms: [_mms(11, threadId: 7, seconds: 2)],
        );
        _seedRecipient(provider, '11', _ada.data1, contact: _ada.row);
        provider.failQueriesTo(ProviderUris.mmsParts, failure);

        await expectLater(
          LookupService().listConversations(),
          throwsA(same(failure)),
        );
      });
    }

    test('a latest MMS shared by threads across a chunk boundary is '
        'hydrated once', () async {
      // 500 distinct latest MMS, then thread 501 repeating MMS 1, so the
      // duplicate would land in a second chunk without deduplication.
      _seedTables(
        provider,
        threads: [for (var t = 1; t <= 501; t++) _thread(t)],
        mms: [
          for (var t = 1; t <= 500; t++) _mms(t, threadId: t, seconds: t),
          _mms(1, threadId: 501, seconds: 1000),
        ],
        parts: [
          for (var t = 1; t <= 500; t++)
            _part(10000 + t, mid: t, contentType: 'text/plain', text: '$t'),
        ],
      );
      _seedRecipient(provider, '11', _ada.data1, contact: _ada.row);

      final byThread = await _listByThread();

      expect(provider.requestsTo(ProviderUris.mmsParts), hasLength(1));
      expect(byThread[501]?.latestMms?.parts?.map((p) => p.id), [10001]);
    });

    test(
      'runs parts chunks one after another, stopping at a failure',
      () async {
        const threadCount = 1001;
        _seedTables(
          provider,
          threads: [for (var t = 1; t <= threadCount; t++) _thread(t)],
          mms: [
            for (var t = 1; t <= threadCount; t++)
              _mms(t, threadId: t, seconds: t),
          ],
        );
        _seedRecipient(provider, '11', _ada.data1, contact: _ada.row);
        provider.failQueriesTo(
          ProviderUris.mmsParts,
          const SimpleQueryError(
            code: SimpleQueryErrorCode.transientFailure,
            message: 'provider busy',
          ),
        );

        final conversations = await LookupService().listConversations();

        expect(provider.requestsTo(ProviderUris.mmsParts), hasLength(1));
        expect(conversations, hasLength(threadCount));
      },
    );

    test(
      'hydrates an inbox past the SQLite bind limit in bounded chunks',
      () async {
        const threadCount = 1001;
        _seedTables(
          provider,
          threads: [for (var t = 1; t <= threadCount; t++) _thread(t)],
          mms: [
            for (var t = 1; t <= threadCount; t++)
              _mms(t, threadId: t, seconds: t),
          ],
          parts: [
            for (var t = 1; t <= threadCount; t++)
              _part(10000 + t, mid: t, contentType: 'text/plain', text: '$t'),
          ],
        );
        _seedRecipient(provider, '11', _ada.data1, contact: _ada.row);

        final conversations = await LookupService().listConversations();

        final chunkSizes = [
          for (final request in provider.requestsTo(ProviderUris.mmsParts))
            switch (request.filters.single.value) {
              final List<Object?> mids => mids.length,
              final other => throw StateError('expected mid IN list: $other'),
            },
        ];
        expect(chunkSizes, [500, 500, 1]);
        expect(
          conversations.map((c) => c.latestMms?.parts?.single.messageId),
          unorderedEquals([for (var t = 1; t <= threadCount; t++) t]),
        );
      },
    );

    test('a non-provider error in the parts batch propagates', () async {
      _seedTables(
        provider,
        threads: [_thread(7)],
        mms: [_mms(11, threadId: 7, seconds: 2)],
      );
      _seedRecipient(provider, '11', _ada.data1, contact: _ada.row);
      final defect = StateError('defect, not a provider failure');
      provider.failQueriesTo(ProviderUris.mmsParts, defect);

      await expectLater(
        LookupService().listConversations(),
        throwsA(same(defect)),
      );
    });

    test(
      'a latest MMS with no part rows gets an empty list, not null',
      () async {
        _seedTables(
          provider,
          threads: [_thread(7)],
          mms: [_mms(11, threadId: 7, seconds: 2)],
          parts: [_part(990, mid: 99, contentType: 'text/plain', text: 'x')],
        );
        _seedRecipient(provider, '11', _ada.data1, contact: _ada.row);

        final [conversation] = await LookupService().listConversations();

        expect(provider.requestsTo(ProviderUris.mmsParts), hasLength(1));
        expect(conversation.latestMms?.parts, isNotNull);
        expect(conversation.latestMms?.parts, isEmpty);
      },
    );
  });

  group('list shape', () {
    test('drops phantom threads but keeps recipient-less threads that hold '
        'messages', () async {
      _seedTables(
        provider,
        threads: [
          _thread(7, recipientIds: '', messageCount: 0),
          _thread(8, recipientIds: '', messageCount: 3),
        ],
      );

      final conversations = await LookupService().listConversations();

      expect(conversations.map((c) => c.threadId), [8]);
      expect(conversations.single.participants, isEmpty);
    });

    test(
      'enrich: false returns bare rows from the conversations query alone',
      () async {
        provider.seed(ProviderUris.conversations, [_thread(7)]);

        final [conversation] = await LookupService().listConversations(
          enrich: false,
        );

        expect(conversation.threadId, 7);
        expect(conversation.participants, isNull);
        expect(provider.queryRequests, hasLength(1));
      },
    );

    test(
      'rethrows a failed conversations query instead of returning empty',
      () async {
        final failure = Exception('no such column: thread_id');
        provider.failQueriesTo(ProviderUris.conversations, failure);

        await expectLater(
          LookupService().listConversations(),
          throwsA(same(failure)),
        );
      },
    );
  });

  group('native drafts (UNFY-349)', () {
    setUp(() {
      _seedRecipient(provider, '11', _ada.data1, contact: _ada.row);
    });

    test("draft is the thread's newest SMS draft by date then id", () async {
      _seedTables(
        provider,
        threads: [_thread(7), _thread(8), _thread(9)],
        sms: [
          _draft(1, threadId: 7, millis: 1000),
          _draft(2, threadId: 7, millis: 3000),
          _draft(3, threadId: 7, millis: 3000),
          _draft(4, threadId: 7, millis: 2000),
          _sms(5, threadId: 7, millis: 9000),
          _sms(6, threadId: 8, millis: 9000),
          _draft(7, threadId: 9, millis: 500),
        ],
      );

      final byThread = await _listByThread();

      expect(byThread[7]?.draft?.id, 3, reason: 'date tie goes to the higher id');
      expect(byThread[9]?.draft?.id, 7);
    });

    test('a thread with no draft has none', () async {
      _seedTables(
        provider,
        threads: [_thread(7), _thread(8)],
        sms: [
          _sms(1, threadId: 7, millis: 1000),
          _draft(2, threadId: 8, millis: 2000),
        ],
      );

      final byThread = await _listByThread();

      expect(byThread[7]?.draft, isNull);
      expect(byThread[8]?.draft?.id, 2);
    });

    test('drafts are read once for the whole list', () async {
      _seedTables(
        provider,
        threads: [_thread(7), _thread(8), _thread(9)],
        sms: [_draft(1, threadId: 7, millis: 1000)],
      );

      await LookupService().listConversations();

      final [draftRead] = _draftReads(provider);
      expect(
        draftRead.filters.map((f) => f.field),
        ['type'],
        reason: 'provider-wide: no thread condition',
      );
      expect(draftRead.filters.single.value, ['3']);
    });

    test('one requested thread scopes the draft read to it', () async {
      _seedTables(
        provider,
        threads: [_thread(7)],
        sms: [_draft(1, threadId: 7, millis: 1000)],
      );

      final conversation = await LookupService().getConversationByThread(7);

      expect(conversation?.draft?.id, 1);
      final [draftRead] = _draftReads(provider);
      final byField = {for (final f in draftRead.filters) f.field: f.value};
      expect(byField, {'thread_id': '7', 'type': ['3']});
    });

    test('a failed draft read fails the list', () async {
      _seedTables(
        provider,
        threads: [_thread(7)],
        sms: [_draft(1, threadId: 7, millis: 1000)],
      );
      // `unavailable` leaves a thread bare when an enrichment read hits it,
      // but the draft read is all-or-nothing: it fails the call.
      final failure = SimpleQueryError(
        code: SimpleQueryErrorCode.unavailable,
        message: 'no cursor',
        details: const {'contentUri': ProviderUris.sms},
      );
      provider.failQueriesTo(ProviderUris.sms, failure);

      await expectLater(
        LookupService().listConversations(),
        throwsA(same(failure)),
      );
    });

    test('enrich false reads no drafts', () async {
      _seedTables(
        provider,
        threads: [_thread(7)],
        sms: [_draft(1, threadId: 7, millis: 1000)],
      );

      final [conversation] = await LookupService().listConversations(
        enrich: false,
      );

      expect(conversation.draft, isNull);
      expect(provider.requestsTo(ProviderUris.sms), isEmpty);
    });
  });
}

/// A contacts phone-filter row and the values a test asserts on.
typedef _Contact = ({int id, String data1, Map<String, Object?> row});

final _Contact _ada = _contact(501, '+15550001111', 'Ada Lovelace');
final _Contact _grace = _contact(502, '+15550003333', 'Grace Hopper');

const int _sender = 0x89;
const int _recipient = 0x97;

/// Purpose: builds a contacts phone-filter row.
///
/// @param id the data row `_id`.
/// @param number the matched phone number (`data1`).
/// @param displayName the contact's display name.
/// @returns the row with its asserted values.
/// @throws nothing.
_Contact _contact(int id, String number, String displayName) => (
  id: id,
  data1: number,
  row: {'_id': id, 'data1': number, 'display_name': displayName},
);

/// Purpose: builds a `mms-sms/conversations?simple=true` row, where `_id` is
/// the thread id and dates are in seconds.
///
/// @param threadId the thread id.
/// @param recipientIds space-separated canonical-address ids.
/// @param messageCount the thread's message count.
/// @returns the raw row.
/// @throws nothing.
Map<String, Object?> _thread(
  int threadId, {
  String recipientIds = '11',
  int messageCount = 1,
}) => {
  '_id': threadId,
  'recipient_ids': recipientIds,
  'message_count': messageCount,
  'date': 1756728000 + threadId,
};

/// Purpose: builds a `content://sms` row (date in milliseconds).
///
/// @param id the SMS `_id`.
/// @param threadId the owning thread.
/// @param millis the `date` column.
/// @returns the raw row.
/// @throws nothing.
Map<String, Object?> _sms(
  int id, {
  required int threadId,
  required int millis,
}) => {'_id': id, 'thread_id': threadId, 'date': millis, 'type': 1};

/// Purpose: builds a native SMS draft row (`type` 3, date in milliseconds).
///
/// @param id the SMS `_id`.
/// @param threadId the owning thread.
/// @param millis the `date` column.
/// @returns the raw row.
/// @throws nothing.
Map<String, Object?> _draft(
  int id, {
  required int threadId,
  required int millis,
}) => {..._sms(id, threadId: threadId, millis: millis), 'type': 3};

/// Purpose: lists the provider-wide draft reads: the `content://sms` requests
/// that filter on `type` with a list.
///
/// @param provider the installed fake.
/// @returns the matching requests, in call order.
/// @throws nothing.
List<QueryRequest> _draftReads(FakeContentProviderPlatform provider) =>
    provider
        .requestsTo(ProviderUris.sms)
        .where(
          (request) => request.filters.any(
            (f) => f.field == 'type' && f.operator == QueryFilterOperator.inList,
          ),
        )
        .toList(growable: false);

/// Purpose: builds a `content://mms` row (date in seconds) with no body, as
/// the provider returns it.
///
/// @param id the MMS `_id`.
/// @param threadId the owning thread.
/// @param seconds the `date` column.
/// @returns the raw row.
/// @throws nothing.
Map<String, Object?> _mms(
  int id, {
  required int threadId,
  required int seconds,
}) => {
  '_id': id,
  'thread_id': threadId,
  'date': seconds,
  'm_type': 0x84,
  'read': 1,
};

/// Purpose: builds a `content://mms/part` row.
///
/// @param id the part `_id`.
/// @param mid the owning MMS `_id`.
/// @param contentType the `ct` column.
/// @param text the inline `text` column, for text and SMIL parts.
/// @param contentId the `cid` column a SMIL `src` refers to.
/// @returns the raw row.
/// @throws nothing.
Map<String, Object?> _part(
  int id, {
  required int mid,
  required String contentType,
  String? text,
  String? contentId,
}) => {
  '_id': id,
  'mid': mid,
  'ct': contentType,
  'text': text,
  'cid': contentId,
};

/// Purpose: builds a `content://mms/{id}/addr` row.
///
/// @param id the address row `_id`.
/// @param mmsId the owning MMS `_id` (`msg_id`).
/// @param address the address column.
/// @param type the PDU address type (sender `0x89`, recipient `0x97`).
/// @returns the raw row.
/// @throws nothing.
Map<String, Object?> _address(
  int id, {
  required int mmsId,
  required String address,
  required int type,
}) => {'_id': id, 'msg_id': mmsId, 'address': address, 'type': type};

/// Purpose: seeds every table enrichment reads. The message tables declare
/// the columns enrichment filters and sorts on, so they may start empty.
///
/// @param provider the installed fake.
/// @param threads conversation rows.
/// @param sms `content://sms` rows.
/// @param mms `content://mms` rows.
/// @param parts `content://mms/part` rows.
/// @returns nothing.
/// @throws nothing.
void _seedTables(
  FakeContentProviderPlatform provider, {
  required List<Map<String, Object?>> threads,
  List<Map<String, Object?>> sms = const [],
  List<Map<String, Object?>> mms = const [],
  List<Map<String, Object?>> parts = const [],
}) {
  const messageColumns = ['_id', 'thread_id', 'date'];
  provider
    ..seed(ProviderUris.conversations, threads)
    ..seed(ProviderUris.sms, sms, columns: [...messageColumns, 'type'])
    ..seed(ProviderUris.mms, mms, columns: messageColumns)
    ..seed(ProviderUris.mmsParts, parts, columns: const ['_id', 'mid']);
}

/// Purpose: seeds a recipient id's canonical address and its contacts lookup
/// (phone or email filter, as production chooses by the `@`).
///
/// @param provider the installed fake.
/// @param recipientId the id in a thread's `recipient_ids`.
/// @param address the canonical address it resolves to.
/// @param contact the matching contacts row, or null for no match.
/// @returns nothing.
/// @throws nothing.
void _seedRecipient(
  FakeContentProviderPlatform provider,
  String recipientId,
  String address, {
  Map<String, Object?>? contact,
}) {
  final lookupUri =
      address.contains('@')
          ? ProviderUris.emailLookup(address)
          : ProviderUris.phoneLookup(address);
  provider
    ..seed(ProviderUris.canonicalAddress(recipientId), [
      {'_id': int.parse(recipientId), 'address': address},
    ])
    ..seed(lookupUri, [if (contact != null) contact], columns: const ['_id']);
}

/// Purpose: lists enriched conversations keyed by thread id.
///
/// @returns each conversation under its thread id.
/// @throws whatever `listConversations` throws.
Future<Map<int, AndroidSimpleConversation>> _listByThread() async => {
  for (final c in await LookupService().listConversations()) c.threadId: c,
};

/// Purpose: projects a participant onto the values a test asserts on.
///
/// @param participant a resolved participant.
/// @returns its contact data id, address, and display name.
/// @throws nothing.
(int, String, String) _describe(Contactable participant) => (
  participant.id,
  participant.value,
  participant.displayName,
);
