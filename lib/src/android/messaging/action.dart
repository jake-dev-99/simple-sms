import 'package:simple_sms/src/interop/actions_interop.dart';

class AndroidAction {
  /// Mark a message as read
  static Future<bool> markMessageAsRead(String messageId) async =>
      ActionsInterop.markMessageAsRead(messageId);

  /// Mark a message as read
  static Future<bool> markConversationAsRead(String messageId) async =>
      ActionsInterop.markMessageAsRead(messageId);

  /// Send a notification
  static Future<bool> sendNotification({
    required String title,
    required String body,
  }) async => ActionsInterop.sendNotification(title, body);
}
