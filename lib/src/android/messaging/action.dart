import 'package:simple_sms/src/interop/actions_interop.dart';

/// Non-destructive actions for messages and contacts.
///
/// Provides methods to mark messages as read, send local notifications,
/// and launch the system contacts app.
class AndroidAction {
  /// Marks a single message as read by its database ID.
  static Future<bool> markMessageAsRead(String messageId) async =>
      ActionsInterop.markMessageAsRead(messageId);

  /// Marks all messages in a conversation as read by the conversation's thread ID.
  static Future<bool> markConversationAsRead(String conversationId) async =>
      ActionsInterop.markConversationAsRead(conversationId);

  /// Shows a local notification with the given [title] and [body].
  static Future<bool> sendNotification({
    required String title,
    required String body,
  }) async => ActionsInterop.sendNotification(title, body);

  /// Launches the native contacts app to add a new contact.
  ///
  /// Optionally pre-fills the [phoneNumber] and [name] fields.
  static Future<bool> launchAddContact({
    String? phoneNumber,
    String? name,
  }) async =>
      ActionsInterop.launchAddContact(phoneNumber: phoneNumber, name: name);
}
