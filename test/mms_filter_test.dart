import 'package:flutter_test/flutter_test.dart';
import 'package:simple_query_platform_interface/simple_query_platform_interface.dart';
import 'package:simple_sms_native/android.dart';

import 'support/fake_content_provider_platform.dart';

// `MmsFilter.messageBoxes` and `excludeMessageBoxes` (UNFY-281): the provider
// evaluates `msg_box IN (...)` and one `msg_box != ?` per excluded box.
void main() {
  late FakeContentProviderPlatform provider;

  setUp(() {
    provider = FakeContentProviderPlatform.install();
    provider.seed(ProviderUris.mms, [
      _mms(1, box: 1),
      _mms(2, box: 2),
      _mms(3, box: 3),
      _mms(4, box: null),
    ]);
  });

  test('messageBoxes matches only the listed boxes', () async {
    final messages = await LookupService().listMms(
      filter: const MmsFilter(messageBoxes: [MessageBox.draft]),
    );

    final condition = provider
        .requestsTo(ProviderUris.mms)
        .single
        .filters
        .single;
    expect(condition.field, 'msg_box');
    expect(condition.operator, QueryFilterOperator.inList);
    expect(condition.value, ['3']);
    expect(messages.map((m) => m.id), [3]);
  });

  test('messageBoxes lists several boxes in one condition', () async {
    final messages = await LookupService().listMms(
      filter: const MmsFilter(
        messageBoxes: [MessageBox.inbox, MessageBox.sent],
      ),
    );

    expect(provider.requestsTo(ProviderUris.mms).single.filters.single.value, [
      '1',
      '2',
    ]);
    expect(messages.map((m) => m.id).toSet(), {1, 2});
  });

  test('excludeMessageBoxes drops the listed boxes and NULL boxes', () async {
    final messages = await LookupService().listMms(
      filter: const MmsFilter(excludeMessageBoxes: [MessageBox.draft]),
    );

    final [request] = provider.requestsTo(ProviderUris.mms);
    expect(request.filters.map((f) => (f.field, f.operator, f.value)), [
      ('msg_box', QueryFilterOperator.notEquals, '3'),
    ]);
    expect(messages.map((m) => m.id).toSet(), {1, 2});
  });

  test('empty box lists add no condition', () async {
    final messages = await LookupService().listMms(
      filter: const MmsFilter(messageBoxes: [], excludeMessageBoxes: []),
    );

    expect(provider.requestsTo(ProviderUris.mms).single.filters, isEmpty);
    expect(messages.map((m) => m.id).toSet(), {1, 2, 3, 4});
  });

  test('copyWith and toString carry both box lists', () {
    const filter = MmsFilter(
      messageBoxes: [MessageBox.draft],
      excludeMessageBoxes: [MessageBox.failed],
    );

    final copy = filter.copyWith(threadId: 7);
    expect(copy.messageBoxes, [MessageBox.draft]);
    expect(copy.excludeMessageBoxes, [MessageBox.failed]);
    expect(filter.copyWith(messageBoxes: [MessageBox.sent]).messageBoxes, [
      MessageBox.sent,
    ]);
    expect(
      filter
          .copyWith(excludeMessageBoxes: [MessageBox.outbox])
          .excludeMessageBoxes,
      [MessageBox.outbox],
    );
    expect(filter.toString(), contains('messageBoxes: [MessageBox.draft]'));
    expect(
      filter.toString(),
      contains('excludeMessageBoxes: [MessageBox.failed]'),
    );
  });
}

/// Purpose: builds a `content://mms` row in thread 7.
///
/// @param id the MMS `_id`.
/// @param box the `msg_box` column; null leaves the column NULL.
/// @returns the raw row.
/// @throws nothing.
Map<String, Object?> _mms(int id, {required int? box}) => {
  '_id': id,
  'thread_id': 7,
  'date': 1000 + id,
  'm_type': MmsMessageType.sendRequest.value,
  'msg_box': box,
};
