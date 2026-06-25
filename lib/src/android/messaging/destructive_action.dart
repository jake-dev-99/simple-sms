import '../../interop/channels.dart';
import '../models/enums/sms_mms_enums.dart';

/// Destructive operations for messages and conversations.
///
/// These actions permanently delete data from the device's messaging database.
/// The app must be the default SMS app to perform these operations.
class AndroidDestructiveAction {
  /// Deletes an entire conversation thread and all its messages.
  static Future<bool> deleteThread(String threadId) async =>
      DestructiveActionsInterop.deleteThread(threadId);

  /// Deletes a single message by its native id within [channel].
  ///
  /// [channel] selects the SMS vs MMS table: the native `_id` is unique only
  /// within its own table, so the channel is required to delete the right
  /// message — there is no SMS-first fallback (UNFY-213).
  static Future<bool> deleteMessage({
    required String lookupId,
    required SmsMmsType channel,
  }) async =>
      DestructiveActionsInterop.deleteMessage(lookupId, channel);
}
