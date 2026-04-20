import 'package:simple_sms_native/src/android/models/model_helpers.dart';
import '../../../interfaces/models_interface.dart';
import '../enums/sms_mms_enums.dart';
import '../messages/mms.dart';
import '../messages/sms.dart';
import '../people/contactables.dart';

/// A simplified conversation model representing an SMS/MMS thread.
///
/// This is a pure data object. To resolve addresses, participants, or
/// messages for this conversation, use [LookupService]:
///
/// ```dart
/// final service = LookupService();
/// final smsList = await service.getSmsByThread(conversation.id);
/// final mmsList = await service.getMmsByThread(conversation.id);
/// ```
class AndroidSimpleConversation implements ModelInterface {
  @override
  final Map<String, dynamic>? sourceMap;
  @override
  final int id;
  final int threadId;
  final String parentId;
  final String? title;
  final bool? isArchived;
  final bool? isBlocked;
  final bool? isDeleted;
  final bool? isMuted;
  final bool? isPinned;
  final bool? isRead;
  final bool? isSafeMessage;
  /// Thread-level chat-type flag the Samsung provider surfaces on
  /// `simple=true` rows (`chat_type` column). Values are provider-defined
  /// and carrier-specific, so this is exposed as a raw `int?` rather
  /// than a closed enum; consumers that need to branch on it should
  /// compare against the real device values they observe.
  final int? chatType;
  final SmsMmsType? smsMmsType;
  final List<String> recipientIds;
  final MessageBox? type;
  final UsingMode? usingMode;
  final String? sourceLabel;
  final DateTime? date;
  final String? snippet;
  final List<String>? displayRecipientIds;
  final String? translateMode;
  final dynamic snippetType;
  final int? binStatus;
  final int? hasAttachment;
  final int? paThread;
  final int? error;
  final int? alertExpired;
  final dynamic snippetCs;
  final int? archived;
  final int? unreadCount;
  final int? isMute;
  final String? fromAddress;
  final int? read;
  final String? menustring;
  final int? pinToTop;
  final int? replyAll;
  final bool? safeMessage;
  final int? classification;
  final int? messageCount;
  final String? groupSnippet;
  final String? messageDate;
  final String? paUuid;
  final int? secretMode;
  final String? paOwnnumber;

  // --- Enrichment (populated by LookupService.listConversations) -----------
  //
  // These fields are `null` when the model is built via [fromRaw] alone.
  // [LookupService.listConversations] resolves them by issuing follow-up
  // queries (canonical-address + contactable lookup, latest SMS/MMS).

  /// Contactables (lightweight contact rows) resolved from [recipientIds].
  /// Null when not enriched.
  final List<Contactable>? participants;

  /// Most recent SMS in the thread, if any. Null when not enriched or when
  /// the thread has no SMS.
  final Sms? latestSms;

  /// Most recent MMS in the thread, if any. Null when not enriched or when
  /// the thread has no MMS.
  final Mms? latestMms;

  AndroidSimpleConversation({
    required this.id,
    this.recipientIds = const [],
    required this.threadId,
    this.parentId = '',
    this.sourceMap,
    this.alertExpired,
    this.archived,
    this.binStatus,
    this.chatType,
    this.classification,
    this.date,
    this.displayRecipientIds,
    this.error,
    this.fromAddress,
    this.groupSnippet,
    this.hasAttachment,
    this.isArchived,
    this.isBlocked,
    this.isDeleted,
    this.isMute,
    this.isMuted,
    this.isPinned,
    this.isRead,
    this.isSafeMessage,
    this.menustring,
    this.messageCount,
    this.messageDate,
    this.smsMmsType,
    this.paOwnnumber,
    this.paThread,
    this.paUuid,
    this.pinToTop,
    this.read,
    this.replyAll,
    this.safeMessage,
    this.secretMode,
    this.snippet,
    this.snippetCs,
    this.snippetType,
    this.sourceLabel,
    this.title,
    this.translateMode,
    this.type,
    this.unreadCount,
    this.usingMode,
    this.participants,
    this.latestSms,
    this.latestMms,
  });

