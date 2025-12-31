import 'dart:async';
import 'dart:convert';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:simple_sms/simple_sms.dart';


// TODO: Create an interface for interop

class MessagingInterop {
  static const MethodChannel methodChannel = MethodChannel(
    'io.simplezen.simple_sms/messaging',
  );

  static Future<dynamic> sendMessage(Mms message) async {
    try {
      return await methodChannel.invokeMethod<Mms>(
        "sendMessage",
        message.toJson(),
      );
    } catch (e, s) {
      debugPrint(e.toString());
      debugPrint(s.toString());
      return null;
    }
  }
}

class OutboundMessagingInterop {
  static const MethodChannel methodChannel = MethodChannel(
    'io.simplezen.simple_sms/messaging',
  );

  static Future<Map<String, dynamic>> sendMessage(
    OutboundMessage message,
  ) async {
    try {
      debugPrint(' >>> Sending message: ${message.toJson()}');
      final messageJson = message.toJson();
      messageJson['attachmentPaths'] = message.attachmentPaths?.toList();

      final result = await methodChannel.invokeMethod<String>(
        "sendMessage",
        messageJson,
      );
      return jsonDecode(result ?? '{}');
    } catch (e, s) {
      debugPrint(e.toString());
      debugPrint(s.toString());
      Error.throwWithStackTrace(e, s);
    }
  }
}
