// ignore_for_file: unused_field, avoid_print
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import '../../interop/messaging.dart';
import '../models/messages/mms.dart';
import '../models/messages/outbound_message.dart';
import '../models/messages/sms.dart';

/// Handler for incoming SMS/MMS messages
@pragma('vm:entry-point')
class AndroidMessaging {
  // If the instance is null, throw an error - it needs to be instantiated prior to use
  static AndroidMessaging get instance => _instance!;

  static AndroidMessaging? _instance;
  Function(Mms) mmsCallback;
  Function(Sms) smsCallback;
  AndroidMessaging._internal({
    required this.mmsCallback,
    required this.smsCallback,
  }) {
    // Use a single channel name consistent with InboundMessaging.kt
    final inboundChannel = MethodChannel(
      'io.simplezen.simple_sms/inbound_messaging', // Unified channel name
    );
    inboundChannel.setMethodCallHandler(receiveMessage);
  }

  factory AndroidMessaging.initialize({
    required Function(Sms) inboundSmsCallback,
    required Function(Mms) inboundMmsCallback,
  }) {
    _instance ??= AndroidMessaging._internal(
      smsCallback: inboundSmsCallback,
      mmsCallback: inboundMmsCallback,
    );

    return _instance!;
  }

  Future<Map<String, dynamic>> sendMessage({
    required OutboundMessage message,
  }) async {
    print(' >>> Sending message');

    try {
      print(" >>> Sending: $message");

      // Create a messageMap payload that includes message and attachments
      final result = await OutboundMessagingInterop.sendMessage(message);

      return result;
    } catch (e, s) {
      print(" >>> Error sending message: $e");
      print(s.toString());
      throw Exception(e);
    }
  }

  @pragma('vm:entry-point')
  Future<bool> receiveMessage(MethodCall methodCall) async {
    WidgetsFlutterBinding.ensureInitialized();
    print(
      " >>> [SimpleSmsPlugin] UnifiedChannelHandler: method='${methodCall.method}', arguments_type='${methodCall.arguments.runtimeType}', arguments='${methodCall.arguments}'",
    );

    if (methodCall.arguments is! String) {
      final String errorMessage =
          " >>> [SimpleSmsPlugin] Error: Received non-string or null arguments. Method: ${methodCall.method}, Type: ${methodCall.arguments.runtimeType}, Value: ${methodCall.arguments}";
      print(errorMessage);
      throw PlatformException(
        code: 'INVALID_ARGUMENT_TYPE',
        message:
            ' >>> Expected JSON string, got ${methodCall.arguments.runtimeType} for method ${methodCall.method}',
        details: {'arguments': methodCall.arguments?.toString()},
      );
    }

    final String jsonString = methodCall.arguments as String;
    Map<String, dynamic> messageData;

    try {
      messageData = jsonDecode(jsonString);
    } on FormatException catch (e, s) {
      final String errorMessage =
          " >>> [SimpleSmsPlugin] JSON Parsing Error for method ${methodCall.method}: $e\nInput: $jsonString\nStackTrace: $s";
      debugPrint(errorMessage);
      throw PlatformException(
        code: 'JSON_PARSE_ERROR',
        message:
            'Failed to parse JSON arguments for ${methodCall.method}: ${e.message}',
        details: {'arguments': jsonString, 'stackTrace': s.toString()},
      );
    }

    try {
      if (methodCall.method == 'receiveInboundSmsMessage') {
        print(' >>> (SimpleSmsPlugin) Received SMS message:  $messageData');
        final Sms smsMessage = Sms.fromRaw(messageData);
        return await smsCallback(smsMessage);
      } else if (methodCall.method == 'receiveInboundMmsMessage') {
        print(' >>> (SimpleSmsPlugin) Received MMS message:  $messageData');
        final Mms mmsMessage = await Mms.fromRaw(messageData);
        print(' >>> (SimpleSmsPlugin) Sending MMS message to callback');
        return await mmsCallback(mmsMessage);
      } else {
        final String errorMessage =
            " >>> [SimpleSmsPlugin] Error: Unknown method '${methodCall.method}' on channel 'io.simplezen.simple_sms/inbound_messaging'";
        print(errorMessage);
        throw PlatformException(
          code: 'UNKNOWN_METHOD',
          message: errorMessage,
          details: {'method': methodCall.method},
        );
      }
    } catch (e, s) {
      final String errorMessage =
          " >>> [SimpleSmsPlugin] Error processing ${methodCall.method}: $e\nStackTrace: $s";
      print(errorMessage);
      throw PlatformException(
        code: '${methodCall.method.toUpperCase()}_PROCESSING_ERROR',
        message: ' >>> Error processing ${methodCall.method}: ${e.toString()}',
        details: {'error': e.toString(), 'stackTrace': s.toString()},
      );
    }
  }
}
