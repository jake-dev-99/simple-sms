// Parsing of the Contacts columns that say where a contact's display name
// comes from (UNFY-394): `name_raw_contact_id` and `display_name_source`.
//
// Values follow Android's documented `ContactsContract.ContactsColumns` and
// `ContactsContract.DisplayNameSources`. These rows are synthetic; they lock
// the parser's handling of absent and documented values.

import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/src/android/models/enums/contact_enums.dart';
import 'package:simple_sms_native/src/android/models/people/contact.dart';

void main() {
  group('AndroidContact name_raw_contact_id', () {
    test('reads the raw contact that supplies the display name', () {
      final contact = AndroidContact.fromRaw({
        '_id': 7,
        'display_name': 'Jane Doe',
        'name_raw_contact_id': 42,
      });

      expect(contact.nameRawContactId, 42);
    });

    test('is null, not 0, when the column is absent', () {
      final contact = AndroidContact.fromRaw({
        '_id': 7,
        'display_name': 'Jane Doe',
      });

      expect(contact.nameRawContactId, isNull);
    });
  });

  group('DisplayNameSource', () {
    test('covers every documented DisplayNameSources value', () {
      expect(
        DisplayNameSource.values.map((s) => s.value),
        [0, 10, 20, 30, 35, 37, 40],
      );
    });

    test('reads STRUCTURED_PHONETIC_NAME (37)', () {
      final contact = AndroidContact.fromRaw({
        '_id': 7,
        'display_name_source': 37,
      });

      expect(contact.displayNameSource, DisplayNameSource.structuredPhonetic);
    });
  });
}
