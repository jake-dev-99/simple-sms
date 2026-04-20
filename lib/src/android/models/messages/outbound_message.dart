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
  ///
  /// Tolerates `attachmentPaths` / `recipients` arriving as either a List
  /// (the canonical on-wire shape emitted by [toJson] and the interop
  /// layer) or a Set (a stale in-memory copy). `body` is required;
  /// recipients defaults to empty rather than throwing.
  static OutboundMessage fromJson(Map<String, dynamic> json) => OutboundMessage(
    body: json['body']?.toString() ?? '',
    attachmentPaths: _asStringSet(json['attachmentPaths']),
    addresses: _asStringSet(json['recipients']) ?? const <String>{},
    conversationId: json['conversationId']?.toString(),
  );

  /// Converts this message to a JSON map for platform channel transport.
  ///
  /// Emits `addresses` / `attachmentPaths` as Lists so the map is directly
  /// encodable over MethodChannel / jsonEncode without any downstream
  /// patching; the interop layer used to swap the Set for a List after
  /// this call — now unnecessary.
  Map<String, dynamic> toJson() => {
    'body': body,
    'attachmentPaths': attachmentPaths?.toList(),
    'recipients': addresses.toList(),
    'conversationId': conversationId,
  };

  /// Coerces an on-wire collection into a `Set<String>`, tolerating nulls,
  /// Lists, and Sets. Returns null only when [raw] itself is null.
  static Set<String>? _asStringSet(dynamic raw) {
    if (raw == null) return null;
    if (raw is Iterable) {
      return raw.map((e) => e.toString()).toSet();
    }
    return {raw.toString()};
  }
}
