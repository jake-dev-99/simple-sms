import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/android.dart';

/// UNFY-346: a host must never mistake partial, malformed or unjournaled deletion for success.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('io.simplezen.simple_sms/destructive_actions');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  Map<String, Object?> receipt({bool complete = true}) => {
    'clientCorrelationId': 'job',
    'threadId': '7',
    'messageUri': 'content://sms/100',
    'confirmedMessageUris': ['content://sms/100'],
    'confirmedGenerations': {'content://sms/100': '1000'},
    'complete': complete,
  };
  test(
    'MMS confirmation requires transaction identity rather than a reused row id',
    () {
      final wire = {
        ...receipt(),
        'messageUri': 'content://mms/100',
        'confirmedMessageUris': ['content://mms/100'],
        'confirmedGenerations': {'content://mms/100': '1000'},
      };
      expect(() => SendDeletionReceipt.fromWire(wire), throwsFormatException);
      wire['confirmedGenerations'] = {
        'content://mms/100': 'mms:transaction-100',
      };
      expect(SendDeletionReceipt.fromWire(wire).confirmedGenerations, {
        'content://mms/100': 'mms:transaction-100',
      });
    },
  );

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'a partial native deletion preserves identities and failure instead of claiming success',
    () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'deleteSend');
        expect(call.arguments, {
          'messageUri': 'content://sms/100',
          'clientCorrelationId': 'job',
          'threadId': '7',
        });
        return {
          ...receipt(complete: false),
          'failureCode': 'DELETE_INCOMPLETE',
          'failureMessage': 'row retained',
        };
      });
      final result = await AndroidDestructiveAction.deleteSend(
        messageUri: 'content://sms/100',
        clientCorrelationId: 'job',
        threadId: '7',
      );
      expect(result.complete, isFalse);
      expect(result.confirmedMessageUris, ['content://sms/100']);
      expect(result.confirmedGenerations, {'content://sms/100': '1000'});
      expect(result.failureReason, DeleteFailureReason.deleteIncomplete);
      expect(result.failureMessage, 'row retained');
    },
  );

  test(
    'pending confirmations preserve the thread and paging cursor without guessing an empty journal',
    () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'pendingSendDeletions');
        expect(call.arguments, {
          'threadId': '7',
          'afterClientCorrelationId': 'earlier',
          'limit': 3,
        });
        return [receipt()];
      });
      final result = await AndroidDestructiveAction.pendingSendDeletions(
        threadId: '7',
        afterClientCorrelationId: 'earlier',
        limit: 3,
      );
      expect(result.single.clientCorrelationId, 'job');
      expect(result.single.threadId, '7');
      expect(result.single.messageUri, 'content://sms/100');
      expect(result.single.complete, isTrue);
    },
  );

  test(
    'only an explicit acknowledgement clears the supplied confirmed identities',
    () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'acknowledgeSendDeletion');
        expect(call.arguments, {
          'clientCorrelationId': 'job',
          'messageUris': ['content://sms/100'],
        });
        return null;
      });
      await AndroidDestructiveAction.acknowledgeSendDeletion(
        clientCorrelationId: 'job',
        messageUris: ['content://sms/100'],
      );
    },
  );

  test('loss of the SMS role remains a typed failure', () async {
    messenger.setMockMethodCallHandler(
      channel,
      (_) async => throw PlatformException(code: 'NOT_DEFAULT_SMS_APP'),
    );
    await expectLater(
      AndroidDestructiveAction.deleteSend(
        messageUri: 'content://sms/100',
        clientCorrelationId: 'job',
        threadId: '7',
      ),
      throwsA(
        isA<DeleteFailedException>().having(
          (e) => e.reason,
          'reason',
          DeleteFailureReason.notDefaultSmsApp,
        ),
      ),
    );
  });

  test('a missing native journal reply fails closed', () async {
    messenger.setMockMethodCallHandler(channel, (_) async => null);
    await expectLater(
      AndroidDestructiveAction.pendingSendDeletions(),
      throwsFormatException,
    );
  });

  test('paging bounds fail before invoking native', () async {
    var calls = 0;
    messenger.setMockMethodCallHandler(channel, (_) async {
      calls++;
      return [];
    });
    await expectLater(
      AndroidDestructiveAction.pendingSendDeletions(limit: 0),
      throwsRangeError,
    );
    await expectLater(
      AndroidDestructiveAction.pendingSendDeletions(limit: 65),
      throwsRangeError,
    );
    expect(calls, 0);
  });

  final malformed = <String, Map<String, Object?> Function()>{
    'missing job': () => receipt()..remove('clientCorrelationId'),
    'blank job': () => receipt()..['clientCorrelationId'] = ' ',
    'non-boolean completion': () => receipt()..['complete'] = 1,
    'unqualified anchor': () => receipt()..['messageUri'] = 'content://sms',
    'invalid thread': () => receipt()..['threadId'] = '0',
    'missing confirmation list': () =>
        receipt()..remove('confirmedMessageUris'),
    'missing generation proof': () => receipt()..remove('confirmedGenerations'),
    'mismatched generation keys': () =>
        receipt()..['confirmedGenerations'] = {'content://sms/101': '1000'},
    'duplicate identity': () => receipt()
      ..['confirmedMessageUris'] = ['content://sms/100', 'content://sms/100'],
    'unknown failure': () => {
      ...receipt(complete: false),
      'failureCode': 'FUTURE_FAILURE',
    },
    'failed completion': () => {...receipt(), 'failureCode': 'DELETE_FAILED'},
  };
  for (final entry in malformed.entries) {
    test('malformed receipt rejects ${entry.key}', () {
      expect(
        () => SendDeletionReceipt.fromWire(entry.value()),
        throwsFormatException,
      );
    });
  }

  test('receipt identities cannot be mutated after verification', () {
    final result = SendDeletionReceipt.fromWire(receipt());
    expect(
      () => result.confirmedMessageUris.add('content://sms/101'),
      throwsUnsupportedError,
    );
    expect(
      () => result.confirmedGenerations['content://sms/100'] = '2000',
      throwsUnsupportedError,
    );
  });
}
