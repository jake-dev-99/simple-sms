import '../../interop/channels.dart';

/// Non-destructive actions for messages and contacts.
///
/// Provides methods to mark messages as read and launch the system contacts
/// app. Notifications are intentionally not included — consumers should use
/// the `simple_notifications` plugin (`SimpleNotifications.showSimple` etc.)
/// for posting notifications.
class AndroidAction {
  /// Marks a single message as read by its database ID.
  static Future<bool> markMessageAsRead(String messageId) async =>
      ActionsInterop.markMessageAsRead(messageId);

  /// Marks all messages in a conversation as read by the conversation's thread ID.
  static Future<bool> markConversationAsRead(String conversationId) async =>
      ActionsInterop.markConversationAsRead(conversationId);

  /// Launches the native contacts app to add a new contact.
  ///
  /// Optionally pre-fills the [phoneNumber] and [name] fields.
  static Future<bool> launchAddContact({
    String? phoneNumber,
    String? name,
  }) async =>
      ActionsInterop.launchAddContact(phoneNumber: phoneNumber, name: name);
}
