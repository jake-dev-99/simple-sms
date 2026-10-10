import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/simple_sms_native.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  for (final isMms in [false, true]) {
    final name = isMms ? 'MMS' : 'SMS';
    final method =
        isMms ? 'receiveInboundMmsMessage' : 'receiveInboundSmsMessage';
    final payload = <String, Object>{
      '_id': 42,
      'thread_id': 7,
      'date': isMms ? 1700000000 : 1700000000000,
      'body': 'Inbound regression test',
      if (isMms) 'm_type': 132 else 'type': 1,
    };

    Future<bool> receive(Function callback) {
      final messaging = AndroidMessaging.initialize(
        inboundSmsCallback: (sms) => callback(sms),
        inboundMmsCallback: (mms) => callback(mms),
      );
      return messaging.receiveMessage(MethodCall(method, jsonEncode(payload)));
    }

    group('$name inbound callback acknowledgement', () {
      test('synchronous void callback completes successfully', () async {
        final received = <Object>[];
        expect(
          await receive((Object message) {
            received.add(message);
          }),
          isTrue,
        );
        expect(received, hasLength(1));
        expect(
          isMms ? (received.single as Mms).id : (received.single as Sms).id,
          42,
        );
      });

      test(
        'asynchronous void callback is awaited before acknowledgement',
        () async {
          final completion = Completer<void>();
          var acknowledged = false;
          final result = receive((Object message) => completion.future)
            ..then((_) {
              acknowledged = true;
            });
          await Future<void>.delayed(Duration.zero);
          expect(acknowledged, isFalse);
          completion.complete();
          expect(await result, isTrue);
        },
      );

      for (final accepted in [true, false]) {
        test('preserves synchronous Boolean $accepted', () async {
          expect(await receive((Object message) => accepted), accepted);
        });
        test('preserves asynchronous Boolean $accepted', () async {
          expect(await receive((Object message) async => accepted), accepted);
        });
      }

      test('callback exceptions remain visible as platform errors', () async {
        await expectLater(
          receive((Object message) => throw StateError('callback failed')),
          throwsA(
            isA<PlatformException>()
                .having(
                  (e) => e.code,
                  'code',
                  'INBOUND_MESSAGE_PROCESSING_ERROR',
                )
                .having(
                  (e) => e.message,
                  'message',
                  contains('callback failed'),
                ),
          ),
        );
      });

      test('unexpected return values are rejected', () async {
        await expectLater(
          receive((Object message) => 'unexpected'),
          throwsA(
            isA<PlatformException>().having(
              (e) => e.code,
              'code',
              'INBOUND_MESSAGE_PROCESSING_ERROR',
            ),
          ),
        );
      });
    });
  }
}
