/// Verified outcome of `AndroidDraftAction.discardDraft` (UNFY-213).
///
/// Both values mean the thread holds no native draft, SMS or MMS. A discard that could
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

  /// The provider threw or returned no row id, the thread couldn't be read
  /// before the save, or an attachment file of an MMS save couldn't be read (in
  /// which case nothing was written). It is also what an MMS save reports when
  /// it failed after inserting its draft and that draft could not be removed:
  /// the thread may then hold an unverified draft.
  draftWriteFailed('DRAFT_WRITE_FAILED'),

  /// The write ran but the read-back disagrees: after a save the thread does
  /// not hold exactly the saved draft. An MMS save also needs the draft's
  /// attachments (with their type and content) and body as parts, its read and
  /// seen flags, the named SIM and the recipients to be as saved, and the
  /// recipients to resolve to the thread; a text/plain or text/html attachment
  /// is kept as text, so one that is not UTF-8 or exceeds 300 KiB fails. After a
  /// discard a draft remains. After an MMS save the draft that was inserted has
  /// been removed again, and the thread's earlier drafts are as they were when
  /// its content was what failed.
  draftWriteIncomplete('DRAFT_WRITE_INCOMPLETE'),

  /// The write ran but the thread's drafts couldn't be read back. After an MMS
  /// save the draft that was inserted has been removed again.
  verifyFailed('VERIFY_FAILED'),

  /// The thread id is not a positive numeric provider id, the body of an SMS
  /// save is missing or blank, an MMS save lacks addresses or has no
  /// attachment or more than eight, an attachment is over 16 MiB or the
  /// attachments are over 32 MiB together, or the subscription id is not an
  /// int.
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
