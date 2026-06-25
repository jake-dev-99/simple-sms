import 'dart:convert';

import 'package:flutter/services.dart';

import '../android/models/enums/sms_mms_enums.dart';
import '../android/models/messages/outbound_message.dart';

/// Single source of truth for the Dart-side method channels this plugin
/// speaks to. Three channels cover the full surface area:
///
/// * `io.simplezen.simple_sms/messaging` — outbound send.
/// * `io.simplezen.simple_sms/actions` — non-destructive actions
///   (mark-read, launch-contact).
/// * `io.simplezen.simple_sms/destructive_actions` — thread / message
///   deletion.
///
/// Inbound messaging lives on a fourth channel
/// (`io.simplezen.simple_sms/inbound_messaging`) that `AndroidMessaging`
/// owns directly because its handler lifecycle is tied to the messaging
/// singleton.
///
/// Keeping every channel literal here (and every `invokeMethod` shape)
/// makes the channel inventory auditable in one file rather than
/// scattered across three tiny shims.
class _Channels {
  static const messaging = MethodChannel('io.simplezen.simple_sms/messaging');
  static const actions = MethodChannel('io.simplezen.simple_sms/actions');
  static const destructiveActions =
      MethodChannel('io.simplezen.simple_sms/destructive_actions');
}

/// Outbound send interop.
class OutboundMessagingInterop {
  static const methodChannel = _Channels.messaging;

  static Future<Map<String, dynamic>> sendMessage(
    OutboundMessage message,
  ) async {
    final result = await methodChannel.invokeMethod<String>(
      'sendMessage',
      message.toJson(),
    );
    return jsonDecode(result ?? '{}');
  }
}

/// Non-destructive message + contact actions.
class ActionsInterop {
  static const methodChannel = _Channels.actions;

  /// Mark one message read in [channel]'s native table. The native `_id` is
  /// unique only within its own table, so [channel] (sent as `SmsMmsType.name`)
  /// is required to target the right message — no SMS-first guess (UNFY-213).
  static Future<bool> markMessageAsRead(
    String messageId,
    SmsMmsType channel,
  ) async =>
      await methodChannel.invokeMethod<bool>('markMessageAsRead', {
        'messageId': messageId,
        'channel': channel.name,
      }) ??
      false;

  static Future<bool> markConversationAsRead(String conversationId) async =>
      await methodChannel.invokeMethod<bool>(
        'markConversationAsRead',
        conversationId,
      ) ??
      false;

  /// Launch the native contacts app to add a new contact.
  /// Optionally pre-fill [phoneNumber] and [name].
  static Future<bool> launchAddContact({
    String? phoneNumber,
    String? name,
  }) async =>
      await methodChannel.invokeMethod<bool>('launchAddContact', {
        'phoneNumber': phoneNumber,
        'name': name,
      }) ??
      false;
}

/// Destructive (irrecoverable) thread + message deletion.
class DestructiveActionsInterop {
  static const methodChannel = _Channels.destructiveActions;

  static Future<bool> deleteThread(String threadId) async =>
      await methodChannel.invokeMethod<bool>('deleteThread', threadId) ?? false;

  /// Delete one message from [channel]'s native table. [channel] (sent as
  /// `SmsMmsType.name`) targets the right table — the native `_id` is unique
  /// only within its own table (UNFY-213).
  static Future<bool> deleteMessage(
    String messageId,
    SmsMmsType channel,
  ) async =>
      await methodChannel.invokeMethod<bool>('deleteMessage', {
        'messageId': messageId,
        'channel': channel.name,
      }) ??
      false;
}
