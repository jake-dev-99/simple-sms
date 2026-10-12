import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/android.dart';

// Purpose: protect queued send identity at the Dart platform payload boundary.
/// @param None The test runner supplies the environment.
/// @returns Registers the outbound identity regression.
/// @throws None Test failures are reported by the test runner.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('outbound correlation survives the platform payload round trip', () {
    final message = OutboundMessage.fromJson({
      'body': 'same text',
      'recipients': ['5551234'],
      'clientCorrelationId': 'job-one',
    });
    expect(message.toJson()['clientCorrelationId'], 'job-one');
  });

  test('recovery sends a top-level URI list and decodes the native map', () async {
    const channel = MethodChannel('io.simplezen.simple_sms/messaging');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final uris = ['content://sms/7'];
    final reply = {
      'content://sms/7': {
        'clientCorrelationId': 'job',
        'latestMessageUri': 'content://sms/7',
      },
    };
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'getSendCorrelations');
      expect(call.arguments, uris);
      return reply;
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
    expect(await AndroidMessaging.getSendCorrelations(uris), reply);
  });

}
