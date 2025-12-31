class OutboundMessage {
  final String body;
  final String? conversationId;
  final Set<String> addresses;
  final Set<String>? attachmentPaths;

  OutboundMessage({
    required this.body,
    required this.addresses,
    required this.attachmentPaths,
    this.conversationId,
  });

  static OutboundMessage fromJson(Map<String, dynamic> json) => OutboundMessage(
    body: json['body'] as String,
    attachmentPaths: json['attachmentPaths'],
    addresses: Set<String>.from(json['recipients']),
    conversationId: json['conversationId'],
  );

  Map<String, dynamic> toJson() => {
    'body': body,
    'attachmentPaths': attachmentPaths,
    'recipients': addresses.toList(),
    'conversationId': conversationId,
  };
}
