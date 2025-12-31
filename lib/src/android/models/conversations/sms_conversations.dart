// DEPRECATED: This model has been superseded by AndroidSimpleConversation.
// The plugin uses mms_sms_simple_conversations.dart for conversation queries.
// This file can be safely removed in a future major version.

// To parse this JSON data, do
//
//     final smsConversations = smsConversationsFromJson(jsonString);

class AndroidSmsConversations {
  String? sourceLabel;
  String? snippet;
  int? threadId;
  int? msgCount;

  AndroidSmsConversations({
    this.sourceLabel,
    this.snippet,
    this.threadId,
    this.msgCount,
  });

  factory AndroidSmsConversations.fromJson(Map<String, dynamic> json) =>
      AndroidSmsConversations(
        sourceLabel: json["sourceLabel"],
        snippet: json["snippet"],
        threadId: json["thread_id"],
        msgCount: json["msg_count"],
      );

  Map<String, dynamic> toJson() => {
    "sourceLabel": sourceLabel,
    "snippet": snippet,
    "thread_id": threadId,
    "msg_count": msgCount,
  };
}
