import '../../interop/channels.dart';
import '../models/enums/draft_result.dart';
import 'verified_write.dart';

/// Native draft writes (ADR-0016): the device's SMS store owns a thread's
/// draft, and these methods save or discard it. A thread holds one draft
/// across both tables: an SMS text draft (its single `MESSAGE_TYPE_DRAFT` row)
/// or an MMS draft with attachments (its single `msg_box` 3 row). Saving
/// either removes the thread's drafts of the other kind.
///
/// Every write requires the default-SMS-app role,
/// obtained through `simple_permissions_native`; these methods never request
/// it. Every write is verified (UNFY-213): the native side reads the thread's
/// drafts back and resolves only when they match the request. Otherwise it
/// throws a [DraftWriteFailedException] carrying the
/// [DraftWriteFailureReason]; an unrecognized native result or error code
/// throws [StateError].
class AndroidDraftAction {
  /// Saves [body] as the draft of the existing thread [threadId] and resolves
  /// to the draft's native SMS `_id`.
  ///
  /// Updates the thread's SMS draft in place, so its `_id` is stable across
  /// saves; a thread with no draft gets a new one. Extra draft rows another
  /// app left in the thread, and its MMS drafts, are deleted, so the thread
  /// ends with exactly one draft.
  ///
  /// [body] must not be blank — use [discardDraft] to clear a draft. A
  /// thread that is not on the device fails with
  /// [DraftWriteFailureReason.threadNotFound].
  ///
  /// [subscriptionId] is the SIM the conversation uses — the `subscriptionId`
  /// of a `SimCard` from `simple_telephony_native`, as for
  /// `OutboundMessage.subscriptionId`. When given, the draft is stored on it.
  /// When null, a new draft gets the system default SMS SIM and an existing
  /// draft keeps its own.
  static Future<String> saveSmsDraft({
    required String threadId,
    required String body,
    int? subscriptionId,
  }) async {
    return _draftId(
      await _verified(
        () => DraftsInterop.saveSmsDraft(threadId, body, subscriptionId),
      ),
    );
  }

  /// Saves [attachmentPaths] and [body] as the MMS draft of the existing
  /// thread [threadId], addressed to [addresses], and resolves to the draft's
  /// native MMS `_id`.
  ///
  /// The thread ends with exactly this draft: its earlier MMS drafts and its
  /// SMS drafts are deleted. The draft is marked read and seen, so it does not
  /// count toward the thread's unread state. Each attachment keeps its file's
  /// raw bytes, typed by the file extension (`application/octet-stream` when
  /// unknown), and is never recompressed. A non-blank [body] is saved as a
  /// `text/plain` part after the attachments.
  ///
  /// [attachmentPaths] must name 1 to 8 readable files. [addresses] are the
  /// thread's recipients, which must resolve to [threadId]; a draft that
  /// lands in another thread is removed and the save fails with
  /// [DraftWriteFailureReason.draftWriteIncomplete]. A file that can't be read
  /// fails with [DraftWriteFailureReason.draftWriteFailed] before anything is
  /// written. A thread that is not on the device fails with
  /// [DraftWriteFailureReason.threadNotFound].
  ///
  /// [subscriptionId] is the SIM the conversation uses, as for
  /// [saveSmsDraft]. When null, the draft gets the system default SMS SIM.
  static Future<String> saveMmsDraft({
    required String threadId,
    required Set<String> addresses,
    String? body,
    required List<String> attachmentPaths,
    int? subscriptionId,
  }) async {
    return _draftId(
      await _verified(
        () => DraftsInterop.saveMmsDraft(
          threadId,
          addresses.toList(growable: false),
          body,
          attachmentPaths,
          subscriptionId,
        ),
      ),
    );
  }

  /// Deletes the draft of thread [threadId], SMS and MMS.
  ///
  /// Resolves to [DraftDiscardResult.alreadyAbsent] when the thread has no
  /// draft, including a thread that is no longer on the device.
  ///
  /// The device then removes the thread itself if the draft was its only
  /// message, as for any native delete: a later [saveSmsDraft] or
  /// [saveMmsDraft] for that thread fails with
  /// [DraftWriteFailureReason.threadNotFound].
  static Future<DraftDiscardResult> discardDraft(String threadId) async =>
      DraftDiscardResult.fromWire(
        await _verified(() => DraftsInterop.discardDraft(threadId)),
      );

  /// The draft's native `_id` in a save [reply], or [StateError] for any other
  /// value rather than a guessed id.
  static String _draftId(Object? reply) {
    if (reply is String && (int.tryParse(reply) ?? 0) > 0) return reply;
    throw StateError('Unrecognized draft save result from native: "$reply"');
  }

  /// Run a native draft write and translate its errors into the typed
  /// contract.
  static Future<Object?> _verified(Future<Object?> Function() invoke) =>
      invokeVerifiedWrite(
        operation: 'draft write',
        invoke: invoke,
        reasonFor: DraftWriteFailureReason.tryFromCode,
        failure:
            (reason, e) => DraftWriteFailedException(
              reason,
              message: e.message,
              details: e.details,
            ),
      );
}
