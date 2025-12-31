// DEPRECATED: This model contains Samsung-specific conversation fields and is not used.
// The plugin uses AndroidSimpleConversation (mms_sms_simple_conversations.dart) instead.
// This file can be safely removed in a future major version.

// To parse this JSON data, do
//
//     final SimpleConversation = SimpleConversationFromJson(jsonString);

import 'dart:convert';

List<SimpleConversation> simpleConversationFromJson(String str) =>
    List<SimpleConversation>.from(
      json.decode(str).map((x) => SimpleConversation.fromJson(x)),
    );

String simpleConversationToJson(List<SimpleConversation> data) =>
    json.encode(List<dynamic>.from(data.map((x) => x.toJson())));

class SimpleConversation {
  String? sourceLabel;
  int? date;
  String? snippet;
  String? displayRecipientIds;
  TranslateMode? translateMode;
  dynamic snippetType;
  int? binStatus;
  int? hasAttachment;
  int? paThread;
  int? type;
  int? error;
  int? alertExpired;
  dynamic snippetCs;
  int? chatType;
  int? archived;
  int? unreadCount;
  int? isMute;
  String? fromAddress;
  int? read;
  String? menustring;
  int? pinToTop;
  int? replyAll;
  int? safeMessage;
  dynamic messageType;
  int? classification;
  int? messageCount;
  String? groupSnippet;
  int? usingMode;
  String? messageDate;
  String? recipientIds;
  String? paUuid;
  int? secretMode;
  int? id;
  String? paOwnnumber;

  SimpleConversation({
    this.sourceLabel,
    this.date,
    this.snippet,
    this.displayRecipientIds,
    this.translateMode,
    this.snippetType,
    this.binStatus,
    this.hasAttachment,
    this.paThread,
    this.type,
    this.error,
    this.alertExpired,
    this.snippetCs,
    this.chatType,
    this.archived,
    this.unreadCount,
    this.isMute,
    this.fromAddress,
    this.read,
    this.menustring,
    this.pinToTop,
    this.replyAll,
    this.safeMessage,
    this.messageType,
    this.classification,
    this.messageCount,
    this.groupSnippet,
    this.usingMode,
    this.messageDate,
    this.recipientIds,
    this.paUuid,
    this.secretMode,
    this.id,
    this.paOwnnumber,
  });

  factory SimpleConversation.fromJson(Map<String, dynamic> json) =>
      SimpleConversation(
        sourceLabel: json["sourceLabel"],
        date: json["date"],
        snippet: json["snippet"],
        displayRecipientIds: json["display_recipient_ids"],
        translateMode: translateModeValues.map[json["translate_mode"]]!,
        snippetType: json["snippet_type"],
        binStatus: json["bin_status"],
        hasAttachment: json["has_attachment"],
        paThread: json["pa_thread"],
        type: json["type"],
        error: json["error"],
        alertExpired: json["alert_expired"],
        snippetCs: json["snippet_cs"],
        chatType: json["chat_type"],
        archived: json["archived"],
        unreadCount: json["unread_count"],
        isMute: json["is_mute"],
        fromAddress: json["from_address"],
        read: json["read"],
        menustring: json["menustring"],
        pinToTop: json["pin_to_top"],
        replyAll: json["reply_all"],
        safeMessage: json["safe_message"],
        messageType: json["message_type"],
        classification: json["classification"],
        messageCount: json["message_count"],
        groupSnippet: json["group_snippet"],
        usingMode: json["using_mode"],
        messageDate: json["message_date"],
        recipientIds: json["recipient_ids"],
        paUuid: json["pa_uuid"],
        secretMode: json["secret_mode"],
        id: json["_id"],
        paOwnnumber: json["pa_ownnumber"],
      );

  Map<String, dynamic> toJson() => {
    "sourceLabel": sourceLabel,
    "date": date,
    "snippet": snippet,
    "display_recipient_ids": displayRecipientIds,
    "translate_mode": translateModeValues.reverse[translateMode],
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
    "message_type": messageType,
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

enum TranslateMode { off }

final translateModeValues = EnumValues({"off": TranslateMode.off});

class EnumValues<T> {
  Map<String, T> map;
  late Map<T, String> reverseMap;

  EnumValues(this.map);

  Map<T, String> get reverse {
    reverseMap = map.map((k, v) => MapEntry(v, k));
    return reverseMap;
  }
}
