import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/simple_sms_native.dart';

void main() {
  group('Sms participants (MessageParticipant contract)', () {
    Sms smsOfType(int type, {String? address, String? fromAddress}) =>
        Sms.fromRaw(<String, dynamic>{
          '_id': 1,
          'thread_id': 1,
          'date': 1700000000000,
          'type': type,
          if (address != null) 'address': address,
          if (fromAddress != null) 'from_address': fromAddress,
        });

    test('inbound: address is the sender, recipient is the local (null)', () {
      final sms = smsOfType(1, address: '+15551234567'); // inbox
      expect(sms.sender.role, ParticipantRole.sender);
      expect(sms.sender.address, '+15551234567');
      expect(sms.recipient.role, ParticipantRole.to);
      expect(sms.recipient.address, isNull);
    });

    test('outbound: address is the recipient, sender is the local (null)', () {
      final sms = smsOfType(2, address: '+15559876543'); // sent
      expect(sms.recipient.role, ParticipantRole.to);
      expect(sms.recipient.address, '+15559876543');
      expect(sms.sender.role, ParticipantRole.sender);
      expect(sms.sender.address, isNull);
    });

    test('inbound falls back to from_address when address is empty', () {
      final sms = smsOfType(1, address: '', fromAddress: '+15550001111');
      expect(sms.sender.address, '+15550001111');
    });

    test('SMS participants never carry a contactId or resolved name', () {
      final sms = smsOfType(1, address: '695628');
      expect(sms.sender.contactId, isNull);
      expect(sms.sender.displayName, isNull);
    });

    test('participants satisfy the MessageParticipant interface', () {
      final sms = smsOfType(1, address: '+15551234567');
      expect(sms.sender, isA<MessageParticipant>());
      expect(sms.recipient, isA<MessageParticipant>());
    });
  });

  group('MmsParticipant (MessageParticipant contract)', () {
    MmsParticipant participantOfType(int type) =>
        MmsParticipant.fromRaw(<String, dynamic>{
          '_id': 1,
          'msg_id': 3,
          'address': '+15551112222',
          'contact_id': 42,
          'type': type,
        });

    test('maps each WAP-PDU address type to the neutral role', () {
      expect(participantOfType(0x89).role, ParticipantRole.sender);
      expect(participantOfType(0x97).role, ParticipantRole.to);
      expect(participantOfType(0x82).role, ParticipantRole.cc);
      expect(participantOfType(0x81).role, ParticipantRole.bcc);
    });

    test('passes address + contactId through the interface', () {
      final p = participantOfType(0x89);
      expect(p, isA<MessageParticipant>());
      expect(p.address, '+15551112222');
      expect(p.contactId, 42);
      expect(p.displayName, isNull); // not resolved on the raw path
    });

    test('a null participant type surfaces as unknown, not a silent default',
        () {
      final p = MmsParticipant(id: 1); // hand-constructed, no type
      expect(p.participantType, isNull);
      expect(p.role, ParticipantRole.unknown);
    });
  });
}
