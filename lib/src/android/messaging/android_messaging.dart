import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import '../../interop/channels.dart';
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
  /// A void callback acknowledges successful completion. Boolean callbacks
  /// preserve their explicit acknowledgement, including `false`.
  Function(Mms) mmsCallback;

  /// Callback invoked for each incoming SMS message.
  /// A void callback acknowledges successful completion. Boolean callbacks
  /// preserve their explicit acknowledgement, including `false`.
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

  // Purpose: recover native send identities without matching message content.
  /// Exact persisted queue identities for SMS/MMS provider URIs.
  /// Available without initializing inbound callbacks. No message content.
  /// Requires ReadSms permission to verify each row's recorded provider date.
  /// Deleted or generation-changed rows are pruned; an unavailable provider fails the lookup
  /// without treating its rows as deleted.
  /// Every current recipient row of the latest native attempt returns itself
  /// as latestMessageUri; an older retry points to a surviving latest row.
  /// Stored in Android no-backup SQLite storage; no message content is retained.
  /// @param uris At most 1000 exact SMS/MMS provider row URIs to recover.
  /// @returns Persisted identities and their latest surviving attempt URIs.
  /// @throws PlatformException When native verification or persistence fails.
  /// @throws FormatException When the native reply is missing.
  static Future<Map<String, dynamic>> getSendCorrelations(List<String> uris) async {
    final result = await OutboundMessagingInterop.methodChannel
        .invokeMapMethod<String, dynamic>('getSendCorrelations', uris);
    if (result == null) throw const FormatException('Missing correlation reply');
    return result;
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
  /// or the send can't be started; without the `DefaultSmsApp` role the
  /// provider refuses the SMS row and the send fails with `PERMISSION_DENIED`
  /// before anything is sent. Otherwise the future settles once the
  /// carrier reports the result. A multipart SMS settles once every part
  /// has reported: as failed if any part failed, with the first failure to
  /// arrive, else as sent.
  ///
  /// Normally it resolves with the message row; a send the carrier rejected
  /// has that row marked failed (SMS `type`, MMS `msg_box`). Two
  /// [PlatformException] codes instead settle a send whose row can't be
  /// returned:
  ///
  /// - `MESSAGE_ROW_MISSING`: the message row was gone when read back.
  /// - `SEND_ACK_FAILED`: the provider threw while recording the result or
  ///   reading the row back; `details['cause']` describes the failure.
  ///
  /// For both, `details['sent']` is the carrier result: `true` means the
  /// message went out (don't resend it), `false` that the send failed.
  /// `details` also carry `messageUri`, `eventType`, `resultCode`, and
  /// `statusString`.
  ///
  /// `SEND_RESULT_TIMEOUT` settles a send that got no carrier result within
  /// 10 minutes of device uptime (the timer pauses in deep sleep), e.g. after
  /// the radio process crashed mid-send. Whether it went out is unknown, so
  /// `details['sent']` is `null`. The row, if still on its way out, is marked
  /// failed; a carrier success that arrives later still moves it to sent.
  /// `details` also carry `messageUri` and `timeoutMs`.
  Future<Map<String, dynamic>> sendMessage({
    required OutboundMessage message,
  }) =>
      send(message: message);

  /// [sendMessage] without the singleton. Sending uses none of the inbound
  /// callbacks [initialize] registers, so an engine that never calls it —
  /// such as a notification plugin's headless action engine sending an
  /// inline reply — can still send. Same access state, result and errors as
  /// [sendMessage].
  static Future<Map<String, dynamic>> send({
    required OutboundMessage message,
  }) =>
      OutboundMessagingInterop.sendMessage(message);

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
      switch (methodCall.method) {
        case 'receiveInboundSmsMessage':
          return await _invokeCallback(smsCallback, Sms.fromRaw(messageData));
        case 'receiveInboundMmsMessage':
          return await _invokeCallback(mmsCallback, Mms.fromRaw(messageData));
        default:
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
        // Stable, readable error codes — the previous scheme produced
        // `RECEIVEINBOUNDSMSMESSAGE_PROCESSING_ERROR` via `toUpperCase()`
        // on the method name, which is hard to grep and harder to
        // dashboard against.
        code: 'INBOUND_MESSAGE_PROCESSING_ERROR',
        message: 'Error processing ${methodCall.method}: $e',
        details: {
          'method': methodCall.method,
          'error': e.toString(),
          'stackTrace': s.toString(),
        },
      );
    }
  }

  /// Waits for the handler before acknowledging delivery to Android.
  /// Exceptions propagate through the existing platform-error contract.
  Future<bool> _invokeCallback<T>(Function(T) callback, T message) async {
    final result = await callback(message);
    if (result == null) return true;
    if (result is bool) return result;
    throw StateError(
      'Inbound callbacks must return void or bool, synchronously or '
      'asynchronously; received ${result.runtimeType}.',
    );
  }
}
