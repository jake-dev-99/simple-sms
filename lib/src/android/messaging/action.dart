import '../../interop/channels.dart';
import '../models/enums/sms_mms_enums.dart';

/// Non-destructive actions for messages and contacts.
///
/// Provides methods to mark messages as read and launch the system contacts
/// app. Notifications are intentionally not included — consumers should use
/// the `simple_notifications` plugin (`SimpleNotifications.showSimple` etc.)
/// for posting notifications.
class AndroidAction {
  /// Marks a single message as read by its native id within [channel].
  ///
  /// [channel] selects the SMS vs MMS table: the native `_id` is unique only
  /// within its own table, so the channel is required to target the right
  /// message — there is no SMS-first fallback (UNFY-213). Callers hold the
  /// channel already (it is part of the message identity in the read contract).
  static Future<bool> markMessageAsRead(
    String messageId, {
    required SmsMmsType channel,
  }) async =>
      ActionsInterop.markMessageAsRead(messageId, channel);

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
