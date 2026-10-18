/// A quick-reply text Android asked the default SMS app to send
/// (`TelephonyManager.ACTION_RESPOND_VIA_MESSAGE`), e.g. when the user
/// declines a call with a message.
///
/// Delivered to `AndroidMessaging.respondViaMessageCallback`. Sending it is
/// the app's job; the plugin only validates the intent and hands it over.
class RespondViaMessageRequest {
  /// Phone numbers to send [body] to. Never empty.
  final List<String> recipients;

  /// The text to send. Never blank.
  final String body;

  /// Telephony subscription (SIM) the caller asked to send from, or null when
  /// it named none or an invalid one.
  final int? subscriptionId;

  RespondViaMessageRequest({
    required List<String> recipients,
    required this.body,
    this.subscriptionId,
  }) : recipients = List.unmodifiable(recipients);

  /// Parses the payload native sends.
  ///
  /// Throws a [FormatException] for missing, empty or non-string
  /// `recipients`, a missing or blank `body`, and a non-integer
  /// `subscriptionId`. A null `subscriptionId` is valid; no value is
  /// defaulted.
  factory RespondViaMessageRequest.fromRaw(Map<String, dynamic> raw) {
    final recipients = raw['recipients'];
    if (recipients is! List ||
        recipients.isEmpty ||
        recipients.any((r) => r is! String || r.trim().isEmpty)) {
      throw FormatException(
        'Respond-via-message needs a non-empty list of recipients, '
        'got ${recipients.runtimeType}',
      );
    }
    final body = raw['body'];
    if (body is! String || body.trim().isEmpty) {
      throw FormatException(
        'Respond-via-message needs a non-blank body, got ${body.runtimeType}',
      );
    }
    final subscriptionId = raw['subscriptionId'];
    if (subscriptionId != null && subscriptionId is! int) {
      throw FormatException(
        'Respond-via-message subscriptionId must be an integer, '
        'got ${subscriptionId.runtimeType}',
      );
    }
    return RespondViaMessageRequest(
      recipients: recipients.cast<String>(),
      body: body,
      subscriptionId: subscriptionId as int?,
    );
  }
}
