import 'package:simple_sms/src/android/models/model_helpers.dart';

import '../../../interfaces/models_interface.dart';
import '../enums/sms_mms_enums.dart';

class AndroidFullConversation implements ModelInterface {
  @override
  /// Original source data map from which this object was constructed
  Map<String, dynamic>? sourceMap;
  @override
  /// Unique identifier for the conversation
  int id;

  /// Parent conversation identifier for nested or linked conversations
  String parentId;

  /// Display name for the conversation, typically contact name or phone number
  String? title;

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
  List<String> recipientIds;

  /// Message classification type
  String? type;

  /// Current mode being used for this conversation
  String? usingMode;

  // App-internal field names:
  /// Recipient's phone number or address
  String address;

  /// Message content text
  String body;

  /// Callback number for reply or confirmation
  String callbackNumber;

  /// MMS content class classification
  int contentClass;

  /// Length of the content in bytes or characters
  String contentLength;

  /// MIME type of the message content
  String contentType;

  /// Timestamp when message was received (milliseconds since epoch)
  int date;

  /// Timestamp when message was sent (milliseconds since epoch)
  int dateSent;

  /// Flag indicating if delivery report was requested
  String deliveryReport;

  /// Number of delivery reports received
  String deliveryReportCount;

  /// Status code for delivery report
  int deliveryReportStatus;

  /// Error code if message delivery failed
  String errorCode;

  /// Message expiration timestamp
  int expiration;

  /// Flag indicating if message is marked as favorite (1 = favorite)
  int favorite;

  /// URL contained in or associated with message
  String linkUrl;

  /// Flag indicating if message is locked from deletion (1 = locked)
  int locked;

  /// Message class category (e.g., personal, advertisement)
  String messageClass;

  /// Unique identifier for the message
  String messageId;

  /// Size of the message in bytes
  int messageSize;

  /// Box/folder where message is stored (inbox=1, sent=2, draft=3, outbox=4, failed=5)
  int messageBox;

  /// Contact identifier or name associated with the message
  String person;

  /// Message priority level (0=normal, 1=high, 2=low)
  int priority;

  /// Flag indicating if message has been read (0=unread, 1=read)
  int read;

  /// Status of message read state in detailed form
  String readStatus;

  /// Flag indicating if reply path is present
  String replyPathPresent;

  /// Reserved field for future use
  int reserved;

  /// Status code for message response
  String responseStatus;

  /// Text of the response message
  String responseText;

  /// Status code for message retrieval
  String retrievalStatus;

  /// Character set of the retrieval text
  String retrievalTextCs;

  /// Flag indicating if reporting is allowed for this message
  String reportAllowed;

  /// Flag indicating if read receipt was requested (1 = requested)
  int readReceipt;

  /// Status code for read receipt
  int readReceiptStatus;

  /// Flag indicating if message is secure/encrypted (1 = safe)
  int safeMessage;

  /// Flag indicating if conversation is in secret/private mode (1 = secret)
  int secretMode;

  /// Service center address that processed the message
  String serviceCenter;

  /// SIM card IMSI that received the message
  String simImsi;

  /// SIM slot number that received the message (0 = first SIM)
  int simSlot;

  /// Flag indicating if message was reported as spam (1 = reported)
  int spamReport;

  /// Status of the message (e.g., sent, delivered, failed)
  String status;

  /// Message subject text (primarily for MMS)
  String subject;

  /// Character set of the subject text
  String subjectCharset;

  /// Network subscription ID associated with the message
  int subscriptionId;

  /// Service command code
  String serviceCommand;

  /// Content of the service command
  String serviceCommandContent;

  /// Teleservice identifier for carrier services
  String teleserviceId;

  /// Thread identifier grouping related messages
  int threadId;

  /// Flag indicating if message contains only text (1 = text only)
  int textOnly;

  /// Unique transaction identifier for message processing
  String transactionId;

  /// Protocol version number
  int version;

