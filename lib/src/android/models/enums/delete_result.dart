/// Verified outcome of an `AndroidDestructiveAction` delete (UNFY-244).
///
/// [deleted] and [alreadyAbsent] mean the target is no longer on the device;
/// [newMessagesArrived] means everything it held is gone but new messages
/// re-populated the thread mid-delete. A delete that could not be verified
/// (the app is not the default SMS app, the provider ignored or only partly
/// applied the delete, the read-back failed, or the provider threw) is never
/// one of these values — it throws [DeleteFailedException] instead.
enum DeleteResult {
  /// The target had rows and they were all deleted.
  deleted('deleted'),

  /// No messages needed deleting and nothing remains (a thread with no
  /// messages may still have had its empty thread row pruned). A target the
  /// provider doesn't return counts as deleted, including a message its
  /// Android 14+ per-user subscription filter hides from this app.
  alreadyAbsent('alreadyAbsent'),

  /// Thread deletes only: every message the thread held before the delete is
  /// gone, but new messages arrived while it ran, so the thread is on the
  /// device again holding only those.
  newMessagesArrived('newMessagesArrived');

  const DeleteResult(this.wireName);

  /// The value the native side sends over the method channel.
  final String wireName;

  /// Parse the native method-channel value.
  ///
  /// Throws [StateError] for a null, non-String or unrecognized value rather
  /// than defaulting: guessing either value here would misreport whether the
  /// target is actually gone.
  static DeleteResult fromWire(Object? raw) {
    for (final result in values) {
      if (result.wireName == raw) return result;
    }
    throw StateError('Unrecognized delete result from native: "$raw"');
  }
}

/// Why an `AndroidDestructiveAction` delete couldn't be verified (UNFY-244).
///
/// Carried by [DeleteFailedException]; in every case the target may still be
/// on the device.
enum DeleteFailureReason {
  /// The app does not hold the default-SMS role, so Android would silently
  /// ignore the delete; nothing was attempted.
  notDefaultSmsApp('NOT_DEFAULT_SMS_APP'),

  /// Rows that existed before the delete remain (the provider ignored or
  /// partly applied it), or the thread's messages and its conversation-list
  /// row disagree. Messages that merely arrived mid-delete are reported as
  /// [DeleteResult.newMessagesArrived] instead.
  deleteIncomplete('DELETE_INCOMPLETE'),

  /// The provider threw during the delete, or the target couldn't be read
  /// before it (in which case nothing was deleted).
  deleteFailed('DELETE_FAILED'),

  /// The delete ran but the target couldn't be read back.
  verifyFailed('VERIFY_FAILED'),

  /// The id is not a positive numeric provider id, or the channel is missing.
  invalidArgument('INVALID_ARGUMENT');

  const DeleteFailureReason(this.code);

  /// The `PlatformException.code` the native side (`DestructiveActions.kt`)
  /// sends for this reason; both sides' tests pin these literals.
  final String code;

  /// The reason the native side reports with [code], or null when [code] is
  /// not a delete failure code. Callers must not default a null to a reason.
  static DeleteFailureReason? tryFromCode(String code) {
    for (final reason in values) {
      if (reason.code == code) return reason;
    }
    return null;
  }
}

/// Thrown by `AndroidDestructiveAction` when a delete can't be verified — the
/// target may still be on the device (UNFY-244).
class DeleteFailedException implements Exception {
  const DeleteFailedException(this.reason, {this.message, this.details});

  /// Why the delete couldn't be verified.
  final DeleteFailureReason reason;

  /// The native side's description, when it sent one.
  final String? message;

  /// The native side's error details (e.g. its stack trace), when it sent any.
  final Object? details;

  @override
  String toString() =>
      message == null
          ? 'DeleteFailedException(${reason.name})'
          : 'DeleteFailedException(${reason.name}): $message';
}
