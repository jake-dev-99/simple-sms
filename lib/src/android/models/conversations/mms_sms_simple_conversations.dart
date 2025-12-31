import 'package:flutter/material.dart';
import 'package:simple_sms/src/android/models/model_helpers.dart';
import 'package:simple_sms/src/android/models/people/contactables.dart';
import 'package:simple_sms/src/android/queries/people/canonical_query.dart';
import 'package:simple_sms/src/android/queries/people/contact_contactables_query.dart';
import '../../../interfaces/models_interface.dart';
import '../../queries/messages/mms_query.dart';
import '../../queries/messages/sms_query.dart';
import '../enums/sms_mms_enums.dart';
import '../messages/mms.dart';
import '../messages/sms.dart';

class AndroidSimpleConversation implements ModelInterface {
  Future<List<String?>> get addresses async {
    try {
      final parsedAddresses = <String>[];
      if (displayRecipientIds != null && displayRecipientIds!.isNotEmpty) {
        for (final address in displayRecipientIds!) {
          if (address.isNotEmpty) {
            final parsedAddress = await CanonicalQuery(id: address).fetch();
            if (parsedAddress.isNotEmpty) {
              parsedAddresses.add(parsedAddress);
            }
          }
        }
      }
      return parsedAddresses;
    } catch (e, s) {
      debugPrint(e.toString());
      debugPrint(s.toString());
      return [];
    }
  }

  Future<List<Contactable>> get participants async {
    try {
      final addrs = <String>[];
      final parsedContactables = <Contactable>[];
      for (final address in await addresses) {
        if (address != null) {
          addrs.add(address);
        }
      }

      if (addrs.isNotEmpty) {
        if (addrs.length == 1 && addrs[0].isEmpty) {
          debugPrint('$id - $displayRecipientIds - No addresses found for $id');
          return [];
        } else {
          for (final addr in addrs) {
            final contactables =
                addr.contains('@')
                    ? await ContactableQuery.byEmail(email: addr).fetch()
                    : await ContactableQuery.byPhone(phoneNum: addr).fetch();
            if (contactables.isNotEmpty) {
              parsedContactables.add(contactables.first);
            } else {
              Contactable contactable = Contactable(value: addr);
              parsedContactables.add(contactable);
            }
          }
          return parsedContactables;
        }
      }

      Contactable contactable = Contactable(value: '');
      debugPrint('$id - $displayRecipientIds - No contactables found');
      return [contactable];
    } catch (e, s) {
      debugPrint('$id - $displayRecipientIds - $e');
      debugPrint('$id - $displayRecipientIds - $s');
      return [];
    }
  }

  Future<List<Mms>> get mmsMessages async {
    try {
      final parsedMessages = <Mms>[];
      final messages =
          await MmsQuery(
            selection: 'thread_id = ?',
            selectionArgs: [id.toString()],
          ).fetch();
      for (final message in messages) {
        parsedMessages.add(message);
      }
      return parsedMessages;
    } catch (e, s) {
      debugPrint(e.toString());
      debugPrint(s.toString());
      return [];
    }
  }

  Future<List<Sms>> get smsMessages async {
    try {
      final parsedMessages = <Sms>[];
      final messages =
          await SmsQuery(
            selection: 'thread_id = ?',
            selectionArgs: [id.toString()],
          ).fetch();
      for (final message in messages) {
        parsedMessages.add(message);
      }
      return parsedMessages;
    } catch (e, s) {
      debugPrint(e.toString());
      debugPrint(s.toString());
      return [];
    }
  }

  @override
  Map<String, dynamic>? sourceMap = {};
  @override
  int id;
  int threadId;

  /// Parent conversation identifier for nested or linked conversations
  String parentId = '';

  /// Display name for the conversation, typically contact name or phone number
  String? title = '';

  /// Indicates if conversation is archived
  bool? isArchived;

  /// Indicates if sender/conversation is blocked
  bool? isBlocked;

  /// Indicates if conversation has been deleted
  bool? isDeleted;

  /// Indicates if notifications are muted for this conversation
  bool? isMuted;

  /// Indicates if conversation is pinned to top of conversation list
  bool? isPinned;

  /// Indicates if all messages in conversation have been read
  bool? isRead;

  /// Indicates if conversation contains secure/encrypted messages
  bool? isSafeMessage;

  /// Type of chat (e.g., individual, group)
  String? chatType;

  /// Type of message (SMS, MMS, etc.)
  SmsMmsType? smsMmsType;

  /// List of phone numbers or identifiers for all participants
  List<String> recipientIds = [];

  /// Message classification type
  MessageBox? type;

  /// Current mode being used for this conversation
  UsingMode? usingMode;

  /// Label identifying the message source or platform
  String? sourceLabel;

  /// Timestamp of the most recent message (milliseconds since epoch)
  DateTime? date;

  /// Preview text from the most recent message
  String? snippet;

  /// Formatted string of recipient identifiers for display
  List<String>? displayRecipientIds;

  /// Current translation mode setting (if applicable)
  String? translateMode;

  /// Type information for the snippet text
  dynamic snippetType;

  /// Status code for the conversation in bin/trash (0 = not in bin)
  int? binStatus;

  /// Flag indicating presence of attachments (1 = has attachments)
  int? hasAttachment;

  /// Thread identifier for platform-specific threading
  int? paThread;

  /// Error code if message delivery failed
  int? error;

  /// Flag indicating if any alerts for this conversation have expired
  int? alertExpired;

  /// Character set information for the snippet
  dynamic snippetCs;

  /// Legacy archive status flag (1 = archived)
  int? archived;

  /// Count of unread messages in the conversation
  int? unreadCount;

  /// Legacy mute status flag (1 = muted)
  int? isMute;

  /// Sender's address or phone number
  String? fromAddress;

  /// Legacy read status flag (1 = read)
  int? read;

  /// Menu options string for UI context menu
  String? menustring;

  /// Legacy pin status flag (1 = pinned to top)
  int? pinToTop;

  /// Flag for default reply behavior (1 = reply to all participants)
  int? replyAll;

  /// Legacy safe message flag (1 = secure/encrypted)
  bool? safeMessage;

  /// Message classification code (spam, priority, etc.)
  int? classification;

  /// Total number of messages in the conversation
  int? messageCount;

  /// Preview text specifically for group conversations
  String? groupSnippet;

  /// Formatted date string of the most recent message
  String? messageDate;

  /// Platform-specific UUID for the conversation
  String? paUuid;

  /// Flag indicating if conversation is in secret/private mode
  int? secretMode;

  /// User's own phone number associated with this conversation
  String? paOwnnumber;

  AndroidSimpleConversation({
    required this.id,
    required this.recipientIds,
    required this.threadId,
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
    this.parentId = '',
    this.pinToTop,
    this.read,
    this.replyAll,
    this.safeMessage,
    this.secretMode,
    this.snippet,
    this.snippetCs,
    this.snippetType,
    this.sourceLabel,
    this.sourceMap,
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
    chatType: raw["chat_type"]?.toString(), // todo - convert to enum
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
