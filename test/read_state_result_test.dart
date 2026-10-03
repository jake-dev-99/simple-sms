import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/android.dart';

/// Purpose: preserve read-state outcomes across the public Dart/native boundary.
///
/// @returns No value; registers the UNFY-114 contract tests.
/// @throws Test failures when a command hides or changes a native result.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const actions = MethodChannel('io.simplezen.simple_sms/actions');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() => messenger.setMockMethodCallHandler(actions, null));

  test('markConversationAsRead forwards the native thread ID', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(actions, (call) async {
      calls.add(call);
      return true;
    });

    expect(await AndroidAction.markConversationAsRead('123'), isTrue);
    expect(calls.single.method, 'markConversationAsRead');
    expect(calls.single.arguments, '123');
  });

  final commands = <String, Future<bool> Function()>{
    'markMessageAsRead':
        () => AndroidAction.markMessageAsRead('42', channel: SmsMmsType.sms),
    'markMessageAsUnread':
        () => AndroidAction.markMessageAsUnread('42', channel: SmsMmsType.mms),
    'markConversationAsRead': () => AndroidAction.markConversationAsRead('123'),
    'markConversationAsUnread':
        () => AndroidAction.markConversationAsUnread('123'),
  };

  for (final command in commands.entries) {
    test('${command.key} preserves a false provider result', () async {
      messenger.setMockMethodCallHandler(actions, (call) async => false);
      expect(await command.value(), isFalse);
    });

    test('${command.key} propagates provider errors', () async {
      messenger.setMockMethodCallHandler(actions, (call) async {
        throw PlatformException(code: 'PROVIDER_FAILURE');
      });
      await expectLater(
        command.value(),
        throwsA(
          isA<PlatformException>().having(
            (error) => error.code,
            'code',
            'PROVIDER_FAILURE',
          ),
        ),
      );
    });
  }
}