  AndroidFullConversation({
    this.sourceMap,
    this.id = 0,
    this.parentId = '',
    this.title = '',
    this.isArchived,
    this.isBlocked,
    this.isDeleted,
    this.isMuted,
    this.isPinned,
    this.isRead,
    this.isSafeMessage,
    this.chatType = '',
    this.smsMmsType,
    List<String>? recipientIds,
    this.type = '',
    this.usingMode = '',
    this.address = '',
    this.body = '',
    this.callbackNumber = '',
    this.contentClass = 0,
    this.contentLength = '',
    this.contentType = '',
    this.date = 0,
    this.dateSent = 0,
    this.deliveryReport = '',
    this.deliveryReportCount = '',
    this.deliveryReportStatus = 0,
    this.errorCode = '',
    this.expiration = 0,
    this.favorite = 0,
    this.linkUrl = '',
    this.locked = 0,
    this.messageClass = '',
    this.messageId = '',
    this.messageSize = 0,
    this.messageBox = 0,
    this.person = '',
    this.priority = 0,
    this.read = 0,
    this.readStatus = '',
    this.replyPathPresent = '',
    this.reserved = 0,
    this.responseStatus = '',
    this.responseText = '',
    this.retrievalStatus = '',
    this.retrievalTextCs = '',
    this.reportAllowed = '',
    this.readReceipt = 0,
    this.readReceiptStatus = 0,
    this.safeMessage = 0,
    this.secretMode = 0,
    this.serviceCenter = '',
    this.simImsi = '',
    this.simSlot = 0,
    this.spamReport = 0,
    this.status = '',
    this.subject = '',
    this.subjectCharset = '',
    this.subscriptionId = 0,
    this.serviceCommand = '',
    this.serviceCommandContent = '',
    this.teleserviceId = '',
    this.threadId = 0,
    this.textOnly = 0,
    this.transactionId = '',
    this.version = 0,
  }) : recipientIds = recipientIds ?? <String>[];

  /// --- App-style JSON, matches Dart field names, safe for REST usage ---
  factory AndroidFullConversation.fromJson(Map<String, dynamic> json) {
    return AndroidFullConversation(
      sourceMap: json,
      id: FieldHelper.asInt(json['id']) ?? 0,
      parentId: json['parentId']?.toString() ?? '',
      title: json['title']?.toString() ?? '',
      isArchived: json['isArchived'] as bool?,
      isBlocked: json['isBlocked'] as bool?,
      isDeleted: json['isDeleted'] as bool?,
      isMuted: json['isMuted'] as bool?,
      isPinned: json['isPinned'] as bool?,
      isRead: json['isRead'] as bool?,
      isSafeMessage: json['isSafeMessage'] as bool?,
      chatType: json['chatType']?.toString(),
      smsMmsType:
          json['smsMmsType'] is int
              ? SmsMmsType.values[(json['smsMmsType'] ?? 0)]
              : null,
      recipientIds:
          (json['recipientIds'] as List?)?.map((e) => e.toString()).toList() ??
          <String>[],
      type: json['type']?.toString(),
      usingMode: json['usingMode']?.toString(),
      address: json['address']?.toString() ?? '',
      body: json['body']?.toString() ?? '',
      callbackNumber: json['callbackNumber']?.toString() ?? '',
      contentClass: json['contentClass'] as int? ?? 0,
      contentLength: json['contentLength']?.toString() ?? '',
      contentType: json['contentType']?.toString() ?? '',
      date: json['date'] as int? ?? 0,
      dateSent: json['dateSent'] as int? ?? 0,
      deliveryReport: json['deliveryReport']?.toString() ?? '',
      deliveryReportCount: json['deliveryReportCount']?.toString() ?? '',
      deliveryReportStatus: json['deliveryReportStatus'] as int? ?? 0,
      errorCode: json['errorCode']?.toString() ?? '',
      expiration: json['expiration'] as int? ?? 0,
      favorite: json['favorite'] as int? ?? 0,
      linkUrl: json['linkUrl']?.toString() ?? '',
      locked: json['locked'] as int? ?? 0,
      messageClass: json['messageClass']?.toString() ?? '',
      messageId: json['messageId']?.toString() ?? '',
      messageSize: json['messageSize'] as int? ?? 0,
      messageBox: json['messageBox'] as int? ?? 0,
      person: json['person']?.toString() ?? '',
      priority: json['priority'] as int? ?? 0,
      read: json['read'] as int? ?? 0,
      readStatus: json['readStatus']?.toString() ?? '',
      replyPathPresent: json['replyPathPresent']?.toString() ?? '',
      reserved: json['reserved'] as int? ?? 0,
      responseStatus: json['responseStatus']?.toString() ?? '',
      responseText: json['responseText']?.toString() ?? '',
      retrievalStatus: json['retrievalStatus']?.toString() ?? '',
      retrievalTextCs: json['retrievalTextCs']?.toString() ?? '',
      reportAllowed: json['reportAllowed']?.toString() ?? '',
      readReceipt: json['readReceipt'] as int? ?? 0,
      readReceiptStatus: json['readReceiptStatus'] as int? ?? 0,
      safeMessage: json['safeMessage'] as int? ?? 0,
      secretMode: json['secretMode'] as int? ?? 0,
      serviceCenter: json['serviceCenter']?.toString() ?? '',
      simImsi: json['simImsi']?.toString() ?? '',
      simSlot: json['simSlot'] as int? ?? 0,
      spamReport: json['spamReport'] as int? ?? 0,
      status: json['status']?.toString() ?? '',
      subject: json['subject']?.toString() ?? '',
      subjectCharset: json['subjectCharset']?.toString() ?? '',
      subscriptionId: json['subscriptionId'] as int? ?? 0,
      serviceCommand: json['serviceCommand']?.toString() ?? '',
      serviceCommandContent: json['serviceCommandContent']?.toString() ?? '',
      teleserviceId: json['teleserviceId']?.toString() ?? '',
      threadId: json['threadId'] as int? ?? 0,
      textOnly: json['textOnly'] as int? ?? 0,
      transactionId: json['transactionId']?.toString() ?? '',
      version: json['version'] as int? ?? 0,
    );
  }

