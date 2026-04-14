import 'package:simple_sms/src/android/models/model_helpers.dart';
import '../../../interfaces/models_interface.dart';
import '../enums/conversation_enums.dart';
import '../enums/sms_mms_enums.dart';

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
  final ChatType? chatType;
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
  });

  factory AndroidSimpleConversation.fromRaw(
    Map<String, dynamic> raw,
  ) => AndroidSimpleConversation(
    id: FieldHelper.asInt(raw['_id']) ?? FieldHelper.asInt(raw['id'])!,
    threadId: FieldHelper.asInt(raw['_id']) ?? FieldHelper.asInt(raw['id'])!,
    alertExpired: raw["alert_expired"],
    archived: raw["archived"],
    binStatus: raw["bin_status"],
    chatType: FieldHelper.enumFromValue(ChatType.values, raw["chat_type"]),
    classification: raw["classification"],
    date: FieldHelper.asDateTime(raw["date"]),
    displayRecipientIds: raw["display_recipient_ids"]?.split(' ') ?? <String>[],
    error: raw["error"],
    fromAddress: raw["from_address"],
    groupSnippet: raw["group_snippet"],
    hasAttachment: raw["has_attachment"],
    isMute: raw["is_mute"],
    menustring: raw["menustring"],
    messageCount: raw["message_count"],
    messageDate: raw["message_date"],
    smsMmsType: FieldHelper.enumFromValue(SmsMmsType.values, raw["smsMmsType"]),
    paOwnnumber: raw["pa_ownnumber"],
    paThread: raw["pa_thread"],
    paUuid: raw["pa_uuid"],
    pinToTop: raw["pin_to_top"],
    read: raw["read"],
    recipientIds: raw["recipient_ids"]?.split(' ') ?? <String>[],
    replyAll: raw["reply_all"],
    safeMessage: FieldHelper.asBool(raw["safe_message"]),
    secretMode: raw["secret_mode"],
    snippet: raw["snippet"],
    snippetCs: raw["snippet_cs"],
    snippetType: raw["snippet_type"],
    sourceLabel: raw["sourceLabel"],
    translateMode: raw["translate_mode"],
    type: FieldHelper.enumFromValue(MessageBox.values, raw["type"]),
    unreadCount: raw["unread_count"],
    usingMode: FieldHelper.enumFromValue(UsingMode.values, raw["using_mode"]),
  );

  Map<String, dynamic> toRaw() => {
    "sourceLabel": sourceLabel,
    "date": date,
    "snippet": snippet,
    "display_recipient_ids": displayRecipientIds,
    "translate_mode": translateMode,
    "snippet_type": snippetType,
    "bin_status": binStatus,
    "has_attachment": hasAttachment,
    "pa_thread": paThread,
    "type": type,
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
    "safe_message": safeMessage,
    "smsMmsType": smsMmsType,
    "classification": classification,
    "message_count": messageCount,
    "group_snippet": groupSnippet,
    "using_mode": usingMode,
    "message_date": messageDate,
    "recipient_ids": recipientIds,
    "pa_uuid": paUuid,
    "secret_mode": secretMode,
    "_id": id,
    "pa_ownnumber": paOwnnumber,
  };
}
