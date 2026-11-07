import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/src/android/models/messages/sms.dart';

/// `person` is an integer contact reference in the Android SMS provider. An
/// inbound row with it set once stopped every Unify launch (UNFY-403).
void main() {
  Map<String, dynamic> rawRow({Object? person}) => <String, dynamic>{
    '_id': 1772,
    'thread_id': 3,
    'date': 1793637861146,
    'type': 1,
    'person': person,
  };

  group('Sms.person', () {
    test('fromRaw reads the integer the provider returns', () {
      expect(Sms.fromRaw(rawRow(person: 116)).person, 116);
    });

    test('fromRaw reads a provider row with no contact as null', () {
      expect(Sms.fromRaw(rawRow()).person, isNull);
    });

    test('fromRaw reads a stringified id, as some OEM builds return', () {
      expect(Sms.fromRaw(rawRow(person: '116')).person, 116);
    });

    test('toRaw returns the provider integer', () {
      expect(Sms.fromRaw(rawRow(person: 116)).toRaw()['person'], 116);
    });

    test('fromJson reads what toJson wrote', () {
      final json = Sms.fromRaw(rawRow(person: 116)).toJson();
      expect(json['person'], 116);
      expect(Sms.fromJson(json).person, 116);
    });

    test('fromJson reads a value cached as a numeric string', () {
      final json = Sms.fromRaw(rawRow()).toJson()..['person'] = '116';
      expect(Sms.fromJson(json).person, 116);
    });
  });
}
