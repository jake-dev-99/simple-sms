import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/android.dart';

/// Native draft writes resolve only to a verified outcome; anything the
/// native side could not verify surfaces as an error, never as a value the
/// caller could mistake for "the draft is written" (UNFY-213, ADR-0016).
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const drafts = MethodChannel('io.simplezen.simple_sms/drafts');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  late List<MethodCall> calls;

  void reply(Future<Object?> Function() result) {
    messenger.setMockMethodCallHandler(drafts, (call) {
      calls.add(call);
      return result();
    });
  }

  setUp(() => calls = []);
  tearDown(() => messenger.setMockMethodCallHandler(drafts, null));

  group('AndroidDraftAction.saveSmsDraft', () {
    test('forwards threadId + body on the drafts channel', () async {
      reply(() async => '42');
      await AndroidDraftAction.saveSmsDraft(threadId: '5', body: 'see you');
      expect(calls.single.method, 'saveSmsDraft');
      expect(calls.single.arguments, {
        'threadId': '5',
        'body': 'see you',
        'subscriptionId': null,
      });
    });

    test('forwards the subscription when given', () async {
      reply(() async => '42');
      await AndroidDraftAction.saveSmsDraft(
        threadId: '5',
        body: 'hi',
        subscriptionId: 2,
      );
      expect(calls.single.arguments, {
        'threadId': '5',
        'body': 'hi',
        'subscriptionId': 2,
      });
    });

    test('resolves to the native draft id', () async {
      reply(() async => '42');
      expect(
        await AndroidDraftAction.saveSmsDraft(threadId: '5', body: 'hi'),
        '42',
      );
    });

    for (final bad in <Object?>[null, '', '0', '-1', 'x', 42]) {
      test('throws StateError for an unrecognized reply ($bad)', () async {
        reply(() async => bad);
        await expectLater(
          AndroidDraftAction.saveSmsDraft(threadId: '5', body: 'hi'),
          throwsStateError,
        );
      });
    }

    test('throws a typed failure instead of reporting an id', () async {
      reply(
        () async =>
            throw PlatformException(
              code: 'THREAD_NOT_FOUND',
              message: 'Thread 5 is not on the device',
            ),
      );
      await expectLater(
        AndroidDraftAction.saveSmsDraft(threadId: '5', body: 'hi'),
        throwsA(
          isA<DraftWriteFailedException>()
              .having(
                (e) => e.reason,
                'reason',
                DraftWriteFailureReason.threadNotFound,
              )
              .having(
                (e) => e.message,
                'message',
                'Thread 5 is not on the device',
              ),
        ),
      );
    });
  });

  group('AndroidDraftAction.saveMmsDraft', () {
    Future<String> save({
      String? body,
      Set<String> addresses = const {'+15555550100'},
      List<String> attachmentPaths = const ['/cache/photo.jpg'],
      int? subscriptionId,
    }) => AndroidDraftAction.saveMmsDraft(
      threadId: '5',
      addresses: addresses,
      body: body,
      attachmentPaths: attachmentPaths,
      subscriptionId: subscriptionId,
    );

    test('forwards the thread, recipients, body and files', () async {
      reply(() async => '42');
      await save(
        body: 'see you',
        addresses: {'+15555550100', '+15555550101'},
        attachmentPaths: ['/cache/a.jpg', '/cache/b.pdf'],
      );
      expect(calls.single.method, 'saveMmsDraft');
      expect(calls.single.arguments, {
        'threadId': '5',
        'addresses': ['+15555550100', '+15555550101'],
        'body': 'see you',
        'attachmentPaths': ['/cache/a.jpg', '/cache/b.pdf'],
        'subscriptionId': null,
      });
    });

    test('forwards a missing body and a subscription', () async {
      reply(() async => '42');
      await save(subscriptionId: 2);
      expect(calls.single.arguments, {
        'threadId': '5',
        'addresses': ['+15555550100'],
        'body': null,
        'attachmentPaths': ['/cache/photo.jpg'],
        'subscriptionId': 2,
      });
    });

    test('resolves to the native draft id', () async {
      reply(() async => '42');
      expect(await save(), '42');
    });

    for (final bad in <Object?>[null, '', '0', '-1', 'x', 42]) {
      test('throws StateError for an unrecognized reply ($bad)', () async {
        reply(() async => bad);
        await expectLater(save(), throwsStateError);
      });
    }

    test('throws a typed failure instead of reporting an id', () async {
      reply(
        () async => throw PlatformException(
          code: 'DRAFT_WRITE_INCOMPLETE',
          message: 'The recipients resolve to thread 9',
        ),
      );
      await expectLater(
        save(),
        throwsA(
          isA<DraftWriteFailedException>()
              .having(
                (e) => e.reason,
                'reason',
                DraftWriteFailureReason.draftWriteIncomplete,
              )
              .having(
                (e) => e.message,
                'message',
                'The recipients resolve to thread 9',
              ),
        ),
      );
    });
  });

  group('AndroidDraftAction.discardDraft', () {
    test('forwards the thread id on the drafts channel', () async {
      reply(() async => 'discarded');
      await AndroidDraftAction.discardDraft('5');
      expect(calls.single.method, 'discardDraft');
      expect(calls.single.arguments, '5');
    });

    for (final result in DraftDiscardResult.values) {
      test('maps "${result.wireName}"', () async {
        reply(() async => result.wireName);
        expect(await AndroidDraftAction.discardDraft('5'), result);
      });
    }

    for (final bad in <Object?>[null, true, 'deleted', 'DISCARDED']) {
      test('throws StateError for an unrecognized result ($bad)', () async {
        reply(() async => bad);
        await expectLater(
          AndroidDraftAction.discardDraft('5'),
          throwsStateError,
        );
      });
    }
  });

  group('failure codes', () {
    /// The literals `DraftActions.kt` sends; the native tests pin the same set.
    const codes = {
      'NOT_DEFAULT_SMS_APP': DraftWriteFailureReason.notDefaultSmsApp,
      'THREAD_NOT_FOUND': DraftWriteFailureReason.threadNotFound,
      'DRAFT_WRITE_FAILED': DraftWriteFailureReason.draftWriteFailed,
      'DRAFT_WRITE_INCOMPLETE': DraftWriteFailureReason.draftWriteIncomplete,
      'VERIFY_FAILED': DraftWriteFailureReason.verifyFailed,
      'INVALID_ARGUMENT': DraftWriteFailureReason.invalidArgument,
    };

    test('every reason has exactly its native code', () {
      expect({
        for (final r in DraftWriteFailureReason.values) r.code: r,
      }, codes);
    });

    final writes = <String, Future<Object?> Function()>{
      'discardDraft': () => AndroidDraftAction.discardDraft('5'),
      'saveMmsDraft': () => AndroidDraftAction.saveMmsDraft(
        threadId: '5',
        addresses: {'+15555550100'},
        attachmentPaths: ['/cache/photo.jpg'],
      ),
    };

    for (final MapEntry(key: code, value: reason) in codes.entries) {
      for (final MapEntry(key: name, value: write) in writes.entries) {
        test(
          '$name: $code becomes DraftWriteFailedException(${reason.name})',
          () async {
            reply(
              () async =>
                  throw PlatformException(code: code, details: 'native trace'),
            );
            await expectLater(
              write(),
              throwsA(
                isA<DraftWriteFailedException>()
                    .having((e) => e.reason, 'reason', reason)
                    .having((e) => e.details, 'details', 'native trace'),
              ),
            );
          },
        );
      }
    }

    test('an unknown code throws StateError, never a guessed reason', () async {
      reply(() async => throw PlatformException(code: 'DELETE_FAILED'));
      await expectLater(
        AndroidDraftAction.saveSmsDraft(threadId: '5', body: 'hi'),
        throwsStateError,
      );
    });

    test(
      'a missing native handler is not mistaken for a draft failure',
      () async {
        await expectLater(
          AndroidDraftAction.discardDraft('5'),
          throwsA(isA<MissingPluginException>()),
        );
      },
    );
  });
}