  Map<String, dynamic> toJson() => {
    'id': id,
    'parentId': parentId,
    'title': title,
    'isArchived': isArchived,
    'isBlocked': isBlocked,
    'isDeleted': isDeleted,
    'isMuted': isMuted,
    'isPinned': isPinned,
    'isRead': isRead,
    'isSafeMessage': isSafeMessage,
    'chatType': chatType,
    'smsMmsType': smsMmsType?.index,
    'recipientIds': recipientIds,
    'type': type,
    'usingMode': usingMode,
    'address': address,
    'body': body,
    'callbackNumber': callbackNumber,
    'contentClass': contentClass,
    'contentLength': contentLength,
    'contentType': contentType,
    'date': date,
    'dateSent': dateSent,
    'deliveryReport': deliveryReport,
    'deliveryReportCount': deliveryReportCount,
    'deliveryReportStatus': deliveryReportStatus,
    'errorCode': errorCode,
    'expiration': expiration,
    'favorite': favorite,
    'linkUrl': linkUrl,
    'locked': locked,
    'messageClass': messageClass,
    'messageId': messageId,
    'messageSize': messageSize,
    'messageBox': messageBox,
    'person': person,
    'priority': priority,
    'read': read,
    'readStatus': readStatus,
    'replyPathPresent': replyPathPresent,
    'reserved': reserved,
    'responseStatus': responseStatus,
    'responseText': responseText,
    'retrievalStatus': retrievalStatus,
    'retrievalTextCs': retrievalTextCs,
    'reportAllowed': reportAllowed,
    'readReceipt': readReceipt,
    'readReceiptStatus': readReceiptStatus,
    'safeMessage': safeMessage,
    'secretMode': secretMode,
    'serviceCenter': serviceCenter,
    'simImsi': simImsi,
    'simSlot': simSlot,
    'spamReport': spamReport,
    'status': status,
    'subject': subject,
    'subjectCharset': subjectCharset,
    'subscriptionId': subscriptionId,
    'serviceCommand': serviceCommand,
    'serviceCommandContent': serviceCommandContent,
    'teleserviceId': teleserviceId,
    'threadId': threadId,
    'textOnly': textOnly,
    'transactionId': transactionId,
    'version': version,
  };

