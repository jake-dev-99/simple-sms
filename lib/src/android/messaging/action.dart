import '../../interop/channels.dart';
import '../models/enums/sms_mms_enums.dart';

/// Purpose: expose non-destructive actions for messages and contacts.
///
/// Provides methods to mark messages read or unread and launch the system contacts
/// app. Notifications are intentionally not included — consumers should use
/// the `simple_notifications` plugin (`SimpleNotifications.showSimple` etc.)
/// for posting notifications.
///
/// Read-state writes require the default-SMS-app role, obtained through
/// `simple_permissions_native`. These methods never request permissions.
/// They update the native SMS/MMS store, which remains authoritative after
/// subsequent lookups. Both explicit directions are supported; existing
/// mark-as-read callers need no migration (UNFY-114).
class AndroidAction {
  /// Purpose: mark a single message as read by its native id within [channel].
  ///
  /// [channel] selects the SMS vs MMS table: the native `_id` is unique only
  /// within its own table, so the channel is required to target the right
  /// message — there is no SMS-first fallback (UNFY-213). Callers hold the
  /// channel already (it is part of the message identity in the read contract).
  /// Sets both `READ` and `SEEN` to 1 in that table.
  ///
  /// @param [messageId] Native row ID within the selected SMS or MMS table.
  /// @param [channel] The message's type, required to prevent ID collisions.
  /// @returns True when the provider reports an updated row; false otherwise.
  /// @throws `PlatformException` with `MARK_READ_FAILED` on provider failure.
  static Future<bool> markMessageAsRead(
    String messageId, {
    required SmsMmsType channel,
  }) async => ActionsInterop.markMessageAsRead(messageId, channel);

  /// Purpose: mark a conversation read in both native message tables.
  ///
  /// Updates unread SMS and MMS rows, setting both `READ` and `SEEN` to 1.
  /// @param [conversationId] Native thread ID shared by its SMS and MMS rows.
  /// @returns True if either table reports changed rows; false if neither does,
  /// including an already-read or missing conversation.
  /// @throws `PlatformException` with `MARK_READ_FAILED` on provider failure.
  static Future<bool> markConversationAsRead(String conversationId) async =>
      ActionsInterop.markConversationAsRead(conversationId);

  /// Purpose: mark a message unread by its native id within [channel] — the
  /// symmetric inverse of [markMessageAsRead] (UNFY-205, native-authoritative
  /// read-state per ADR-0015). Sets the native `READ` flag to 0 so the message
  /// re-surfaces as unread. [channel] selects the SMS vs MMS table (the native
  /// `_id` is unique only within its own table; no SMS-first fallback — UNFY-213).
  /// Leaves `SEEN` unchanged so restoring a follow-up cue does not make the
  /// message newly unseen.
  ///
  /// @param [messageId] Native row ID within the selected SMS or MMS table.
  /// @param [channel] The message's type, required to prevent ID collisions.
  /// @returns True when the provider reports an updated row; false otherwise.
  /// @throws `PlatformException` with `MARK_UNREAD_FAILED` on provider failure.
  static Future<bool> markMessageAsUnread(
    String messageId, {
    required SmsMmsType channel,
  }) async => ActionsInterop.markMessageAsUnread(messageId, channel);

  /// Purpose: mark all read messages in a conversation unread by the thread ID —
  /// the inverse of [markConversationAsRead].
  ///
  /// Updates SMS and MMS rows with `READ = 1`, setting only `READ` to 0.
  /// @param [conversationId] Native thread ID shared by its SMS and MMS rows.
  /// @returns True if either table reports changed rows; false if neither does,
  /// including an already-unread or missing conversation.
  /// @throws `PlatformException` with `MARK_UNREAD_FAILED` on provider failure.
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
