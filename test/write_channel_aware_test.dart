import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/android.dart';

/// The per-message write surface (mark-read, delete) must carry the channel so
/// the native side targets the one correct table — the native `_id` is unique
/// only within its own SMS/MMS table (UNFY-213). These pin that the Dart API
/// forwards `SmsMmsType.name` to the plugin, and never an un-disambiguated id.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const actions = MethodChannel('io.simplezen.simple_sms/actions');
  const destructive =
      MethodChannel('io.simplezen.simple_sms/destructive_actions');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  late List<MethodCall> calls;

  void mock(MethodChannel channel) {
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return true;
    });
  }

  setUp(() => calls = []);
  tearDown(() {
    messenger.setMockMethodCallHandler(actions, null);
    messenger.setMockMethodCallHandler(destructive, null);
  });

  group('AndroidAction.markMessageAsRead', () {
    test('forwards messageId + channel.name (mms) on the actions channel',
        () async {
      mock(actions);
      final ok =
          await AndroidAction.markMessageAsRead('42', channel: SmsMmsType.mms);
      expect(ok, isTrue);
      expect(calls.single.method, 'markMessageAsRead');
      expect(calls.single.arguments, {'messageId': '42', 'channel': 'mms'});
    });

    test('forwards the sms channel discriminator', () async {
      mock(actions);
      await AndroidAction.markMessageAsRead('7', channel: SmsMmsType.sms);
      expect(calls.single.arguments, {'messageId': '7', 'channel': 'sms'});
    });
  });

  group('AndroidAction.markMessageAsUnread', () {
    test('forwards messageId + channel.name (mms) on the actions channel',
        () async {
      mock(actions);
      final ok =
          await AndroidAction.markMessageAsUnread('42', channel: SmsMmsType.mms);
      expect(ok, isTrue);
      expect(calls.single.method, 'markMessageAsUnread');
      expect(calls.single.arguments, {'messageId': '42', 'channel': 'mms'});
    });

    test('forwards the sms channel discriminator', () async {
      mock(actions);
      await AndroidAction.markMessageAsUnread('7', channel: SmsMmsType.sms);
      expect(calls.single.arguments, {'messageId': '7', 'channel': 'sms'});
    });
  });

  group('AndroidAction.markConversationAsUnread', () {
    test('forwards the thread id on the actions channel', () async {
      mock(actions);
      final ok = await AndroidAction.markConversationAsUnread('123');
      expect(ok, isTrue);
      expect(calls.single.method, 'markConversationAsUnread');
      expect(calls.single.arguments, '123');
    });
  });

  group('AndroidDestructiveAction.deleteMessage', () {
    test('forwards messageId + channel.name (sms) on the destructive channel',
        () async {
      mock(destructive);
      final ok = await AndroidDestructiveAction.deleteMessage(
        lookupId: '7',
        channel: SmsMmsType.sms,
      );
      expect(ok, isTrue);
      expect(calls.single.method, 'deleteMessage');
      expect(calls.single.arguments, {'messageId': '7', 'channel': 'sms'});
    });

    test('forwards the mms channel discriminator', () async {
      mock(destructive);
      await AndroidDestructiveAction.deleteMessage(
        lookupId: '99',
        channel: SmsMmsType.mms,
      );
      expect(calls.single.arguments, {'messageId': '99', 'channel': 'mms'});
    });
  });
}