  /// --- Raw Android/DB mapping ---
  /// Converts from Android "raw" field names and DB values
  factory AndroidFullConversation.fromRaw(Map<String, dynamic> raw) {
    return AndroidFullConversation(
      sourceMap: raw,
      id: FieldHelper.asInt(raw['_id']) ?? FieldHelper.asInt(raw['id'])!,
      address: raw['address']?.toString() ?? '',
      body: raw['body']?.toString() ?? '',
      callbackNumber: raw['callback_number']?.toString() ?? '',
      contentClass: raw['ct_cls'] as int? ?? 0,
      contentLength: raw['ct_l']?.toString() ?? '',
      contentType: raw['ct_t']?.toString() ?? '',
      deliveryReport: raw['d_rpt']?.toString() ?? '',
      deliveryReportCount: raw['d_rpt_cnt']?.toString() ?? '',
      deliveryReportStatus: raw['d_rpt_st'] as int? ?? 0,
      date: raw['date'] as int? ?? 0,
      dateSent: raw['date_sent'] as int? ?? 0,
      errorCode: raw['error_code']?.toString() ?? '',
      expiration: raw['exp'] as int? ?? 0,
      favorite: raw['favorite'] as int? ?? 0,
      linkUrl: raw['link_url']?.toString() ?? '',
      locked: raw['locked'] as int? ?? 0,
      messageClass: raw['m_cls']?.toString() ?? '',
      messageId: raw['m_id']?.toString() ?? '',
      messageSize: raw['m_size'] as int? ?? 0,
      messageBox: raw['msg_box'] as int? ?? 0,
      person: raw['person']?.toString() ?? '',
      priority: raw['pri'] as int? ?? 0,
      read: raw['read'] as int? ?? 0,
      readStatus: raw['read_status']?.toString() ?? '',
      replyPathPresent: raw['reply_path_present']?.toString() ?? '',
      reserved: raw['reserved'] as int? ?? 0,
      responseStatus: raw['resp_st']?.toString() ?? '',
      responseText: raw['resp_txt']?.toString() ?? '',
      retrievalStatus: raw['retr_st']?.toString() ?? '',
      retrievalTextCs: raw['retr_txt_cs']?.toString() ?? '',
      reportAllowed: raw['rpt_a']?.toString() ?? '',
      readReceipt: raw['rr'] as int? ?? 0,
      readReceiptStatus: raw['rr_st'] as int? ?? 0,
      safeMessage: raw['safe_message'] as int? ?? 0,
      secretMode: raw['secret_mode'] as int? ?? 0,
      serviceCenter: raw['service_center']?.toString() ?? '',
      simImsi: raw['sim_imsi']?.toString() ?? '',
      simSlot: raw['sim_slot'] as int? ?? 0,
      spamReport: raw['spam_report'] as int? ?? 0,
      status: raw['st']?.toString() ?? '',
      subject: raw['sub']?.toString() ?? '',
      subjectCharset: raw['sub_cs']?.toString() ?? '',
      subscriptionId: raw['sub_id'] as int? ?? 0,
      serviceCommand: raw['svc_cmd']?.toString() ?? '',
      serviceCommandContent: raw['svc_cmd_content']?.toString() ?? '',
      teleserviceId: raw['teleservice_id']?.toString() ?? '',
      threadId: raw['thread_id'] as int? ?? 0,
      textOnly: raw['text_only'] as int? ?? 0,
      transactionId: raw['tr_id']?.toString() ?? '',
      version: raw['v'] as int? ?? 0,
      // Any app-internal fields that can't be mapped from raw, default as above.
    );
  }

  /// Maps this Dart object to an Android/DB/wire/raw map, using raw field names.
  Map<String, dynamic> toRaw() => {
    '_id': id,
    'address': address,
    'body': body,
    'callback_number': callbackNumber,
    'ct_cls': contentClass,
    'ct_l': contentLength,
    'ct_t': contentType,
    'd_rpt': deliveryReport,
    'd_rpt_cnt': deliveryReportCount,
    'd_rpt_st': deliveryReportStatus,
    'date': date,
    'date_sent': dateSent,
    'error_code': errorCode,
    'exp': expiration,
    'favorite': favorite,
    'link_url': linkUrl,
    'locked': locked,
    'm_cls': messageClass,
    'm_id': messageId,
    'm_size': messageSize,
    'msg_box': messageBox,
    'person': person,
    'pri': priority,
    'read': read,
    'read_status': readStatus,
    'reply_path_present': replyPathPresent,
    'reserved': reserved,
    'resp_st': responseStatus,
    'resp_txt': responseText,
    'retr_st': retrievalStatus,
    'retr_txt_cs': retrievalTextCs,
    'rpt_a': reportAllowed,
    'rr': readReceipt,
    'rr_st': readReceiptStatus,
    'safe_message': safeMessage,
    'secret_mode': secretMode,
    'service_center': serviceCenter,
    'sim_imsi': simImsi,
    'sim_slot': simSlot,
    'spam_report': spamReport,
    'st': status,
    'sub': subject,
    'sub_cs': subjectCharset,
    'sub_id': subscriptionId,
    'svc_cmd': serviceCommand,
    'svc_cmd_content': serviceCommandContent,
    'teleservice_id': teleserviceId,
    'thread_id': threadId,
    'text_only': textOnly,
    'tr_id': transactionId,
    'v': version,
  };
}
