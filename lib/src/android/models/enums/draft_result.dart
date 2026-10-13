/// Verified outcome of `AndroidDraftAction.discardSmsDraft` (UNFY-213).
///
/// Both values mean the thread holds no native SMS draft. A discard that could
/// not be verified is never one of these values — it throws
/// [DraftWriteFailedException] instead.
enum DraftDiscardResult {
  /// The thread's draft was deleted and none remains.
  discarded('discarded'),

  /// The thread had no draft to delete.
  alreadyAbsent('alreadyAbsent');

  const DraftDiscardResult(this.wireName);

  /// The value the native side sends over the method channel.
  final String wireName;

  /// Parse the native method-channel value.
  ///
  /// Throws [StateError] for a null, non-String or unrecognized value rather
  /// than defaulting: guessing either value would misreport whether the
  /// draft is gone.
  static DraftDiscardResult fromWire(Object? raw) {
    for (final result in values) {
      if (result.wireName == raw) return result;
    }
    throw StateError('Unrecognized draft discard result from native: "$raw"');
  }
}

/// Why an `AndroidDraftAction` write couldn't be verified (UNFY-213).
///
/// Carried by [DraftWriteFailedException]; in every case the thread's native
/// draft may differ from what the caller asked for.
enum DraftWriteFailureReason {
  /// The app does not hold the default-SMS role, so Android would silently
  /// ignore the write; nothing was attempted.
  notDefaultSmsApp('NOT_DEFAULT_SMS_APP'),

  /// The thread is not on the device, so no draft was saved: a draft must
  /// belong to an existing conversation.
  threadNotFound('THREAD_NOT_FOUND'),

  /// The provider threw or returned no row id, or the thread couldn't be
  /// read before the save (in which case nothing was written).
  draftWriteFailed('DRAFT_WRITE_FAILED'),

  /// The write ran but the read-back disagrees: after a save the thread does
  /// not hold exactly the saved draft; after a discard a draft remains.
  draftWriteIncomplete('DRAFT_WRITE_INCOMPLETE'),

  /// The write ran but the thread's drafts couldn't be read back.
  verifyFailed('VERIFY_FAILED'),

  /// The thread id is not a positive numeric provider id, the body is
  /// missing or blank, or the subscription id is not an int.
  invalidArgument('INVALID_ARGUMENT');

  const DraftWriteFailureReason(this.code);

  /// The `PlatformException.code` the native side (`DraftActions.kt`) sends
  /// for this reason; both sides' tests pin these literals.
  final String code;

  /// The reason the native side reports with [code], or null when [code] is
  /// not a draft write failure code. Callers must not default a null to a
  /// reason.
  static DraftWriteFailureReason? tryFromCode(String code) {
    for (final reason in values) {
      if (reason.code == code) return reason;
    }
    return null;
  }
}

/// Thrown by `AndroidDraftAction` when a draft write can't be verified — the
/// thread's native draft may differ from what was asked (UNFY-213).
class DraftWriteFailedException implements Exception {
  const DraftWriteFailedException(this.reason, {this.message, this.details});

  /// Why the write couldn't be verified.
  final DraftWriteFailureReason reason;

  /// The native side's description, when it sent one.
  final String? message;

  /// The native side's error details (e.g. its stack trace), when it sent any.
  final Object? details;

  @override
  String toString() =>
      message == null
          ? 'DraftWriteFailedException(${reason.name})'
          : 'DraftWriteFailedException(${reason.name}): $message';
}
