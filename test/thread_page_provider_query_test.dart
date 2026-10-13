import 'package:flutter_test/flutter_test.dart';
import 'package:simple_query_platform_interface/simple_query_platform_interface.dart';
import 'package:simple_sms_native/android.dart';

import 'support/fake_content_provider_platform.dart';

void main() {
  late FakeContentProviderPlatform provider;
  final at = DateTime.utc(2026, 9, 1, 12);

  setUp(() => provider = FakeContentProviderPlatform.install());

  test(
    'real query filters and sorting drain same-date, same-id SMS/MMS ties',
    () async {
      // Ascending input deliberately disagrees with the required native order.
      _seedThread(
        provider,
        sms: [
          for (var id = 1; id <= 9; id++) _sms(id, at),
          _sms(99, at.subtract(const Duration(seconds: 1))),
          {..._sms(101, at), 'thread_id': 8},
        ],
        mms: [
          for (var id = 1; id <= 9; id++) _mms(id, at),
          {..._mms(100, at), 'm_type': 0x82}, // transport-only
        ],
      );
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
        _messageQueries(provider).map((q) => q.page?.limit),
        everyElement(3),
      );
      expect(
        _hydratedMmsIds(provider),
        unorderedEquals([for (var id = 1; id <= 9; id++) id]),
      );
    },
  );

  test(
    'fractional SMS cursor includes older MMS in the same second once',
    () async {
      final smsAt = at.add(const Duration(milliseconds: 500));
      _seedThread(
        provider,
        sms: [_sms(2, smsAt), _sms(1, smsAt)],
        mms: [_mms(7, at)],
      );
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
      expect(_hydratedMmsIds(provider), [7]);
    },
  );

  test('timestamp-only callers keep an exclusive boundary', () async {
    _seedThread(
      provider,
      sms: [_sms(3, at), _sms(2, at.subtract(const Duration(milliseconds: 1)))],
      mms: [_mms(3, at), _mms(2, at.subtract(const Duration(seconds: 1)))],
    );
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
      expect(_messageQueries(provider), isEmpty);
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

/// Purpose: seeds every table `getNormalizedThreadPage` reads — the SMS and
/// MMS rows, plus an empty parts table and an empty address table per MMS row
/// for the page's hydration reads.
///
/// @param provider the installed fake.
/// @param sms raw `content://sms` rows.
/// @param mms raw `content://mms` rows.
/// @returns nothing.
/// @throws [ArgumentError] when an MMS row has no int `_id`.
void _seedThread(
  FakeContentProviderPlatform provider, {
  required List<Map<String, Object?>> sms,
  required List<Map<String, Object?>> mms,
}) {
  provider
    ..seed(ProviderUris.sms, sms)
    ..seed(ProviderUris.mms, mms)
    ..seed(ProviderUris.mmsParts, const [], columns: const ['_id', 'mid']);
  for (final row in mms) {
    final id = row['_id'];
    if (id is! int) throw ArgumentError.value(row, 'mms', 'row has no int _id');
    provider.seed(
      ProviderUris.mmsAddresses(id),
      const [],
      columns: const ['_id'],
    );
  }
}

/// Purpose: lists the paged SMS and MMS list queries the lookup issued.
///
/// @param provider the installed fake.
/// @returns the `content://sms` then `content://mms` requests.
/// @throws nothing.
List<QueryRequest> _messageQueries(FakeContentProviderPlatform provider) => [
  ...provider.requestsTo(ProviderUris.sms),
  ...provider.requestsTo(ProviderUris.mms),
];

/// Purpose: lists the MMS ids whose parts were hydrated, one per
/// `mid = ?` parts query.
///
/// @param provider the installed fake.
/// @returns the hydrated MMS ids, in request order.
/// @throws [FormatException] when a parts query binds a non-integer `mid`.
List<int> _hydratedMmsIds(FakeContentProviderPlatform provider) => [
  for (final request in provider.requestsTo(ProviderUris.mmsParts))
    int.parse(request.filters.single.value.toString()),
];
