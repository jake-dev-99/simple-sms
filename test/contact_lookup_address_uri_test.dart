import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/android.dart';

import 'support/fake_content_provider_platform.dart';

// The contacts filter URI carries the address as one path segment. These
// literals pin the encoding independently of `ProviderUris`, so a regression
// to raw interpolation fails here even if the harness helper drifts with it.
void main() {
  late FakeContentProviderPlatform provider;

  setUp(() => provider = FakeContentProviderPlatform.install());

  test(
    'phone lookup percent-encodes the address as one path segment',
    () async {
      const uri =
          'content://com.android.contacts/data/phones/filter/'
          '%2B1%20(555)%20000-1111%232';
      provider.seed(uri, [
        {'_id': 7, 'data1': '+1 (555) 000-1111#2'},
      ]);

      final contactable = await LookupService().lookupContactableByAddress(
        '+1 (555) 000-1111#2',
      );

      expect(contactable?.id, 7);
    },
  );

  test('email lookup percent-encodes the address as one path segment', () async {
    const uri =
        'content://com.android.contacts/data/emails/filter/a%2Bb%40example.com';
    provider.seed(uri, const [], columns: const ['_id']);

    final contactable = await LookupService().lookupContactableByAddress(
      'a+b@example.com',
    );

    expect(contactable, isNull);
    expect(provider.requestsTo(uri), hasLength(1));
  });

  test('a blank address matches nothing without querying', () async {
    expect(await LookupService().lookupContactableByAddress('  '), isNull);
    expect(provider.queryRequests, isEmpty);
  });
}
