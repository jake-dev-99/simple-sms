import 'package:flutter_test/flutter_test.dart';
import 'package:simple_query_platform_interface/simple_query_platform_interface.dart';
import 'package:simple_sms_native/android.dart';

import 'support/fake_content_provider_platform.dart';

/// UNFY-321: `threadRowExists` reads one raw `threads` row, so a phantom thread
/// the conversation list drops still counts as present, and only a row that is
/// truly absent reports `false`.
void main() {
  late FakeContentProviderPlatform provider;

  setUp(() {
    provider = FakeContentProviderPlatform.install()
      ..seed(ProviderUris.conversations, [
        {'_id': 1, 'recipient_ids': '5', 'message_count': 2, 'date': 1756728001},
        {
          '_id': 2,
          'recipient_ids': '',
          'message_count': 0,
          'date': 1756728002,
        },
      ]);
  });

  test('true for a thread row', () async {
    expect(await LookupService().threadRowExists(1), isTrue);

    final request = provider.requestsTo(ProviderUris.conversations).last;
    expect(request.page, isNull);
    expect(request.filters, hasLength(1));
    expect(request.filters.single.field, '_id');
    expect(request.filters.single.operator, QueryFilterOperator.equals);
    expect(request.filters.single.value, '1');
  });

  test('true for a phantom thread the list drops', () async {
    final listed = await LookupService().listConversations(enrich: false);

    expect(listed.map((c) => c.threadId), [1]);
    expect(await LookupService().threadRowExists(2), isTrue);
  });

  test('false when no thread row exists', () async {
    expect(await LookupService().threadRowExists(3), isFalse);
  });

  test('an unavailable provider throws instead of reporting absence', () async {
    final error = SimpleQueryError(
      code: SimpleQueryErrorCode.unavailable,
      message: 'cursor was null',
    );
    provider.failQueriesTo(ProviderUris.conversations, error);

    await expectLater(LookupService().threadRowExists(1), throwsA(error));
  });

  test('any other provider failure throws too', () async {
    final error = SimpleQueryError(
      code: SimpleQueryErrorCode.permissionDenied,
      message: 'READ_SMS not granted',
    );
    provider.failQueriesTo(ProviderUris.conversations, error);

    await expectLater(LookupService().threadRowExists(1), throwsA(error));
  });
}
