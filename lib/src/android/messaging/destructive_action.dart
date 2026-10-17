import '../../interop/channels.dart';
import '../models/enums/delete_result.dart';
import '../models/enums/sms_mms_enums.dart';
import 'verified_write.dart';
import '../models/messages/send_deletion_receipt.dart';

/// Destructive operations for messages and conversations.
///
/// These actions permanently delete data from the device's messaging database.
/// The app must be the default SMS app to perform these operations.
///
/// Every delete is verified (UNFY-244): the native side reads the target back
/// after deleting and resolves to a [DeleteResult] only when nothing that
/// existed before the delete remains — [DeleteResult.newMessagesArrived] means the thread is on the device again
/// with messages that arrived mid-delete, so the caller must keep its record
/// of it.
/// Otherwise it throws a [DeleteFailedException] carrying the
/// [DeleteFailureReason]; an unrecognized native result or error code throws
/// [StateError].
class AndroidDestructiveAction {
  /// Delete every retained native attempt of a correlated send, at most 64 rows.
  ///
  /// The selected URI must belong to [clientCorrelationId] in [threadId].
  /// All generations are checked before the durable plan is created. Older
  /// attempts go first, the actual latest attempt last, and further retries
  /// of this job are fenced before radio handoff. This does not unsend a
  /// message already handed to the carrier. Repeating the same job resumes
  /// an interrupted plan without targeting reused row generations.
  ///
  /// A provider failure returns a partial receipt with [SendDeletionReceipt.failureReason].
  /// Preflight/storage failure throws [DeleteFailedException]; pending progress
  /// remains readable. Journal every confirmation before acknowledging it.
  static Future<SendDeletionReceipt> deleteSend({
    required String messageUri,
    required String clientCorrelationId,
    required String threadId,
  }) async => SendDeletionReceipt.fromWire(
    await _verifiedValue(
      () => DestructiveActionsInterop.deleteSend(
        messageUri,
        clientCorrelationId,
        threadId,
      ),
    ),
  );

  /// Page the indexed recovery journal (maximum 64 outstanding operations).
  ///
  /// Each receipt contains at most 64 row confirmations. [limit] is 1..64;
  /// resume after the last returned job ID with [afterClientCorrelationId].
  /// Reads neither mutate provider rows nor acknowledge host journal writes.
  static Future<List<SendDeletionReceipt>> pendingSendDeletions({
    String? threadId,
    String? afterClientCorrelationId,
    int limit = 20,
  }) async {
    RangeError.checkValueInInterval(limit, 1, 64, 'limit');
    final raw = await _verifiedValue(
      () => DestructiveActionsInterop.pendingSendDeletions(
        threadId,
        afterClientCorrelationId,
        limit,
      ),
    );
    if (raw is! List || raw.length > limit) {
      throw const FormatException('Invalid pending send deletion page');
    }
    return List.unmodifiable(raw.map(SendDeletionReceipt.fromWire));
  }

  /// Acknowledge exact confirmed identities after the host persists them.
  ///
  /// Empty acknowledgements retire completed plans whose rows were already
  /// acknowledged before an interrupted completion commit. Idempotent for
  /// previously acknowledged identities. Unknown or unconfirmed
  /// identities fail without dropping any recovery evidence. Cancellation
  /// markers survive acknowledgement so a delayed retry cannot send again.
  static Future<void> acknowledgeSendDeletion({
    required String clientCorrelationId,
    required List<String> messageUris,
  }) async {
    final result = await _verifiedValue(
      () => DestructiveActionsInterop.acknowledgeSendDeletion(
        clientCorrelationId,
        messageUris,
      ),
    );
    if (result != null) {
      throw const FormatException('Invalid send deletion acknowledgement');
    }
  }

  /// Deletes an entire conversation thread — its messages and the thread row.
  ///
  /// May resolve to [DeleteResult.newMessagesArrived] when messages arrive in
  /// the thread while the delete runs.
  static Future<DeleteResult> deleteThread(String threadId) =>
      _verified(() => DestructiveActionsInterop.deleteThread(threadId));

  /// Deletes a single message by its native id within [channel].
  ///
  /// [channel] selects the SMS vs MMS table: the native `_id` is unique only
  /// within its own table, so the channel is required to delete the right
  /// message — there is no SMS-first fallback (UNFY-213). Never resolves to
  /// [DeleteResult.newMessagesArrived]. A message the provider doesn't return
  /// resolves to [DeleteResult.alreadyAbsent].
  static Future<DeleteResult> deleteMessage({
    required String lookupId,
    required SmsMmsType channel,
  }) => _verified(
    () => DestructiveActionsInterop.deleteMessage(lookupId, channel),
  );

  /// Run a native delete and translate its reply into the typed contract.
  static Future<DeleteResult> _verified(
    Future<Object?> Function() invoke,
  ) async => DeleteResult.fromWire(await _verifiedValue(invoke));

  static Future<Object?> _verifiedValue(Future<Object?> Function() invoke) =>
      invokeVerifiedWrite(
        operation: 'delete',
        invoke: invoke,
        reasonFor: DeleteFailureReason.tryFromCode,
        failure: (reason, e) => DeleteFailedException(
          reason,
          message: e.message,
          details: e.details,
        ),
      );
}
