import 'dart:async';
import 'package:flutter/services.dart';

class DestructiveActionsInterop {
  static const MethodChannel methodChannel = MethodChannel(
    'io.simplezen.simple_sms/destructive_actions',
  );

  static Future<bool> deleteThread(String threadId) async =>
      await methodChannel.invokeMethod<bool>("deleteThread", threadId) ?? false;

  static Future<bool> deleteMessage(String messageId) async =>
      await methodChannel.invokeMethod<bool>("deleteMessage", messageId) ??
      false;
}
