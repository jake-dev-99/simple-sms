/// A message to be sent via SMS or MMS.
///
/// The plugin automatically chooses SMS or MMS based on:
/// - If [attachmentPaths] is non-empty, sends as MMS
/// - If [body] is >= 160 characters, sends as MMS
/// - If [addresses] has multiple recipients, sends as MMS
/// - Otherwise, sends as SMS
///
/// ```dart
/// final message = OutboundMessage(
///   body: 'Hello from simple_sms!',
///   addresses: {'+15551234567'},
///   attachmentPaths: null,
/// );
/// await android.messaging.sendMessage(message: message);
/// ```
class OutboundMessage {
  /// The text content of the message.
  final String body;

  /// Optional conversation thread ID to continue an existing thread.
  final String? conversationId;

  /// Phone numbers to send the message to.
  final Set<String> addresses;

  /// File paths of attachments to include (images, video, etc.).
  /// When non-null and non-empty, forces MMS delivery.
  final Set<String>? attachmentPaths;

  OutboundMessage({
    required this.body,
    required this.addresses,
    required this.attachmentPaths,
    this.conversationId,
  });

  /// Creates an [OutboundMessage] from a JSON map.
  static OutboundMessage fromJson(Map<String, dynamic> json) => OutboundMessage(
    body: json['body'] as String,
    attachmentPaths: json['attachmentPaths'],
    addresses: Set<String>.from(json['recipients']),
    conversationId: json['conversationId'],
  );

  /// Converts this message to a JSON map for platform channel transport.
  Map<String, dynamic> toJson() => {
    'body': body,
    'attachmentPaths': attachmentPaths,
    'recipients': addresses.toList(),
    'conversationId': conversationId,
  };
}
