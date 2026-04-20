import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import '../../interop/messaging.dart';
import '../models/messages/mms.dart';
import '../models/messages/outbound_message.dart';
import '../models/messages/sms.dart';

/// Manages SMS/MMS message sending and receiving on Android.
///
/// This class is a singleton — call [initialize] once at app startup,
/// then access via [instance].
///
/// Incoming messages are delivered through the callbacks provided to
/// [initialize]. When the app is in the foreground, messages arrive
/// immediately. When the app is killed, the native Android layer creates
/// a background Flutter engine and calls the `initializeApp` Dart
/// entrypoint to deliver queued messages.
@pragma('vm:entry-point')
class AndroidMessaging {
  /// Returns the singleton instance. Throws if [initialize] has not been called.
  static AndroidMessaging get instance => _instance!;

  static AndroidMessaging? _instance;

  /// Callback invoked for each incoming MMS message.
  Function(Mms) mmsCallback;

  /// Callback invoked for each incoming SMS message.
  Function(Sms) smsCallback;

  AndroidMessaging._internal({
    required this.mmsCallback,
    required this.smsCallback,
  }) {
    final inboundChannel = MethodChannel(
      'io.simplezen.simple_sms/inbound_messaging',
    );
    inboundChannel.setMethodCallHandler(receiveMessage);
  }

  /// Initializes the messaging singleton with callbacks for incoming messages.
  ///
  /// Must be called before [instance] is accessed. Safe to call multiple
  /// times — subsequent calls update the callbacks on the existing
  /// singleton so the last caller always wins (matching what a developer
  /// naturally expects when they re-invoke `Android.initialize` from a
  /// hot-reload path or a background-engine entrypoint).
  factory AndroidMessaging.initialize({
    required Function(Sms) inboundSmsCallback,
    required Function(Mms) inboundMmsCallback,
  }) {
    final existing = _instance;
    if (existing != null) {
      existing.smsCallback = inboundSmsCallback;
      existing.mmsCallback = inboundMmsCallback;
      return existing;
    }
    return _instance = AndroidMessaging._internal(
      smsCallback: inboundSmsCallback,
      mmsCallback: inboundMmsCallback,
    );
  }

  /// Sends an SMS or MMS message.
  ///
  /// Returns the message record as stored in the Android SMS/MMS database
  /// after the message has been sent (or failed). The returned map contains
  /// all database columns for the sent message.
  ///
  /// **Access state:** requires the `SendSms` permission; writing the
  /// sent record into the Telephony store additionally requires the
  /// `DefaultSmsApp` role. Check + request via `simple_permissions_native`:
  ///
  /// ```dart
  /// import 'package:simple_permissions_native/simple_permissions_native.dart';
  ///
  /// final ok = await SimplePermissionsNative.instance.requestAll(
  ///   const [SendSms(), DefaultSmsApp()],
  /// );
  /// if (!ok.isFullyGranted) return;
  /// await android.messaging.sendMessage(message: ...);
  /// ```
  ///
  /// Throws a [PlatformException] if the device lacks telephony capability
  /// or if sending fails.
  Future<Map<String, dynamic>> sendMessage({
    required OutboundMessage message,
  }) async {
    return OutboundMessagingInterop.sendMessage(message);
  }

  /// Internal handler for incoming messages from the native platform.
  @pragma('vm:entry-point')
  Future<bool> receiveMessage(MethodCall methodCall) async {
    WidgetsFlutterBinding.ensureInitialized();

    if (methodCall.arguments is! String) {
      throw PlatformException(
        code: 'INVALID_ARGUMENT_TYPE',
        message:
            'simple_sms: Expected JSON string, got ${methodCall.arguments.runtimeType} for method ${methodCall.method}',
        details: {'arguments': methodCall.arguments?.toString()},
      );
    }

    final String jsonString = methodCall.arguments as String;
    Map<String, dynamic> messageData;

    try {
      messageData = jsonDecode(jsonString);
    } on FormatException catch (e, s) {
      debugPrint('simple_sms: JSON parse error for ${methodCall.method}: $e');
      throw PlatformException(
        code: 'JSON_PARSE_ERROR',
        message:
            'Failed to parse JSON arguments for ${methodCall.method}: ${e.message}',
        details: {'arguments': jsonString, 'stackTrace': s.toString()},
      );
    }

    try {
      if (methodCall.method == 'receiveInboundSmsMessage') {
        final Sms smsMessage = Sms.fromRaw(messageData);
        return await smsCallback(smsMessage);
      } else if (methodCall.method == 'receiveInboundMmsMessage') {
        final Mms mmsMessage = Mms.fromRaw(messageData);
        return await mmsCallback(mmsMessage);
      } else {
        throw PlatformException(
          code: 'UNKNOWN_METHOD',
          message:
              "simple_sms: Unknown method '${methodCall.method}' on inbound_messaging channel",
          details: {'method': methodCall.method},
        );
      }
    } catch (e, s) {
      if (e is PlatformException) rethrow;
      debugPrint('simple_sms: Error processing ${methodCall.method}: $e');
      throw PlatformException(
        code: '${methodCall.method.toUpperCase()}_PROCESSING_ERROR',
        message: 'Error processing ${methodCall.method}: $e',
        details: {'error': e.toString(), 'stackTrace': s.toString()},
      );
    }
  }
}