  /// Returns a new instance with the supplied enrichment fields populated,
  /// preserving all other fields from [this].
  ///
  /// Intended for internal use by [LookupService.listConversations]; app code
  /// should treat `AndroidSimpleConversation` as immutable and request the
  /// enriched shape from the list API rather than constructing it manually.
  AndroidSimpleConversation enrich({
    List<Contactable>? participants,
    Sms? latestSms,
    Mms? latestMms,
  }) {
    return AndroidSimpleConversation(
      id: id,
      recipientIds: recipientIds,
      threadId: threadId,
      parentId: parentId,
      sourceMap: sourceMap,
      alertExpired: alertExpired,
      archived: archived,
      binStatus: binStatus,
      chatType: chatType,
      classification: classification,
      date: date,
      displayRecipientIds: displayRecipientIds,
      error: error,
      fromAddress: fromAddress,
      groupSnippet: groupSnippet,
      hasAttachment: hasAttachment,
      isArchived: isArchived,
      isBlocked: isBlocked,
      isDeleted: isDeleted,
      isMute: isMute,
      isMuted: isMuted,
      isPinned: isPinned,
      isRead: isRead,
      isSafeMessage: isSafeMessage,
      menustring: menustring,
      messageCount: messageCount,
      messageDate: messageDate,
      smsMmsType: smsMmsType,
      paOwnnumber: paOwnnumber,
      paThread: paThread,
      paUuid: paUuid,
      pinToTop: pinToTop,
      read: read,
      replyAll: replyAll,
      safeMessage: safeMessage,
      secretMode: secretMode,
      snippet: snippet,
      snippetCs: snippetCs,
      snippetType: snippetType,
      sourceLabel: sourceLabel,
      title: title,
      translateMode: translateMode,
      type: type,
      unreadCount: unreadCount,
      usingMode: usingMode,
      participants: participants ?? this.participants,
      latestSms: latestSms ?? this.latestSms,
      latestMms: latestMms ?? this.latestMms,
    );
  }

