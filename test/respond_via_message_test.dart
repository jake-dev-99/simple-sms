import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/simple_sms_native.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const method = 'receiveRespondViaMessage';
  final payload = <String, Object?>{
    'recipients': ['5554', '5556'],
    'body': "Can't talk now",
    'subscriptionId': 3,
  };

  AndroidMessaging messaging({
    FutureOr<void> Function(RespondViaMessageRequest)? callback,
  }) {
    final messaging = AndroidMessaging.initialize(
      inboundSmsCallback: (_) {},
      inboundMmsCallback: (_) {},
      respondViaMessageCallback: callback,
    );
    // The singleton keeps the callback an earlier test registered.
    messaging.respondViaMessageCallback = callback;
    return messaging;
  }

  Future<bool> receive(
    AndroidMessaging messaging,
    Map<String, Object?> body,
  ) => messaging.receiveMessage(MethodCall(method, jsonEncode(body)));

  Matcher processingError() => throwsA(
    isA<PlatformException>().having(
      (e) => e.code,
      'code',
      'INBOUND_MESSAGE_PROCESSING_ERROR',
    ),
  );

  test('routes a request to the callback', () async {
    final received = <RespondViaMessageRequest>[];
    final target = messaging(callback: received.add);

    expect(await receive(target, payload), isTrue);

    expect(received, hasLength(1));
    expect(received.single.recipients, ['5554', '5556']);
    expect(received.single.body, "Can't talk now");
    expect(received.single.subscriptionId, 3);
  });

  test('acknowledges only after an asynchronous callback completes', () async {
    final completion = Completer<void>();
    var acknowledged = false;
    final result = receive(
      messaging(callback: (_) => completion.future),
      payload,
    )..then((_) => acknowledged = true);

    await Future<void>.delayed(Duration.zero);
    expect(acknowledged, isFalse);
    completion.complete();
    expect(await result, isTrue);
  });

  test('accepts a request without a subscription', () async {
    final received = <RespondViaMessageRequest>[];
    final target = messaging(callback: received.add);

    await receive(target, {...payload, 'subscriptionId': null});

    expect(received.single.subscriptionId, isNull);
  });

  test('fails without a callback', () async {
    await expectLater(
      receive(messaging(), payload),
      throwsA(
        isA<PlatformException>().having(
          (e) => e.code,
          'code',
          'NO_RESPOND_VIA_MESSAGE_HANDLER',
        ),
      ),
    );
  });

  test('keeps the registered callback when re-initialized without one', () {
    void callback(RespondViaMessageRequest _) {}
    messaging(callback: callback);

    final again = AndroidMessaging.initialize(
      inboundSmsCallback: (_) {},
      inboundMmsCallback: (_) {},
    );

    expect(again.respondViaMessageCallback, same(callback));
  });

  test('rejects a payload without recipients', () async {
    final target = messaging(callback: (_) {});
    for (final recipients in <Object?>[null, <String>[], [''], [5554], '5554']) {
      await expectLater(
        receive(target, {...payload, 'recipients': recipients}),
        processingError(),
        reason: 'recipients: $recipients',
      );
    }
  });

  test('rejects a payload without text', () async {
    final target = messaging(callback: (_) {});
    for (final body in <Object?>[null, '', '  ', 5]) {
      await expectLater(
        receive(target, {...payload, 'body': body}),
        processingError(),
        reason: 'body: $body',
      );
    }
  });

  test('rejects a non-integer subscription', () async {
    final target = messaging(callback: (_) {});
    for (final subscription in <Object>['3', 3.5, true]) {
      await expectLater(
        receive(target, {...payload, 'subscriptionId': subscription}),
        processingError(),
        reason: 'subscriptionId: $subscription',
      );
    }
  });
}
