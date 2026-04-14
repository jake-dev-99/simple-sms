import 'dart:convert';
import 'package:flutter/services.dart';
import 'package:simple_sms_native/simple_sms.dart';

class OutboundMessagingInterop {
  static const MethodChannel methodChannel = MethodChannel(
    'io.simplezen.simple_sms/messaging',
  );

  static Future<Map<String, dynamic>> sendMessage(
    OutboundMessage message,
  ) async {
    final messageJson = message.toJson();
    messageJson['attachmentPaths'] = message.attachmentPaths?.toList();

    final result = await methodChannel.invokeMethod<String>(
      "sendMessage",
      messageJson,
    );
    return jsonDecode(result ?? '{}');
  }
}
