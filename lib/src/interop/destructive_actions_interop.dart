import 'dart:async';
import 'package:flutter/services.dart';

// TODO: Create an interface for interop

class DestructiveActionsInterop {
  static const MethodChannel methodChannel = MethodChannel(
    'io.simplezen.simple_sms/destructive_actions',
  );

  static Future<bool> deleteThread(String threadId) async =>
      await methodChannel.invokeMethod<bool>("deleteThread", threadId) ?? false;

  static Future<bool> deleteContact(String contactId) async =>
      await methodChannel.invokeMethod<bool>("deleteContact", contactId) ??
      false;

  static Future<bool> deleteMessage(String messageId) async =>
      await methodChannel.invokeMethod<bool>("deleteMessage", messageId) ??
      false;
}
