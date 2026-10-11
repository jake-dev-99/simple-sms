import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/android.dart';

/// UNFY-306: an engine that never calls [AndroidMessaging.initialize] — such
/// as a notification plugin's headless action engine — can still send.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const messaging = MethodChannel('io.simplezen.simple_sms/messaging');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(messaging, null);
  });

  test('send works without initialize and returns the message row', () async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(messaging, (call) async {
      calls.add(call);
      return jsonEncode({'_id': 42, 'uri': 'content://sms/42'});
    });

    final row = await AndroidMessaging.send(
      message: OutboundMessage(
        body: 'on my way',
        addresses: {'+15551234567'},
        attachmentPaths: const {},
        subscriptionId: 2,
      ),
    );

    expect(row, {'_id': 42, 'uri': 'content://sms/42'});
    expect(calls.single.method, 'sendMessage');
    expect(calls.single.arguments, {
      'body': 'on my way',
      'attachmentPaths': <String>[],
      'recipients': ['+15551234567'],
      'conversationId': null,
      'subscriptionId': 2,
    });
  });

  test('send surfaces the platform error unchanged', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(messaging, (call) async {
      throw PlatformException(code: 'NO_TELEPHONY');
    });

    await expectLater(
      AndroidMessaging.send(
        message: OutboundMessage(
          body: 'hi',
          addresses: {'+15551234567'},
          attachmentPaths: const {},
        ),
      ),
      throwsA(isA<PlatformException>()
          .having((e) => e.code, 'code', 'NO_TELEPHONY')),
    );
  });
}
