import '../../interop/channels.dart';

/// Destructive operations for messages and conversations.
///
/// These actions permanently delete data from the device's messaging database.
/// The app must be the default SMS app to perform these operations.
class AndroidDestructiveAction {
  /// Deletes an entire conversation thread and all its messages.
  static Future<bool> deleteThread(String threadId) async =>
      DestructiveActionsInterop.deleteThread(threadId);

  /// Deletes a single message by its database ID.
  static Future<bool> deleteMessage({required String lookupId}) async =>
      DestructiveActionsInterop.deleteMessage(lookupId);
}
