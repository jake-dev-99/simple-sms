/// Extension for destructive operations
abstract interface class DestructiveAction {
  Future<bool> deleteThread(String threadId);
  Future<bool> deleteContact({required String lookupId});
  Future<bool> deleteMessage({required String lookupId});
}
