// Parsing of the Contacts columns that say where a contact's display name
// comes from (UNFY-394): `name_raw_contact_id` and `display_name_source`.
//
// Values follow Android's documented `ContactsContract.ContactsColumns` and
// `ContactsContract.DisplayNameSources`. These rows are synthetic; they lock
// the parser's handling of absent and documented values.

import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/src/android/models/enums/contact_enums.dart';
import 'package:simple_sms_native/src/android/models/people/contact.dart';
import 'package:simple_sms_native/src/android/models/people/contactables.dart';

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

  group('Contactable name-source columns', () {
    test('are null, not 0 or undefined, when the columns are absent', () {
      final contactable = Contactable.fromRaw({
        '_id': 71,
        'contact_id': 7,
        'data1': '+15550007',
      });

      expect(contactable.nameRawContactId, isNull);
      expect(contactable.displayNameSource, isNull);
    });

    test('are null on a synthetic unknown-sender row', () {
      final contactable = Contactable(id: -1, value: '+15550008');

      expect(contactable.nameRawContactId, isNull);
      expect(contactable.displayNameSource, isNull);
    });

    test('read the documented values when present', () {
      final contactable = Contactable.fromRaw({
        '_id': 71,
        'contact_id': 7,
        'name_raw_contact_id': 42,
        'display_name_source': 40,
      });

      expect(contactable.nameRawContactId, 42);
      expect(contactable.displayNameSource, DisplayNameSource.structured);
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