  factory AndroidSimpleConversation.fromRaw(
    Map<String, dynamic> raw,
  ) => AndroidSimpleConversation(
    // `_id` on a `content://mms-sms/conversations?simple=true` row is
    // the most-recent MESSAGE id for the thread the row represents —
    // NOT the thread id. The actual thread primary key is the
    // `thread_id` column. Earlier revisions of this parser mapped
    // `threadId = raw['_id']`, which meant every downstream
    // Conversation model's `threadId` was actually a message id.
    // Consumers that join messages on `thread_id` (simple-messages
    // does this to attach SMS/MMS to conversations) had their joins
    // silently fail — the conversation's stored id was a message id,
    // the messages' stored parent id was a thread id, and they never
    // matched. The fallback to `_id` stays only for the degenerate
    // case where a provider row legitimately lacks `thread_id` (not
    // expected on real Android, but safer than a null).
    id: FieldHelper.asInt(raw['_id']) ?? FieldHelper.asInt(raw['id']) ?? 0,
    threadId: FieldHelper.asInt(raw['thread_id']) ??
        FieldHelper.asInt(raw['_id']) ??
        FieldHelper.asInt(raw['id']) ??
        0,
    // Every raw field goes through FieldHelper coercion: Samsung's
    // mms-sms/conversations?simple=true returns Strings for several int
    // columns (e.g. "archived", "has_attachment", "read") and a single
    // stringified-int for `recipient_ids`. A direct `raw[x]` assignment
    // to a typed nullable slot would throw TypeError and sink the whole
    // page inside LookupService's outer try/catch.
    alertExpired: FieldHelper.asInt(raw["alert_expired"]),
    archived: FieldHelper.asInt(raw["archived"]),
    binStatus: FieldHelper.asInt(raw["bin_status"]),
    chatType: FieldHelper.asInt(raw["chat_type"]),
    classification: FieldHelper.asInt(raw["classification"]),
    date: FieldHelper.asDateTime(raw["date"]),
    displayRecipientIds: _splitIds(raw["display_recipient_ids"]),
    error: FieldHelper.asInt(raw["error"]),
    fromAddress: raw["from_address"]?.toString(),
    groupSnippet: raw["group_snippet"]?.toString(),
    hasAttachment: FieldHelper.asInt(raw["has_attachment"]),
    isMute: FieldHelper.asInt(raw["is_mute"]),
    menustring: raw["menustring"]?.toString(),
    messageCount: FieldHelper.asInt(raw["message_count"]),
    messageDate: raw["message_date"]?.toString(),
    smsMmsType: FieldHelper.enumFromValue(SmsMmsType.values, raw["smsMmsType"]),
    paOwnnumber: raw["pa_ownnumber"]?.toString(),
    paThread: FieldHelper.asInt(raw["pa_thread"]),
    paUuid: raw["pa_uuid"]?.toString(),
    pinToTop: FieldHelper.asInt(raw["pin_to_top"]),
    read: FieldHelper.asInt(raw["read"]),
    recipientIds: _splitIds(raw["recipient_ids"]),
    replyAll: FieldHelper.asInt(raw["reply_all"]),
    safeMessage: FieldHelper.asBool(raw["safe_message"]),
    secretMode: FieldHelper.asInt(raw["secret_mode"]),
    snippet: raw["snippet"]?.toString(),
    snippetCs: FieldHelper.asInt(raw["snippet_cs"]),
    snippetType: FieldHelper.asInt(raw["snippet_type"]),
    sourceLabel: raw["sourceLabel"]?.toString(),
    translateMode: raw["translate_mode"]?.toString(),
    type: FieldHelper.enumFromValue(MessageBox.values, raw["type"]),
    unreadCount: FieldHelper.asInt(raw["unread_count"]),
    usingMode: FieldHelper.enumFromValue(UsingMode.values, raw["using_mode"]),
  );

  /// Parses a space-separated id column (e.g. `recipient_ids` = "1 2 3") into
  /// a list of non-empty id strings, tolerating ints or null.
  static List<String> _splitIds(dynamic raw) {
    if (raw == null) return const <String>[];
    final s = raw.toString().trim();
    if (s.isEmpty) return const <String>[];
    return s.split(' ').where((p) => p.isNotEmpty).toList(growable: false);
  }

  Map<String, dynamic> toRaw() => {
    // Platform-channel payload: enums emit as their int `.value`,
    // DateTime emits as millis-since-epoch (matching how the provider
    // surfaces `date`), and id-list columns emit as space-separated
    // Strings — symmetric with `_splitIds` in fromRaw.
    "_id": id,
    "thread_id": threadId,
    "sourceLabel": sourceLabel,
    "date": date?.millisecondsSinceEpoch,
    "snippet": snippet,
    "display_recipient_ids": displayRecipientIds?.join(' '),
    "translate_mode": translateMode,
    "snippet_type": snippetType,
    "bin_status": binStatus,
    "has_attachment": hasAttachment,
    "pa_thread": paThread,
    "type": type?.value,
    "error": error,
    "alert_expired": alertExpired,
    "snippet_cs": snippetCs,
    "chat_type": chatType,
    "archived": archived,
    "unread_count": unreadCount,
    "is_mute": isMute,
    "from_address": fromAddress,
    "read": read,
    "menustring": menustring,
    "pin_to_top": pinToTop,
    "reply_all": replyAll,
    "safe_message": FieldHelper.boolToInt(safeMessage),
    "smsMmsType": smsMmsType?.index,
    "classification": classification,
    "message_count": messageCount,
    "group_snippet": groupSnippet,
    "using_mode": usingMode?.value,
    "message_date": messageDate,
    "recipient_ids": recipientIds.join(' '),
    "pa_uuid": paUuid,
    "secret_mode": secretMode,
    "pa_ownnumber": paOwnnumber,
  };
}
