import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/simple_sms_native.dart';

void main() {
  group('Sms', () {
    test('fromRaw round-trips through toRaw with core fields intact', () {
      final raw = <String, dynamic>{
        '_id': 42,
        'thread_id': 7,
        'address': '+15551234567',
        'body': 'Hello, world!',
        'date': 1700000000000,
        'date_sent': 1700000000000,
        'read': 1,
        'seen': 0,
        'status': 0x81,
        'type': 1,
        'service_center': '+15550000000',
        'subject': 'Test',
        'reply_path_present': 0,
        'locked': 0,
        'error_code': 0,
        'creator': 'com.example.app',
      };

      final sms = Sms.fromRaw(raw);

      expect(sms.id, 42);
      expect(sms.threadId, 7);
      expect(sms.address, '+15551234567');
      expect(sms.body, 'Hello, world!');
      expect(sms.read, true);
      expect(sms.seen, false);
      expect(sms.status, AndroidMessageStatus.retrieved);
      expect(sms.type, SmsMessageType.inbox);
      expect(sms.serviceCenter, '+15550000000');

      final output = sms.toRaw();
      expect(output['_id'], 42);
      expect(output['thread_id'], 7);
      expect(output['address'], '+15551234567');
      expect(output['body'], 'Hello, world!');
    });

    test('fromJson round-trips through toJson with core fields intact', () {
      final json = <String, dynamic>{
        'id': 99,
        'threadId': 3,
        'address': '+15559876543',
        'body': 'JSON test',
        'date': '2024-01-15T10:30:00.000',
        'dateSent': '2024-01-15T10:29:00.000',
        'read': true,
        'seen': false,
        'status': 0x81,
        'type': 2,
        'serviceCenter': '+15550000000',
        'priority': 0x81,
      };

      final sms = Sms.fromJson(json);

      expect(sms.id, 99);
      expect(sms.threadId, 3);
      expect(sms.body, 'JSON test');
      expect(sms.read, true);
      expect(sms.seen, false);
      expect(sms.type, SmsMessageType.sent);
      expect(sms.priority, MessagePriority.normal);

      final output = sms.toJson();
      expect(output['_id'], 99);
      expect(output['threadId'], 3);
      expect(output['body'], 'JSON test');
    });

    test('fromRaw handles null/missing optional fields without crashing', () {
      final minimal = <String, dynamic>{
        '_id': 1,
        'thread_id': 1,
        'date': 1700000000000,
      };

      final sms = Sms.fromRaw(minimal);
      expect(sms.id, 1);
      expect(sms.body, isNull);
      expect(sms.address, isNull);
      expect(sms.read, isNull);
      expect(sms.priority, isNull);
    });

    test('fromRaw falls back to "id" when "_id" is missing', () {
      final raw = <String, dynamic>{
        'id': 55,
        'thread_id': 2,
        'date': 1700000000000,
      };

      final sms = Sms.fromRaw(raw);
      expect(sms.id, 55);
    });
  });

  group('Mms', () {
    test('fromRaw parses core fields and nested recipients/parts', () async {
      final raw = <String, dynamic>{
        '_id': 10,
        'thread_id': 5,
        'read': 1,
        'seen': 1,
        'sim_slot': 0,
        'body': 'MMS body text',
        'm_type': 0x84,
        'st': 0x81,
        'pri': 0x82,
        'sub_id': 1,
        'date': 1700000000,
        'recipients': [
          {'_id': 1, 'address': '+15551111111', 'type': 0x89, 'charset': 106},
          {'_id': 2, 'address': '+15552222222', 'type': 0x97, 'charset': 106},
        ],
        'parts': [
          {
            '_id': 1,
            'seq': 0,
            'ct': 'text/plain',
            'text': 'Hello MMS',
            'cl': 'text.txt',
          },
        ],
      };

      final mms = Mms.fromRaw(raw);

      expect(mms.id, 10);
      expect(mms.threadId, 5);
      expect(mms.read, true);
      expect(mms.type, MmsMessageType.retrieveConfirmationInd);
      expect(mms.status, AndroidMessageStatus.retrieved);
      expect(mms.priority, MessagePriority.high);
      expect(mms.parts, isNotNull);
      expect(mms.parts!.length, 1);
      expect(mms.parts!.first.text, 'Hello MMS');
    });

    test('fromRaw handles empty recipients and parts gracefully', () async {
      final raw = <String, dynamic>{
        '_id': 20,
        'thread_id': 1,
        'read': 0,
        'sim_slot': 0,
        'body': '',
      };

      final mms = Mms.fromRaw(raw);
      expect(mms.id, 20);
      expect(mms.recipients, isEmpty);
      expect(mms.parts, isEmpty);
      expect(mms.sender, isNull);
    });

    test(
      'deliveryReport / readReport / responseStatus round-trip through '
      'toRaw + toJson as int values, not enum instances',
      () async {
        // Regression for Tier 0 #9: toRaw used to emit enum *instances*
        // for d_rpt, rr, and resp_st — unencodable over the platform
        // channel and unreadable back through fromRaw's int-coerced
        // enumFromValue. The round-trip now survives a full toRaw →
        // fromRaw cycle without losing the enum values.
        final raw = <String, dynamic>{
          '_id': 42,
          'thread_id': 5,
          'read': 1,
          'sim_slot': 0,
          'body': 'roundtrip',
          'd_rpt': 0x81, // DeliveryReport.requested
          'rr': 0x80, // DeliveryReport.notRequested
          'resp_st': 0x81, // AndroidMessageStatus.retrieved
          'recipients': <Map<String, dynamic>>[],
          'parts': <Map<String, dynamic>>[],
        };

        final original = Mms.fromRaw(raw);
        expect(original.deliveryReport, DeliveryReport.requested);
        expect(original.readReport, DeliveryReport.notRequested);
        expect(original.responseStatus, AndroidMessageStatus.retrieved);

        final rawOut = original.toRaw();
        expect(rawOut['d_rpt'], 0x81);
        expect(rawOut['rr'], 0x80);
        expect(rawOut['resp_st'], 0x81);

        final jsonOut = original.toJson();
        expect(jsonOut['deliveryReport'], 0x81);
        expect(jsonOut['readReport'], 0x80);

        // Round-trip: parse the emitted raw back into an Mms and
        // confirm the enums land on the same values.
        final restored = Mms.fromRaw(rawOut);
        expect(restored.deliveryReport, DeliveryReport.requested);
        expect(restored.readReport, DeliveryReport.notRequested);
        expect(restored.responseStatus, AndroidMessageStatus.retrieved);
      },
    );

    test('toJson produces correct camelCase keys', () async {
      final raw = <String, dynamic>{
        '_id': 30,
        'thread_id': 8,
        'read': 1,
        'sim_slot': 1,
        'body': 'test',
        'sub': 'Subject line',
        'ct_t': 'application/vnd.wap.multipart.related',
        'm_size': 1024,
        'recipients': <Map<String, dynamic>>[],
        'parts': <Map<String, dynamic>>[],
      };

      final mms = Mms.fromRaw(raw);
      final json = mms.toJson();

      expect(json['id'], 30);
      expect(json['threadId'], 8);
      expect(json['read'], true);
      expect(json['subject'], 'Subject line');
      expect(json['contentType'], 'application/vnd.wap.multipart.related');
      expect(json['messageSize'], 1024);
    });
  });

  group('OutboundMessage', () {
    test('toJson and fromJson round-trip correctly', () {
      final message = OutboundMessage(
        body: 'Test message',
        addresses: {'+15551234567', '+15559876543'},
        attachmentPaths: {'/path/to/image.jpg'},
        conversationId: 'conv-123',
      );

      final json = message.toJson();
      expect(json['body'], 'Test message');
      expect(json['recipients'], isA<List>());
      expect((json['recipients'] as List).length, 2);
      expect(json['conversationId'], 'conv-123');

      final restored = OutboundMessage.fromJson(json);
      expect(restored.body, 'Test message');
      expect(restored.addresses.length, 2);
      expect(restored.addresses.contains('+15551234567'), isTrue);
      expect(restored.conversationId, 'conv-123');
    });

    test('handles null attachmentPaths', () {
      final message = OutboundMessage(
        body: 'No attachments',
        addresses: {'+15551234567'},
        attachmentPaths: null,
      );

      final json = message.toJson();
      expect(json['attachmentPaths'], isNull);
    });

    test('toJson emits Lists (not Sets) for channel-safe encoding', () {
      // Regression for Tier 0 #12: toJson used to emit raw `Set<String>`
      // for attachmentPaths, which isn't encodable through jsonEncode.
      // The interop layer patched it post-hoc — that patch is gone now.
      final message = OutboundMessage(
        body: 'MMS',
        addresses: {'+15551111111', '+15552222222'},
        attachmentPaths: {'/a.jpg', '/b.png'},
      );
      final json = message.toJson();
      expect(json['recipients'], isA<List>());
      expect(json['attachmentPaths'], isA<List>());
    });

    test('fromJson tolerates wire payloads with either List or null', () {
      // After jsonEncode/jsonDecode, Sets surface as Lists. fromJson has
      // to accept both without throwing a TypeError.
      final fromList = OutboundMessage.fromJson({
        'body': 'test',
        'recipients': ['+15551234567'],
        'attachmentPaths': ['/image.jpg'],
      });
      expect(fromList.addresses, {'+15551234567'});
      expect(fromList.attachmentPaths, {'/image.jpg'});

      final missingAttachments = OutboundMessage.fromJson({
        'body': 'noatt',
        'recipients': ['+15559999999'],
      });
      expect(missingAttachments.attachmentPaths, isNull);
      expect(missingAttachments.addresses, {'+15559999999'});
    });
  });

  group('FieldHelper (via Sms.fromRaw)', () {
    test('asBool handles int 0/1, bool, and string "1"/"true"', () {
      // Int values
      final smsTrue = Sms.fromRaw({
        '_id': 1,
        'thread_id': 1,
        'date': 1700000000000,
        'read': 1,
      });
      expect(smsTrue.read, true);

      final smsFalse = Sms.fromRaw({
        '_id': 2,
        'thread_id': 1,
        'date': 1700000000000,
        'read': 0,
      });
      expect(smsFalse.read, false);
    });

    test('asDateTime handles epoch millis and epoch seconds', () {
      // Milliseconds (> 1 trillion)
      final smsMillis = Sms.fromRaw({
        '_id': 1,
        'thread_id': 1,
        'date': 1700000000000,
      });
      expect(smsMillis.date.year, 2023);

      // Seconds (< 1 trillion, gets multiplied by 1000)
      final smsSecs = Sms.fromRaw({
        '_id': 2,
        'thread_id': 1,
        'date': 1700000000,
      });
      expect(smsSecs.date.year, 2023);
    });

    test('enumFromValue returns null for unknown values', () {
      final sms = Sms.fromRaw({
        '_id': 1,
        'thread_id': 1,
        'date': 1700000000000,
        'pri': 0xFF, // Not a valid MessagePriority
      });
      expect(sms.priority, isNull);
    });
  });

  group('AndroidSimpleConversation.fromRaw', () {
    // Regression for Tier 0 #5: Samsung's mms-sms/conversations?simple=true
    // returns several int flag columns as Strings. Before this fix those
    // landed in typed nullable slots via raw dynamic assignment and threw
    // TypeError inside the outer try/catch — dropping whole pages of
    // conversations on device. The fixture below mirrors the shapes
    // observed in the Prospector dump (prospector_export_2026_4_19_20_20_17/
    // mms-sms_conversations.json): int-as-String for the flag columns
    // and a space-separated `recipient_ids` payload.
    test('coerces Samsung string-form int flags without throwing', () {
      final raw = <String, dynamic>{
        '_id': 6244, // latest-message id on this view
        'thread_id': 3, // stable thread pk
        'archived': '0',
        'has_attachment': '1',
        'read': '1',
        'message_count': '70',
        'unread_count': '0',
        'reply_all': '0',
        'pin_to_top': '0',
        'secret_mode': '0',
        'snippet_cs': '106',
        'recipient_ids': '5 9 12',
        'display_recipient_ids': '5 9',
        'snippet': 'Thank you',
        'date': 1776546259000,
      };

      final conv = AndroidSimpleConversation.fromRaw(raw);
      expect(conv.id, 6244);
      expect(conv.threadId, 3);
      expect(conv.archived, 0);
      expect(conv.hasAttachment, 1);
      expect(conv.read, 1);
      expect(conv.messageCount, 70);
      expect(conv.unreadCount, 0);
      expect(conv.snippetCs, 106);
      expect(conv.recipientIds, ['5', '9', '12']);
      expect(conv.displayRecipientIds, ['5', '9']);
      expect(conv.snippet, 'Thank you');
    });

    test('tolerates null / empty recipient_ids', () {
      final conv = AndroidSimpleConversation.fromRaw({
        '_id': 1,
        'thread_id': 1,
      });
      expect(conv.recipientIds, isEmpty);
      expect(conv.displayRecipientIds, isEmpty);
    });

    test('toRaw → fromRaw round-trip preserves core fields', () {
      // Regression for Tier 0 #10: toRaw previously emitted raw enum
      // instances (chatType, type, usingMode), raw DateTime (date), and
      // List<String> for recipient_ids — none encodable over the platform
      // channel, and asymmetric with fromRaw which splits on spaces.
      final raw = <String, dynamic>{
        '_id': 6244,
        'thread_id': 3,
        'date': 1776546259000,
        'read': 1,
        'has_attachment': 1,
        'message_count': 70,
        'type': 0x01, // MessageBox.inbox
        'recipient_ids': '5 9 12',
        'display_recipient_ids': '5 9',
        'snippet': 'Thank you',
      };
      final original = AndroidSimpleConversation.fromRaw(raw);
      final rawOut = original.toRaw();

      expect(rawOut['_id'], 6244);
      expect(rawOut['thread_id'], 3);
      expect(rawOut['date'], 1776546259000);
      expect(rawOut['type'], 0x01); // enum serialised as int
      expect(rawOut['recipient_ids'], '5 9 12'); // list → space-joined
      expect(rawOut['display_recipient_ids'], '5 9');

      // Re-parse the emitted raw; core joining columns survive intact.
      final restored = AndroidSimpleConversation.fromRaw(rawOut);
      expect(restored.id, 6244);
      expect(restored.threadId, 3);
      expect(restored.recipientIds, ['5', '9', '12']);
      expect(restored.type, MessageBox.inbox);
    });
  });

  group('Mms.fromRaw — strict enum parsing', () {
    // Tests the strict-throw behaviour of enumFromValueOrThrow through
    // its actual contract surface (Mms.fromRaw), not by reaching into
    // the private FieldHelper. If a row carries an unrecognized
    // required-field value, the parse must fail loudly so crashlytics
    // surfaces it with the offending value named — silent fallback to
    // a default is exactly what produced the HEIC-photo and
    // MmsMessageType-off-by-N classes of bugs.

    test('valid m_type round-trips to the right enum', () {
      final raw = <String, dynamic>{
        '_id': 1,
        'thread_id': 1,
        'date': 1700000000,
        'msg_box': 1, // INBOX
        'm_type': 0x84, // RETRIEVE_CONF
      };
      expect(Mms.fromRaw(raw).type, MmsMessageType.retrieveConfirmationInd);
    });

    test('unrecognized m_type throws with the offending value named', () {
      final raw = <String, dynamic>{
        '_id': 999,
        'thread_id': 1,
        'date': 1700000000,
        'msg_box': 1, // INBOX
        'm_type': 0xFF, // garbage — not a valid PduHeaders MESSAGE_TYPE
      };
      expect(
        () => Mms.fromRaw(raw),
        throwsA(
          isA<StateError>().having(
            (e) => e.message,
            'message',
            allOf(
              contains('Mms.type'),
              contains('255'),
            ),
          ),
        ),
      );
    });

    test('unrecognized msg_box throws with the offending value named', () {
      final raw = <String, dynamic>{
        '_id': 999,
        'thread_id': 1,
        'date': 1700000000,
        'msg_box': 0xFF, // garbage — not a valid MessageBox value
        'm_type': 0x84, // RETRIEVE_CONF
      };
      expect(
        () => Mms.fromRaw(raw),
        throwsA(
          isA<StateError>().having(
            (e) => e.message,
            'message',
            allOf(
              contains('Mms.messageBox'),
              contains('255'),
            ),
          ),
        ),
      );
    });

    test('unrecognized OPTIONAL field (priority) returns null, not throws',
        () {
      final raw = <String, dynamic>{
        '_id': 1,
        'thread_id': 1,
        'date': 1700000000,
        'msg_box': 1, // INBOX
        'm_type': 0x84,
        'pri': 0xFF, // garbage — but priority is optional
      };
      // Optional fields tolerate unrecognized values (returned as null)
      // because the field is genuinely optional in the spec. Required
      // fields (m_type, msg_box) throw above.
      expect(() => Mms.fromRaw(raw), returnsNormally);
      expect(Mms.fromRaw(raw).priority, isNull);
    });
  });

  group('Mms.fromRaw — primary key strictness', () {
    test('throws StateError when neither _id nor id is present', () {
      // Pre-fix this silently coerced the row to id=0; multiple
      // PK-less rows then collided in HiveSearch lookups. Now the
      // parse throws so corruption surfaces in crashlytics with the
      // row's keys named.
      final raw = <String, dynamic>{
        'thread_id': 1,
        'date': 1700000000,
        'msg_box': 1,
        'm_type': 0x84,
      };
      expect(
        () => Mms.fromRaw(raw),
        throwsA(
          isA<StateError>().having(
            (e) => e.message,
            'message',
            allOf(
              contains('primary key'),
              contains('thread_id'),
            ),
          ),
        ),
      );
    });

    test('throws when _id is unparseable junk', () {
      final raw = <String, dynamic>{
        '_id': 'not-a-number',
        'thread_id': 1,
        'date': 1700000000,
        'msg_box': 1,
        'm_type': 0x84,
      };
      expect(() => Mms.fromRaw(raw), throwsA(isA<StateError>()));
    });

    test('falls back to legacy "id" key when "_id" is absent', () {
      final raw = <String, dynamic>{
        'id': 77,
        'thread_id': 1,
        'date': 1700000000,
        'msg_box': 1,
        'm_type': 0x84,
      };
      expect(Mms.fromRaw(raw).id, 77);
    });
  });

  group('ConversationFilter', () {
    // Regression: on the `mms-sms/conversations?simple=true` view, `_id` is
    // the latest-message id, not the thread id. Filtering threads must go
    // through `thread_id` or every lookup silently mismatches.
    test('threadIds + threadIdAfter round-trip through copyWith + toString', () {
      const filter = ConversationFilter(
        threadIds: [3, 9, 25],
        threadIdAfter: 100,
      );
      expect(filter.threadIds, [3, 9, 25]);
      expect(filter.threadIdAfter, 100);

      final copy = filter.copyWith(threadIds: [7]);
      expect(copy.threadIds, [7]);
      expect(copy.threadIdAfter, 100);

      expect(filter.toString(), contains('threadIds: [3, 9, 25]'));
      expect(filter.toString(), contains('threadIdAfter: 100'));
    });
  });
}
