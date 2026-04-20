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

      final mms = await Mms.fromRaw(raw);

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

      final mms = await Mms.fromRaw(raw);
      expect(mms.id, 20);
      expect(mms.recipients, isEmpty);
      expect(mms.parts, isEmpty);
      expect(mms.sender, isNull);
    });

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

      final mms = await Mms.fromRaw(raw);
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
