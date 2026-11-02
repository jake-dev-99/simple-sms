// `getStructuredName` reads the name row of the raw contact Android uses for
// the display name (`Contacts.NAME_RAW_CONTACT_ID`), not the first name row of
// a merged contact (UNFY-394).
//
// Row shapes follow a read-only check of a Samsung SM-S928U on Android 17:
// a merged contact had name rows on two Google raw contacts, Telegram and
// Meet/Duo raw contacts had none, and every name row had `is_primary` = 0.
// Names here are synthetic.

import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/android.dart';

import 'support/fake_content_provider_platform.dart';

const _dataUri = 'content://com.android.contacts/data';
const _name = 'vnd.android.cursor.item/name';

Map<String, Object?> _nameRow({
  required int id,
  required int rawContactId,
  required String given,
  int isPrimary = 0,
}) =>
    {
      '_id': id,
      'contact_id': 113,
      'raw_contact_id': rawContactId,
      'mimetype': _name,
      'is_primary': isPrimary,
      'data1': '$given Doe',
      'data2': given,
      'data3': 'Doe',
    };

void main() {
  late FakeContentProviderPlatform provider;

  setUp(() => provider = FakeContentProviderPlatform.install());

  test('reads the display-name raw contact, not the first name row', () async {
    provider.seed(_dataUri, [
      _nameRow(id: 4410, rawContactId: 424, given: 'Janie'),
      _nameRow(id: 781, rawContactId: 113, given: 'Jane'),
    ]);

    final name = await LookupService().getStructuredName(
      contactId: 113,
      nameRawContactId: 113,
    );

    expect(name?.givenName, 'Jane');
    expect(name?.id, 781);
  });

  test('filters by contact, raw contact and the name mimetype', () async {
    provider.seed(_dataUri, [
      _nameRow(id: 781, rawContactId: 113, given: 'Jane'),
    ]);

    await LookupService().getStructuredName(
      contactId: 113,
      nameRawContactId: 113,
    );

    final filters = provider.requestsTo(_dataUri).single.filters;
    expect(
      {for (final f in filters) f.field: f.value},
      {'contact_id': '113', 'raw_contact_id': '113', 'mimetype': _name},
    );
  });

  test('is null when that raw contact has no name row', () async {
    provider.seed(_dataUri, [
      _nameRow(id: 4410, rawContactId: 424, given: 'Janie'),
    ]);

    final name = await LookupService().getStructuredName(
      contactId: 113,
      nameRawContactId: 609,
    );

    expect(name, isNull);
  });

  test('picks the single is_primary row when there are several', () async {
    provider.seed(_dataUri, [
      _nameRow(id: 781, rawContactId: 113, given: 'Jane'),
      _nameRow(id: 782, rawContactId: 113, given: 'Janet', isPrimary: 1),
    ]);

    final name = await LookupService().getStructuredName(
      contactId: 113,
      nameRawContactId: 113,
    );

    expect(name?.givenName, 'Janet');
  });

  test('returns no name, rather than guess, when none is primary', () async {
    provider.seed(_dataUri, [
      _nameRow(id: 781, rawContactId: 113, given: 'Jane'),
      _nameRow(id: 782, rawContactId: 113, given: 'Janet'),
    ]);

    final name = await LookupService().getStructuredName(
      contactId: 113,
      nameRawContactId: 113,
    );

    expect(name, isNull);
  });

  test('returns no name when several rows are primary', () async {
    provider.seed(_dataUri, [
      _nameRow(id: 781, rawContactId: 113, given: 'Jane', isPrimary: 1),
      _nameRow(id: 782, rawContactId: 113, given: 'Janet', isPrimary: 1),
    ]);

    final name = await LookupService().getStructuredName(
      contactId: 113,
      nameRawContactId: 113,
    );

    expect(name, isNull);
  });
}
