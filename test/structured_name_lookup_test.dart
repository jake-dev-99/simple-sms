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
const _contactsUri = 'content://com.android.contacts/contacts';
const _name = 'vnd.android.cursor.item/name';

/// The fake provider each test seeds; reinstalled before every test.
late FakeContentProviderPlatform _provider;

/// Purpose: build a StructuredName data row of contact 113.
///
/// @param id the data row `_id`.
/// @param rawContactId the owning raw contact.
/// @param given the given name.
/// @param isPrimary the row's `is_primary` flag.
/// @returns the provider row.
/// @throws nothing.
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

/// Purpose: read contact 113's name through the public lookup facade.
///
/// @param nameRawContactId the raw contact id the caller read.
/// @returns the resolved structured name, or null.
/// @throws nothing; the fake provider does not fail.
Future<AndroidContactName?> _lookup(int nameRawContactId) =>
    LookupService().getStructuredName(
      contactId: 113,
      nameRawContactId: nameRawContactId,
    );

/// Purpose: register the `getStructuredName` tests on a fresh fake provider.
///
/// @returns nothing.
/// @throws nothing.
void main() {
  setUp(() => _provider = FakeContentProviderPlatform.install());

  _registerSelectionTests();
  _registerRetryTests();
  _registerAmbiguityTests();
}

/// Purpose: register tests for choosing the display-name raw contact's row.
///
/// @returns nothing.
/// @throws nothing.
void _registerSelectionTests() {
  test('reads the display-name raw contact, not the first name row', () async {
    _provider.seed(_dataUri, [
      _nameRow(id: 4410, rawContactId: 424, given: 'Janie'),
      _nameRow(id: 781, rawContactId: 113, given: 'Jane'),
    ]);

    final name = await _lookup(113);

    expect(name?.givenName, 'Jane');
    expect(name?.id, 781);
  });

  test('filters by contact, raw contact and the name mimetype', () async {
    _provider.seed(_dataUri, [
      _nameRow(id: 781, rawContactId: 113, given: 'Jane'),
    ]);

    await _lookup(113);

    final filters = _provider.requestsTo(_dataUri).single.filters;
    expect(
      {for (final f in filters) f.field: f.value},
      {'contact_id': '113', 'raw_contact_id': '113', 'mimetype': _name},
    );
  });

  test('is null when that raw contact has no name row', () async {
    _provider
      ..seed(_dataUri, [
        _nameRow(id: 4410, rawContactId: 424, given: 'Janie'),
      ])
      ..seed(_contactsUri, [
        {'_id': 113, 'name_raw_contact_id': 609},
      ]);

    expect(await _lookup(609), isNull);
  });
}

/// Purpose: register tests for the one retry after a contact re-merge.
///
/// @returns nothing.
/// @throws nothing.
void _registerRetryTests() {
  group('when Android re-merged the contact after the caller read its ids', () {
    test('retries once with the current name_raw_contact_id', () async {
      _provider
        ..seed(_dataUri, [
          _nameRow(id: 4410, rawContactId: 424, given: 'Janie'),
        ])
        ..seed(_contactsUri, [
          {'_id': 113, 'name_raw_contact_id': 424},
        ]);

      final name = await _lookup(113);

      expect(name?.givenName, 'Janie');
      expect(_provider.requestsTo(_dataUri), hasLength(2));
      expect(_provider.requestsTo(_contactsUri), hasLength(1));
    });

    test('does not retry when the pointer is unchanged', () async {
      _provider
        ..seed(_dataUri, [
          _nameRow(id: 4410, rawContactId: 424, given: 'Janie'),
        ])
        ..seed(_contactsUri, [
          {'_id': 113, 'name_raw_contact_id': 113},
        ]);

      expect(await _lookup(113), isNull);
      expect(_provider.requestsTo(_dataUri), hasLength(1));
    });

    test('returns no name when the contact no longer exists', () async {
      _provider
        ..seed(_dataUri, [
          _nameRow(id: 4410, rawContactId: 424, given: 'Janie'),
        ])
        ..seed(_contactsUri, const [], columns: const ['_id']);

      expect(await _lookup(113), isNull);
      expect(_provider.requestsTo(_dataUri), hasLength(1));
    });
  });
}

/// Purpose: register tests for several name rows on one raw contact.
///
/// @returns nothing.
/// @throws nothing.
void _registerAmbiguityTests() {
  test('picks the single is_primary row when there are several', () async {
    _provider.seed(_dataUri, [
      _nameRow(id: 781, rawContactId: 113, given: 'Jane'),
      _nameRow(id: 782, rawContactId: 113, given: 'Janet', isPrimary: 1),
    ]);

    expect((await _lookup(113))?.givenName, 'Janet');
  });

  test('returns no name, rather than guess, when none is primary', () async {
    _provider.seed(_dataUri, [
      _nameRow(id: 781, rawContactId: 113, given: 'Jane'),
      _nameRow(id: 782, rawContactId: 113, given: 'Janet'),
    ]);

    expect(await _lookup(113), isNull);
  });

  test('returns no name when several rows are primary', () async {
    _provider.seed(_dataUri, [
      _nameRow(id: 781, rawContactId: 113, given: 'Jane', isPrimary: 1),
      _nameRow(id: 782, rawContactId: 113, given: 'Janet', isPrimary: 1),
    ]);

    expect(await _lookup(113), isNull);
  });
}
