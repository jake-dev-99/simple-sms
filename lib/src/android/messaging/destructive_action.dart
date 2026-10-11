import 'package:flutter/services.dart';

import '../../interop/channels.dart';
import '../models/enums/delete_result.dart';
import '../models/enums/sms_mms_enums.dart';

/// Destructive operations for messages and conversations.
///
/// These actions permanently delete data from the device's messaging database.
/// The app must be the default SMS app to perform these operations.
///
/// Every delete is verified (UNFY-244): the native side reads the target back
/// after deleting and resolves to a [DeleteResult] only when nothing that
/// existed before the delete remains and the target's absence is provable —
/// [DeleteResult.newMessagesArrived] means the thread is on the device again
/// with messages that arrived mid-delete, so the caller must keep its record
/// of it.
/// Otherwise it throws a [DeleteFailedException] carrying the
/// [DeleteFailureReason]; an unrecognized native result or error code throws
/// [StateError].
class AndroidDestructiveAction {
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
  ) async {
    final Object? reply;
    try {
      reply = await invoke();
    } on PlatformException catch (e) {
      final reason = DeleteFailureReason.tryFromCode(e.code);
      if (reason == null) {
        // Not a delete failure code (e.g. a new native code or an engine
        // channel error): never guess a reason, but keep what native sent.
        throw StateError(
          'Unrecognized delete error code from native: "${e.code}" '
          '(${e.message}) ${e.details ?? ''}',
        );
      }
      throw DeleteFailedException(
        reason,
        message: e.message,
        details: e.details,
      );
    }
    return DeleteResult.fromWire(reply);
  }
}
