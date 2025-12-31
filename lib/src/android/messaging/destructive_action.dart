import '../../interop/destructive_actions_interop.dart';

class AndroidDestructiveAction {
  static Future<bool> deleteThread(String threadId) async =>
      DestructiveActionsInterop.deleteThread(threadId);

  static Future<bool> deleteMessage({required String lookupId}) async =>
      DestructiveActionsInterop.deleteMessage(lookupId);
}
