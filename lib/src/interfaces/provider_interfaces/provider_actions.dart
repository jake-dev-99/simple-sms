abstract interface class AndroidDeviceAction {
  /// Mark a message as read
  Future<bool> markMessageAsRead(String messageId);

  /// Send a notification
  Future<bool> sendNotification({required String title, required String body});
}
