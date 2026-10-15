import 'package:flutter/services.dart';

import '../../interop/channels.dart';
import '../models/enums/delete_result.dart';

/// A durable native identity pending the consumer's deletion journal.
/// [scope] is an opaque conversation identifier, never message content.
class NativeDeletionReceipt {
  const NativeDeletionReceipt({
    required this.id,
    required this.scope,
    required this.messageUri,
    required this.confirmed,
    this.clientCorrelationId,
    this.jobDeletionComplete = false,
  });

  final int id;
  final String scope;
  final String messageUri;
  final bool confirmed;

  /// Queue identity fenced by this deletion, if the app originally supplied one.
  final String? clientCorrelationId;

  /// True only after every native attempt is gone; partial deletions keep the job.
  final bool jobDeletionComplete;

  factory NativeDeletionReceipt.fromWire(Object? raw) {
    if (raw is! Map ||
        raw['id'] is! int ||
        (raw['id'] as int) <= 0 ||
        raw['scope'] is! String ||
        (raw['scope'] as String).isEmpty ||
        (raw['scope'] as String).length > 256 ||
        raw['messageUri'] is! String ||
        !_isMessageUri(raw['messageUri'] as String) ||
        raw['confirmed'] is! bool ||
        (raw['clientCorrelationId'] != null &&
            (raw['clientCorrelationId'] is! String ||
                (raw['clientCorrelationId'] as String).isEmpty)) ||
        (raw['jobDeletionComplete'] != null &&
            raw['jobDeletionComplete'] is! bool)) {
      throw const FormatException('Malformed native deletion receipt');
    }
    return NativeDeletionReceipt(
      id: raw['id'] as int,
      scope: raw['scope'] as String,
      messageUri: raw['messageUri'] as String,
      confirmed: raw['confirmed'] as bool,
      clientCorrelationId: raw['clientCorrelationId'] as String?,
      jobDeletionComplete: raw['jobDeletionComplete'] == true,
    );
  }
}

/// Logical-send deletion and durable receipt handover (UNFY-346).
abstract interface class NativeSendDeletion {
  /// Fence retries and delete all indexed attempts. Partial failures retain receipts.
  Future<void> delete({required String scope, required String messageUri});

  /// Read at most [limit] outstanding plans/receipts in ascending id order.
  ///
  /// [after] is a temporary page cursor for one drain, never a saved watermark.
  /// Start EVERY new drain with `after: 0`, advance past all returned ids (also
  /// unconfirmed plans), and stop at a short page. A later drain must start at
  /// zero because an unconfirmed plan can become confirmed without changing id.
  /// Only [acknowledge] removes a row; failed handovers remain available.
  Future<List<NativeDeletionReceipt>> pending({int after = 0, int limit = 64});

  /// Acknowledge only after the caller's deletion journal has committed.
  Future<void> acknowledge(int id);
}

/// Android implementation. Requires ReadSms; deletion also requires the SMS
/// role. Exact provider generations protect reuse. No thread scan is performed.
class AndroidSendDeletion implements NativeSendDeletion {
  const AndroidSendDeletion();

  @override
  Future<void> delete({
    required String scope,
    required String messageUri,
  }) async {
    final canonicalUri = _canonicalDeletionUri(messageUri);
    if (scope.trim().isEmpty || scope.length > 256 || canonicalUri == null) {
      throw ArgumentError('Invalid logical-send deletion identity');
    }
    await _invoke('deleteMessageAttempts', {
      'scope': scope,
      'messageUri': canonicalUri,
    });
  }

  @override
  Future<List<NativeDeletionReceipt>> pending({
    int after = 0,
    int limit = 64,
  }) async {
    if (after < 0 || limit < 1 || limit > 64) {
      throw ArgumentError('Invalid receipt page');
    }
    final raw = await _invoke('pendingMessageDeletions', {
      'after': after,
      'limit': limit,
    });
    if (raw is! List || raw.length > limit) {
      throw const FormatException('Malformed native deletion page');
    }
    final page = <NativeDeletionReceipt>[];
    var previous = after;
    for (final value in raw) {
      final row = NativeDeletionReceipt.fromWire(value);
      if (row.id <= previous) {
        throw const FormatException('Unordered native deletion receipts');
      }
      page.add(row);
      previous = row.id;
    }
    return page;
  }

  @override
  Future<void> acknowledge(int id) async {
    if (id <= 0) throw ArgumentError.value(id, 'id');
    await _invoke('acknowledgeMessageDeletion', id);
  }

  /// Preserve the existing typed native deletion failure vocabulary.
  static Future<Object?> _invoke(String method, Object arguments) async {
    try {
      return await DestructiveActionsInterop.methodChannel
          .invokeMethod<Object?>(method, arguments);
    } on PlatformException catch (e) {
      final reason = DeleteFailureReason.tryFromCode(e.code);
      if (reason == null) rethrow;
      throw DeleteFailedException(
        reason,
        message: e.message,
        details: e.details,
      );
    }
  }
}

bool _isMessageUri(String value) =>
    RegExp(r'^content://(sms|mms)/[1-9][0-9]*$').hasMatch(value);

// Delete accepts the same box aliases as the native correlation registry;
// receipts still require canonical table-qualified identities.
String? _canonicalDeletionUri(String value) {
  final match = RegExp(
    r'^content://(sms|mms)/(?:inbox/|outbox/|sent/|drafts/|failed/|queued/)?([0-9]+)$',
  ).firstMatch(value);
  if (match == null) return null;
  final id = BigInt.tryParse(match.group(2)!);
  if (id == null ||
      id <= BigInt.zero ||
      id > BigInt.parse('9223372036854775807')) {
    return null;
  }
  return 'content://${match.group(1)}/$id';
}
