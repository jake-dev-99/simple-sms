import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/android.dart';
import 'package:simple_sms_native/src/interop/channels.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const api = AndroidSendDeletion();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(
    () => messenger.setMockMethodCallHandler(
      DestructiveActionsInterop.methodChannel,
      null,
    ),
  );

  test('deletion uses the qualified URI and opaque receipt scope', () async {
    messenger.setMockMethodCallHandler(
      DestructiveActionsInterop.methodChannel,
      (call) async {
        expect(call.method, 'deleteMessageAttempts');
        expect(call.arguments, {
          'scope': 'conversation',
          'messageUri': 'content://mms/7',
        });
        return null;
      },
    );
    await api.delete(scope: 'conversation', messageUri: 'content://mms/7');
  });

  test(
    'receipt pages validate identities and retain unconfirmed entries',
    () async {
      messenger.setMockMethodCallHandler(
        DestructiveActionsInterop.methodChannel,
        (call) async {
          expect(call.arguments, {'after': 2, 'limit': 64});
          return [
            {
              'id': 3,
              'scope': 'conversation',
              'messageUri': 'content://sms/7',
              'confirmed': true,
            },
            {
              'id': 4,
              'scope': 'conversation',
              'messageUri': 'content://mms/7',
              'confirmed': false,
            },
          ];
        },
      );
      final page = await api.pending(after: 2);
      expect(page.map((r) => r.id), [3, 4]);
      expect(page.map((r) => r.confirmed), [true, false]);
      expect(page.first.messageUri, 'content://sms/7');
    },
  );

  test('malformed or unordered receipts fail closed', () async {
    for (final reply in [
      null,
      [
        {
          'id': 1,
          'scope': 'c',
          'messageUri': 'content://sms/7',
          'confirmed': 'true',
        },
      ],
      [
        {
          'id': 1,
          'scope': 'c',
          'messageUri': 'content://contacts/7',
          'confirmed': true,
        },
      ],
      [
        {
          'id': 0,
          'scope': 'c',
          'messageUri': 'content://sms/7',
          'confirmed': true,
        },
      ],
    ]) {
      messenger.setMockMethodCallHandler(
        DestructiveActionsInterop.methodChannel,
        (_) async => reply,
      );
      await expectLater(api.pending(), throwsFormatException);
    }
  });

  test('native deletion errors preserve typed permission failures', () async {
    messenger.setMockMethodCallHandler(
      DestructiveActionsInterop.methodChannel,
      (_) async {
        throw PlatformException(
          code: 'NOT_DEFAULT_SMS_APP',
          message: 'role revoked',
        );
      },
    );
    await expectLater(
      api.delete(scope: 'c', messageUri: 'content://sms/7'),
      throwsA(
        isA<DeleteFailedException>().having(
          (e) => e.reason,
          'reason',
          DeleteFailureReason.notDefaultSmsApp,
        ),
      ),
    );
  });

  test('only positive receipt identities are acknowledged', () async {
    var calls = 0;
    messenger.setMockMethodCallHandler(
      DestructiveActionsInterop.methodChannel,
      (call) async {
        calls++;
        expect(call.method, 'acknowledgeMessageDeletion');
        expect(call.arguments, 7);
        return null;
      },
    );
    await expectLater(api.acknowledge(0), throwsArgumentError);
    await api.acknowledge(7);
    expect(calls, 1);
  });
}
