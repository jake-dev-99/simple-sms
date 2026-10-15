import '../enums/delete_result.dart';

/// Durable, unacknowledged confirmations of one logical-send deletion.
///
/// [complete] means every captured native attempt is gone. A partial failure
/// retains confirmations already committed; journal them before acknowledging
/// them, even when [complete] is false. Reading this receipt never acknowledges
/// it. Native cancellation continues to fence retries after acknowledgement.
class SendDeletionReceipt {
  const SendDeletionReceipt._({
    required this.clientCorrelationId,
    required this.threadId,
    required this.messageUri,
    required this.confirmedMessageUris,
    required this.confirmedGenerations,
    required this.complete,
    this.failureReason,
    this.failureMessage,
  });

  /// The exact queue identity supplied when this native send was created.
  final String clientCorrelationId;

  /// The verified native conversation ID, expressed as a positive decimal.
  final String threadId;

  /// The canonical selected URI that first created this deletion operation.
  final String messageUri;

  /// At most 64 canonical SMS/MMS row URIs, confirmed and not yet acknowledged.
  final List<String> confirmedMessageUris;

  /// Captured provider generations for exactly [confirmedMessageUris].
  /// These raw date discriminators identify the deleted occurrences; a host
  /// must preserve a newer occurrence that reuses the same URI.
  final Map<String, String> confirmedGenerations;

  /// False for an interrupted or partially failed operation; never inferred.
  final bool complete;

  /// The failure of this command, when it returned a partial receipt.
  /// A later journal read contains progress, without inventing a failure.
  final DeleteFailureReason? failureReason;
  final String? failureMessage;

  /// Validate the native contract without guessing missing identities or state.
  /// Throws [FormatException] for malformed or unrecognized native data.
  factory SendDeletionReceipt.fromWire(Object? raw) {
    if (raw is! Map) {
      throw const FormatException('Missing send deletion receipt');
    }
    final token = raw['clientCorrelationId'];
    final thread = raw['threadId'];
    final anchor = raw['messageUri'];
    final done = raw['complete'];
    final uris = raw['confirmedMessageUris'];
    final generations = raw['confirmedGenerations'];
    if (token is! String ||
        token.trim().isEmpty ||
        thread is! String ||
        int.tryParse(thread) == null ||
        int.parse(thread) <= 0 ||
        int.parse(thread).toString() != thread ||
        !_isRowUri(anchor) ||
        done is! bool ||
        uris is! List ||
        uris.length > 64 ||
        generations is! Map) {
      throw const FormatException('Invalid send deletion identity or progress');
    }
    final confirmed = <String>[];
    for (final uri in uris) {
      if (!_isRowUri(uri)) {
        throw const FormatException('Invalid confirmed row URI');
      }
      confirmed.add(uri as String);
    }
    if (confirmed.toSet().length != confirmed.length ||
        generations.length != confirmed.length) {
      throw const FormatException('Ambiguous send deletion confirmations');
    }
    final verifiedGenerations = <String, String>{};
    for (final uri in confirmed) {
      final generation = generations[uri];
      if (generation is! String ||
          int.tryParse(generation) == null ||
          int.parse(generation).toString() != generation) {
        throw const FormatException('Missing confirmed provider generation');
      }
      verifiedGenerations[uri] = generation;
    }
    final code = raw['failureCode'];
    final reason = code is String
        ? DeleteFailureReason.tryFromCode(code)
        : null;
    final message = raw['failureMessage'];
    if ((code != null && reason == null) ||
        (done && code != null) ||
        (message != null && (message is! String || code == null))) {
      throw const FormatException('Invalid send deletion failure');
    }
    return SendDeletionReceipt._(
      clientCorrelationId: token,
      threadId: thread,
      messageUri: anchor as String,
      confirmedMessageUris: List.unmodifiable(confirmed),
      confirmedGenerations: Map.unmodifiable(verifiedGenerations),
      complete: done,
      failureReason: reason,
      failureMessage: message as String?,
    );
  }

  static bool _isRowUri(Object? value) =>
      value is String &&
      RegExp(r'^content://(sms|mms)/[1-9][0-9]*$').hasMatch(value) &&
      int.tryParse(Uri.parse(value).pathSegments.single) != null;
}
