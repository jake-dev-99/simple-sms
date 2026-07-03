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

  /// Marks a single message as UNREAD by its native id within [channel] — the
  /// symmetric inverse of [markMessageAsRead] (UNFY-205, native-authoritative
  /// read-state per ADR-0015). Sets the native `READ` flag to 0 so the message
  /// re-surfaces as unread. [channel] selects the SMS vs MMS table (the native
  /// `_id` is unique only within its own table; no SMS-first fallback — UNFY-213).
  static Future<bool> markMessageAsUnread(
    String messageId, {
    required SmsMmsType channel,
  }) async =>
      ActionsInterop.markMessageAsUnread(messageId, channel);

  /// Marks all read messages in a conversation as unread by the thread ID —
  /// the inverse of [markConversationAsRead].
  static Future<bool> markConversationAsUnread(String conversationId) async =>
      ActionsInterop.markConversationAsUnread(conversationId);

  /// Launches the native contacts app to add a new contact.
  ///
  /// Optionally pre-fills the [phoneNumber] and [name] fields.
  static Future<bool> launchAddContact({
    String? phoneNumber,
    String? name,
  }) async =>
      ActionsInterop.launchAddContact(phoneNumber: phoneNumber, name: name);
}
