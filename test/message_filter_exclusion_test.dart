import 'package:flutter_test/flutter_test.dart';
import 'package:simple_query_platform_interface/simple_query_platform_interface.dart';
import 'package:simple_sms_native/android.dart';

import 'support/fake_content_provider_platform.dart';

// `SmsFilter.excludeTypes` (UNFY-349): one `type != ?` condition per listed
// type, evaluated by the provider rather than after the read.
void main() {
  late FakeContentProviderPlatform provider;

  setUp(() => provider = FakeContentProviderPlatform.install());

  test('excludeTypes excludes each listed type', () async {
    provider.seed(ProviderUris.sms, [
      _sms(1, type: SmsMessageType.inbox, millis: 5000),
      _sms(2, type: SmsMessageType.sent, millis: 4000),
      _sms(3, type: SmsMessageType.draft, millis: 3000),
      _sms(4, type: SmsMessageType.outbox, millis: 2000),
      _sms(5, type: SmsMessageType.failed, millis: 1000),
    ]);

    final messages = await LookupService().listSms(
      filter: const SmsFilter(
        excludeTypes: [SmsMessageType.draft, SmsMessageType.outbox],
      ),
    );

    final [request] = provider.requestsTo(ProviderUris.sms);
    expect(
      request.filters.map((f) => (f.field, f.operator, f.value)),
      [
        ('type', QueryFilterOperator.notEquals, '3'),
        ('type', QueryFilterOperator.notEquals, '4'),
      ],
    );
    expect(messages.map((m) => m.id), [1, 2, 5]);
  });

  test('excludeTypes drops a row whose type is NULL, as SQL != does', () async {
    provider.seed(ProviderUris.sms, [
      _sms(1, type: SmsMessageType.inbox, millis: 2000),
      {'_id': 2, 'thread_id': 7, 'date': 1000, 'type': null},
    ]);

    final messages = await LookupService().listSms(
      filter: const SmsFilter(excludeTypes: [SmsMessageType.draft]),
    );

    expect(messages.map((m) => m.id), [1]);
  });

  test('an empty excludeTypes adds no condition', () async {
    provider.seed(ProviderUris.sms, [
      _sms(1, type: SmsMessageType.draft, millis: 1000),
    ]);

    final messages = await LookupService().listSms(
      filter: const SmsFilter(excludeTypes: []),
    );

    expect(provider.requestsTo(ProviderUris.sms).single.filters, isEmpty);
    expect(messages.map((m) => m.id), [1]);
  });

  test('copyWith and toString carry excludeTypes', () {
    const filter = SmsFilter(excludeTypes: [SmsMessageType.draft]);

    expect(filter.copyWith(threadId: 7).excludeTypes, [SmsMessageType.draft]);
    expect(
      filter.copyWith(excludeTypes: [SmsMessageType.failed]).excludeTypes,
      [SmsMessageType.failed],
    );
    expect(filter.toString(), contains('excludeTypes: [SmsMessageType.draft]'));
  });
}

/// Purpose: builds a `content://sms` row in thread 7.
///
/// @param id the SMS `_id`.
/// @param type the `type` column.
/// @param millis the `date` column.
/// @returns the raw row.
/// @throws nothing.
Map<String, Object?> _sms(
  int id, {
  required SmsMessageType type,
  required int millis,
}) => {'_id': id, 'thread_id': 7, 'date': millis, 'type': type.value};
