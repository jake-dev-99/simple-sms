import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/android.dart';

// Purpose: protect queued send identity at the Dart platform payload boundary.
/// @param None The test runner supplies the environment.
/// @returns Registers the outbound identity regression.
/// @throws None Test failures are reported by the test runner.
void main() {
  test('outbound correlation survives the platform payload round trip', () {
    final message = OutboundMessage.fromJson({
      'body': 'same text',
      'recipients': ['5551234'],
      'clientCorrelationId': 'job-one',
    });
    expect(message.toJson()['clientCorrelationId'], 'job-one');
  });
}
