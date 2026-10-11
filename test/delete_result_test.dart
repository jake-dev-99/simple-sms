import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/android.dart';

/// Destructive deletes resolve only to a verified [DeleteResult]; anything the
/// native side could not verify surfaces as an error, never as a value the
/// caller could mistake for "the target is gone" (UNFY-244).
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const destructive = MethodChannel(
    'io.simplezen.simple_sms/destructive_actions',
  );
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  late List<MethodCall> calls;

  void reply(Future<Object?> Function() result) {
    messenger.setMockMethodCallHandler(destructive, (call) {
      calls.add(call);
      return result();
    });
  }

  setUp(() => calls = []);
  tearDown(() => messenger.setMockMethodCallHandler(destructive, null));

  group('AndroidDestructiveAction.deleteThread', () {
    test('forwards the thread id on the destructive channel', () async {
      reply(() async => 'deleted');
      await AndroidDestructiveAction.deleteThread('123');
      expect(calls.single.method, 'deleteThread');
      expect(calls.single.arguments, '123');
    });

    test('maps "deleted"', () async {
      reply(() async => 'deleted');
      expect(
        await AndroidDestructiveAction.deleteThread('1'),
        DeleteResult.deleted,
      );
    });

    test('maps "newMessagesArrived"', () async {
      reply(() async => 'newMessagesArrived');
      expect(
        await AndroidDestructiveAction.deleteThread('1'),
        DeleteResult.newMessagesArrived,
      );
    });

    test('maps "alreadyAbsent"', () async {
      reply(() async => 'alreadyAbsent');
      expect(
        await AndroidDestructiveAction.deleteThread('1'),
        DeleteResult.alreadyAbsent,
      );
    });

    test('throws a typed failure instead of reporting a result', () async {
      reply(
        () async =>
            throw PlatformException(
              code: 'DELETE_INCOMPLETE',
              message: 'thread 1 still has 2 rows after delete',
            ),
      );
      await expectLater(
        AndroidDestructiveAction.deleteThread('1'),
        throwsA(
          isA<DeleteFailedException>()
              .having(
                (e) => e.reason,
                'reason',
                DeleteFailureReason.deleteIncomplete,
              )
              .having(
                (e) => e.message,
                'message',
                'thread 1 still has 2 rows after delete',
              ),
        ),
      );
    });

    test(
      'throws StateError on an unrecognized error code, keeping its message',
      () async {
        reply(
          () async =>
              throw PlatformException(
                code: 'SOMETHING_NEW',
                message: 'native detail',
              ),
        );
        await expectLater(
          AndroidDestructiveAction.deleteThread('1'),
          throwsA(
            isA<StateError>().having(
              (e) => e.message,
              'message',
              contains('native detail'),
            ),
          ),
        );
      },
    );

    test('throws on a null result rather than defaulting', () async {
      reply(() async => null);
      await expectLater(
        AndroidDestructiveAction.deleteThread('1'),
        throwsStateError,
      );
    });

    test('throws StateError on a non-String result', () async {
      reply(() async => true);
      await expectLater(
        AndroidDestructiveAction.deleteThread('1'),
        throwsStateError,
      );
    });

    test('throws on an unrecognized result rather than defaulting', () async {
      reply(() async => 'partial');
      await expectLater(
        AndroidDestructiveAction.deleteThread('1'),
        throwsStateError,
      );
    });
  });

  group('AndroidDestructiveAction.deleteMessage', () {
    test('maps the verified result', () async {
      reply(() async => 'alreadyAbsent');
      expect(
        await AndroidDestructiveAction.deleteMessage(
          lookupId: '7',
          channel: SmsMmsType.sms,
        ),
        DeleteResult.alreadyAbsent,
      );
    });

    test('throws a typed failure instead of reporting a result', () async {
      reply(() async => throw PlatformException(code: 'NOT_DEFAULT_SMS_APP'));
      await expectLater(
        AndroidDestructiveAction.deleteMessage(
          lookupId: '7',
          channel: SmsMmsType.mms,
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

    test(
      'throws absenceUnverified when a missing message may be hidden',
      () async {
        reply(() async => throw PlatformException(code: 'ABSENCE_UNVERIFIED'));
        await expectLater(
          AndroidDestructiveAction.deleteMessage(
            lookupId: '7',
            channel: SmsMmsType.mms,
          ),
          throwsA(
            isA<DeleteFailedException>().having(
              (e) => e.reason,
              'reason',
              DeleteFailureReason.absenceUnverified,
            ),
          ),
        );
      },
    );

    test('throws on a null result rather than defaulting', () async {
      reply(() async => null);
      await expectLater(
        AndroidDestructiveAction.deleteMessage(
          lookupId: '7',
          channel: SmsMmsType.sms,
        ),
        throwsStateError,
      );
    });
  });

  // The native side (DestructiveActions.kt) sends these exact literals and its
  // Robolectric tests pin them too, so a rename on either side fails a test.
  test('wire values match the native contract', () {
    expect(DeleteResult.deleted.wireName, 'deleted');
    expect(DeleteResult.alreadyAbsent.wireName, 'alreadyAbsent');
    expect(DeleteResult.newMessagesArrived.wireName, 'newMessagesArrived');
    expect(DeleteResult.values, hasLength(3));
    expect(
      {for (final r in DeleteFailureReason.values) r: r.code},
      {
        DeleteFailureReason.notDefaultSmsApp: 'NOT_DEFAULT_SMS_APP',
        DeleteFailureReason.deleteIncomplete: 'DELETE_INCOMPLETE',
        DeleteFailureReason.deleteFailed: 'DELETE_FAILED',
        DeleteFailureReason.verifyFailed: 'VERIFY_FAILED',
        DeleteFailureReason.absenceUnverified: 'ABSENCE_UNVERIFIED',
        DeleteFailureReason.invalidArgument: 'INVALID_ARGUMENT',
      },
    );
  });

  test('DeleteFailedException describes itself without a "null" message', () {
    expect(
      const DeleteFailedException(
        DeleteFailureReason.notDefaultSmsApp,
      ).toString(),
      'DeleteFailedException(notDefaultSmsApp)',
    );
    expect(
      const DeleteFailedException(
        DeleteFailureReason.deleteFailed,
        message: 'boom',
      ).toString(),
      'DeleteFailedException(deleteFailed): boom',
    );
  });
}
