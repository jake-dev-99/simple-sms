import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/android.dart';

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
