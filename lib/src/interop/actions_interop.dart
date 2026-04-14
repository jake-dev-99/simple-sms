import 'dart:async';
import 'package:flutter/services.dart';

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

  /// Launch the native contacts app to add a new contact.
  /// Optionally pre-fill [phoneNumber] and [name].
  static Future<bool> launchAddContact({
    String? phoneNumber,
    String? name,
  }) async =>
      await methodChannel.invokeMethod<bool>("launchAddContact", {
        'phoneNumber': phoneNumber,
        'name': name,
      }) ??
      false;
}
