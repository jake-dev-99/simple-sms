import 'dart:async';
import 'package:flutter/services.dart';

// TODO: Create an interface for interop

class ActionsInterop {
  static const MethodChannel methodChannel = MethodChannel(
    'io.simplezen.simple_sms/actions',
  );

  static Future<bool> markMessageAsRead(String messageId) async =>
      await methodChannel.invokeMethod<bool>("markMessageAsRead", messageId) ??
      false;

  static Future<bool> markConversationAsRead(String conversationId) async =>
      await methodChannel.invokeMethod<bool>(
        "markConversationAsRead",
        conversationId,
      ) ??
      false;

  static Future<bool> sendNotification(String title, String body) async =>
      await methodChannel.invokeMethod<bool>("sendNotification", {
        'title': title,
        'body': body,
      }) ??
      false;
}
