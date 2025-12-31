import '../../../interfaces/models_interface.dart';
import '../enums/contact_enums.dart';
import '../enums/sms_mms_enums.dart';
import '../model_helpers.dart';
import 'mms_part.dart';
import '../people/mms_participant.dart';

/// Represents an MMS message in the Android system
///
/// This class maps to MMS messages in Android's messaging database
/// and provides methods to convert between different data formats.
class Mms implements ModelInterface {
  /// Returns the body text of the MMS by combining all text parts
  ///
  /// Extracts text content from MMS parts, joining them with newlines
  /// and cleaning up whitespace.

  // Core fields (naming unified where overlap exists with Sms)
  @override
  final int id;

  @override
  final Map<String, dynamic>? sourceMap;

  String parentId = '';

  /// The individual parts/attachments of the MMS
  final List<MmsPart>? parts;

  final String body;

  /// Message recipients
  final List<MmsParticipant>? recipients;
  final MmsParticipant? sender;

  // Common message fields
  /// Address of the sender/recipient
  final String? address;

  /// Conversation thread ID
  final int threadId;

  /// Message type classification
  final MmsMessageType? type;

  /// Delivery/processing status
  final AndroidMessageStatus? status;

  /// Message priority level
  final MessagePriority? priority;

  /// SIM subscription ID
  final int? subscriptionId;

  /// Message subject
  final String? subject;

  /// Whether message has been read
  final bool read;

  /// Whether message has been seen
  final bool? seen;

  /// Whether message can be deleted
  final bool? deletable;

  /// Whether message is locked
  final bool? locked;

  /// Whether message is marked as favorite
  final bool? favorite;

  /// Whether message is hidden
  final bool? hidden;

  /// Whether message is reported as spam
  final bool? spamReport;

  /// Date the message was received
  final DateTime? date;

  /// Date the message was sent
  final DateTime? dateSent;

  /// Sender's address
  final String? fromAddress;

  /// Creator of the message
  final String? creator;

  /// Service center that processed the message
  final String? serviceCenter;

  /// SIM slot used for the message
  final int simSlot;

  /// SIM IMSI identifier
  final String? simImsi;

  /// Character set for the address
  final String? addressCharset;

  // MMS-specific fields
  /// Application ID
  final int? appId;

  /// Binary information
  final BinaryInfo? binaryInfo;

  /// CMC property
  final String? cmcProp;

  /// Content classification
  final int? contentClass;

  /// Content location
  final String? contentLocation;

  /// MIME content type
  final String? contentType;

  /// Correlation tag
  final String? correlationTag;

  /// Date of delivery
  final DateTime? deliveryDate;

  /// Delivery report status
  final DeliveryReport? deliveryReport;

  /// Status of delivery report
  final DeliveryReportStatus? deliveryReportStatus;

  /// Expiry date
  final DateTime? expiryDate;

  /// Message storage location
  final MessageBox? messageBox;

  /// Message classification
  final MessageClass? messageClass;

  /// Size of message in bytes
  final int? messageSize;

  /// Unique message identifier
  final String? messageIdentifier;

  /// Object identifier
  final String? objectId;

  /// Read report status
  final DeliveryReport? readReport;

  /// Status of read report
  final AndroidMessageStatus? readReportStatus;

  /// Read status
  final ReadStatus? readStatus;

  /// Reply body text
  final String? replyBody;

  /// Reply content MIME type
  final String? replyContentType;

  /// Reply content URI
  final String? replyContentUri;

  /// Reply count information
  final String? replyCountInfo;

  /// Reply attachment filename
  final String? replyFileName;

  /// Reply original message body
  final String? replyOriginalBody;

  /// Reply original message key
  final String? replyOriginalKey;

  /// Reply recipient address
  final String? replyRecipientAddress;

  /// Reply type
  final ReplyType? replyType;

  /// Reserved flag
  final bool? reserved;

  /// Callback set flag
  final bool? callbackSet;

  /// Report address field
  final String? reportAddress;

  /// Response text
  final String? responseText;

  /// Response status
  final AndroidMessageStatus? responseStatus;

  /// Retrieval status
  final AndroidMessageStatus? retrieveStatus;

  /// Retrieved text content
  final String? retrievedText;

  /// Character set for retrieved text
  final String? retrievedTextCharset;

  /// Source label
  final String? sourceLabel;

  /// Transaction ID
  final String? transactionId;

  /// Using mode
  final UsingMode? usingMode;

  /// MMS version
  final int? version;

  /// Safe message flag
  final bool? safeMessage;

  /// Secret mode flag
  final bool? secretMode;

  /// Text-only flag
  final bool? textOnly;

  /// Report allowed flag
  final bool? reportAllowed;

  /// Creates an MMS message instance
  ///
  /// Required fields include id, parts, recipients, threadId, read status, and simSlot
  /// while other fields are optional.
  Mms({
    required this.id,
    required this.parts,
    required this.body,
    required this.read,
    required this.recipients,
    required this.sender,
    required this.simSlot,
    required this.threadId,
    this.address,
    this.addressCharset,
    this.appId,
    this.binaryInfo,
    this.callbackSet,
    this.cmcProp,
    this.contentClass,
    this.contentLocation,
    this.contentType,
    this.correlationTag,
    this.creator,
    this.date,
    this.dateSent,
    this.deletable,
    this.deliveryDate,
    this.deliveryReport,
    this.deliveryReportStatus,
    this.expiryDate,
    this.favorite,
    this.fromAddress,
    this.hidden,
    this.locked,
    this.messageBox,
    this.messageClass,
    this.messageIdentifier,
    this.messageSize,
    this.objectId,
    this.priority,
    this.readReport,
    this.readReportStatus,
    this.readStatus,
    this.replyBody,
    this.replyContentType,
    this.replyContentUri,
    this.replyCountInfo,
    this.replyFileName,
    this.replyOriginalBody,
    this.replyOriginalKey,
    this.replyRecipientAddress,
    this.replyType,
    this.reportAddress,
    this.reportAllowed,
    this.reserved,
    this.responseStatus,
    this.responseText,
    this.retrieveStatus,
    this.retrievedText,
    this.retrievedTextCharset,
    this.safeMessage,
    this.secretMode,
    this.seen,
    this.serviceCenter,
    this.simImsi,
    this.sourceLabel,
    this.sourceMap,
    this.spamReport,
    this.status,
    this.subject,
    this.subscriptionId,
    this.textOnly,
    this.transactionId,
    this.type,
    this.usingMode,
    this.version,
  });

  /// Creates an Mms instance from a Dart/app-style JSON object
  ///
  /// Handles app-side JSON format where fields use camelCase naming
  static Mms fromJson(Map<String, dynamic> json) => Mms(
    id: FieldHelper.asInt(json['id']) ?? 0,
    sourceMap: json,
    body: json['body'],
    parts:
        List<Map<String, dynamic>>.from(
          json['parts'],
        ).map((e) => MmsPart.fromJson(e)).toList(),
    recipients:
        List<Map<String, dynamic>>.from(
          json['recipients'],
        ).map((e) => MmsParticipant.fromJson(e)).toList(),
    sender: MmsParticipant.fromJson(Map<String, dynamic>.from(json['sender'])),
    address: json['address'],
    threadId: json['threadId'],
    type: FieldHelper.enumFromValue(MmsMessageType.values, json['type']),
    status: FieldHelper.enumFromValue(
      AndroidMessageStatus.values,
      json['status'],
    ),
    priority: FieldHelper.enumFromValue(
      MessagePriority.values,
      json['priority'],
    ),
    subscriptionId: json['subscriptionId'],
    subject: json['subject'],
    read: FieldHelper.asBool(json['read']) ?? false,
    seen: FieldHelper.asBool(json['seen']),
    deletable: FieldHelper.asBool(json['deletable']),
    locked: FieldHelper.asBool(json['locked']),
    favorite: FieldHelper.asBool(json['favorite']),
    hidden: FieldHelper.asBool(json['hidden']),
    spamReport: FieldHelper.asBool(json['spamReport']),
    date: FieldHelper.asDateTime(json['date']),
    dateSent: FieldHelper.asDateTime(json['dateSent']),
    fromAddress: json['fromAddress'],
    creator: json['creator'],
    serviceCenter: json['serviceCenter'],
    simSlot: json['simSlot'],
    simImsi: json['simImsi'],
    addressCharset: json['addressCharset'],
    appId: json['appId'],
    binaryInfo: FieldHelper.enumFromValue(
      BinaryInfo.values,
      json['binaryInfo'],
    ),
    cmcProp: json['cmcProp'],
    contentClass: json['contentClass'],
    contentLocation: json['contentLocation'],
    contentType: json['contentType'],
    correlationTag: json['correlationTag'],
    deliveryDate: FieldHelper.asDateTime(json['deliveryDate']),
    deliveryReport: json['deliveryReport'],
    deliveryReportStatus: FieldHelper.enumFromValue(
      DeliveryReportStatus.values,
      json['deliveryReportStatus'],
    ),
    expiryDate: FieldHelper.asDateTime(json['expiryDate']),
    messageBox: FieldHelper.enumFromValue(
      MessageBox.values,
      json['messageBox'],
    ),
    messageClass: FieldHelper.enumFromValue(
      MessageClass.values,
      json['messageClass'],
    ),
    messageSize: json['messageSize'],
    messageIdentifier: json['messageIdentifier'],
    objectId: json['objectId'],
    readReport: json['readReport'],
    readReportStatus: FieldHelper.enumFromValue(
      AndroidMessageStatus.values,
      json['readReportStatus'],
    ),
    readStatus: FieldHelper.enumFromValue(
      ReadStatus.values,
      json['readStatus'],
    ),
    replyBody: json['reBody'],
    replyContentType: json['reContentType'],
    replyContentUri: json['reContentUri'],
    replyCountInfo: json['reCountInfo'],
    replyFileName: json['reFileName'],
    replyOriginalBody: json['reOriginalBody'],
    replyOriginalKey: json['reOriginalKey'],
    replyRecipientAddress: json['reRecipientAddress'],
    replyType: FieldHelper.enumFromValue(ReplyType.values, json['replyType']),
    reserved: FieldHelper.asBool(json['reserved']),
    callbackSet: FieldHelper.asBool(json['callbackSet']),
    reportAddress: json['rptA'],
    responseText: json['respTxt'],
    responseStatus: FieldHelper.enumFromValue(
      AndroidMessageStatus.values,
      json['respSt'],
    ),
    retrieveStatus: FieldHelper.enumFromValue(
      AndroidMessageStatus.values,
      json['retrSt'],
    ),
    retrievedText: json['retrTxt'],
    retrievedTextCharset: json['retrTxtCs'],
    sourceLabel: json['sourceLabel'],
    transactionId: json['transactionId'],
    usingMode: FieldHelper.enumFromValue(UsingMode.values, json['usingMode']),
    version: json['version'],
    safeMessage: FieldHelper.asBool(json['safeMessage']),
    secretMode: FieldHelper.asBool(json['secretMode']),
    textOnly: FieldHelper.asBool(json['textOnly']),
    reportAllowed: FieldHelper.asBool(json['reportAllowed']),
  );

  /// Converts this MMS instance to app/Dart-style JSON
  ///
  /// Outputs fields in camelCase naming convention for app-side use
  Map<String, dynamic> toJson() => {
    'id': id,
    'parts': parts?.map((e) => e.toJson()).toList(),
    'recipients': recipients?.map((e) => e.toJson()).toList(),
    'sender': sender?.toJson(),
    'address': address,
    'threadId': threadId,
    'type': type?.value,
    'status': status?.value,
    'priority': priority?.value,
    'subscriptionId': subscriptionId,
    'subject': subject,
    'read': read,
    'seen': seen,
    'deletable': deletable,
    'locked': locked,
    'favorite': favorite,
    'hidden': hidden,
    'spamReport': spamReport,
    'date': date?.toIso8601String(),
    'dateSent': dateSent?.toIso8601String(),
    'body': body,
    'fromAddress': fromAddress,
    'creator': creator,
    'serviceCenter': serviceCenter,
    'simSlot': simSlot,
    'simImsi': simImsi,
    'addressCharset': addressCharset,
    'appId': appId,
    'binaryInfo': binaryInfo?.value,
    'cmcProp': cmcProp,
    'contentClass': contentClass,
    'contentLocation': contentLocation,
    'contentType': contentType,
    'correlationTag': correlationTag,
    'deliveryDate': deliveryDate?.toIso8601String(),
    'deliveryReport': deliveryReport,
    'deliveryReportStatus': deliveryReportStatus?.value,
    'expiryDate': expiryDate?.toIso8601String(),
    'messageBox': messageBox?.value,
    'messageClass': messageClass?.value,
    'messageSize': messageSize,
    'messageIdentifier': messageIdentifier,
    'objectId': objectId,
    'readReport': readReport,
    'readReportStatus': readReportStatus?.value,
    'readStatus': readStatus?.value,
    'reBody': replyBody,
    'reContentType': replyContentType,
    'reContentUri': replyContentUri,
    'reCountInfo': replyCountInfo,
    'reFileName': replyFileName,
    'reOriginalBody': replyOriginalBody,
    'reOriginalKey': replyOriginalKey,
    'reRecipientAddress': replyRecipientAddress,
    'replyType': replyType?.value,
    'reserved': reserved,
    'callbackSet': callbackSet,
    'rptA': reportAddress,
    'respTxt': responseText,
    'respSt': responseStatus,
    'retrSt': retrieveStatus?.value,
    'retrTxt': retrievedText,
    'retrTxtCs': retrievedTextCharset,
    'sourceLabel': sourceLabel,
    'transactionId': transactionId,
    'usingMode': usingMode?.value,
    'version': version,
    'safeMessage': safeMessage,
    'secretMode': secretMode,
    'textOnly': textOnly,
    'reportAllowed': reportAllowed,
  };

  /// Creates an MMS instance from Android/DB raw data
  ///
  /// Handles the Android-style field naming conventions (snake_case and abbreviations)
  /// and data type peculiarities from the Android database
  static Future<Mms> fromRaw(Map<String, dynamic> raw) async {
    List<MmsParticipant> recipients =
        raw['recipients'] != null
            ? raw['recipients'] is List<MmsParticipant>
                ? raw['recipients']
                : List<Map<String, dynamic>>.from(
                  raw['recipients'],
                ).map((e) => MmsParticipant.fromRaw(e)).toList()
            : [];

    List<MmsPart> parts =
        raw['parts'] != null
            ? raw['parts'] is List<MmsPart>
                ? raw['parts']
                : List<Map<String, dynamic>>.from(
                  raw['parts'],
                ).map((e) => MmsPart.fromRaw(e)).toList()
            : [];

    return Mms(
      id: FieldHelper.asInt(raw['_id']) ?? FieldHelper.asInt(raw['id'])!,
      parts: parts,
      body: raw['body'] ?? '',
      recipients:
          recipients
              .where((r) => r.participantType != AndroidParticipantType.sender)
              .toList(),
      sender:
          recipients
              .where((r) => r.participantType == AndroidParticipantType.sender)
              .firstOrNull,
      sourceMap: raw,
      address: raw['address'],
      threadId: raw['thread_id'],

      type: FieldHelper.enumFromValue(MmsMessageType.values, raw['m_type']),
      status: FieldHelper.enumFromValue(AndroidMessageStatus.values, raw['st']),
      priority: FieldHelper.enumFromValue(MessagePriority.values, raw['pri']),
      subscriptionId: FieldHelper.asInt(raw['sub_id']),
      subject: raw['sub'],
      read: FieldHelper.asBool(raw['read']) ?? false,
      seen: FieldHelper.asBool(raw['seen']),
      deletable: FieldHelper.asBool(raw['deletable']),
      locked: FieldHelper.asBool(raw['locked']),
      favorite: FieldHelper.asBool(raw['favorite']),
      hidden: FieldHelper.asBool(raw['hidden']),
      spamReport: FieldHelper.asBool(raw['spam_report']),
      date: FieldHelper.asDateTime(raw['date']),
      dateSent: FieldHelper.asDateTime(raw['date_sent']),

      // No "body" for MMS, but included for schema unity
      fromAddress: raw['from_address'],
      creator: raw['creator'],
      serviceCenter: raw['service_center'],
      simSlot: raw['sim_slot'],
      simImsi: raw['sim_imsi'],
      addressCharset: raw['address_charset'],
      appId: raw['app_id'],
      binaryInfo: FieldHelper.enumFromValue(BinaryInfo.values, raw['bin_info']),
      cmcProp: raw['cmc_prop'],
      contentClass: FieldHelper.asInt(raw['ct_cls']),
      contentLocation: raw['ct_l'],
      contentType: raw['ct_t'],
      correlationTag: raw['correlation_tag'],
      deliveryDate: FieldHelper.asDateTime(raw['d_tm']),
      deliveryReport: FieldHelper.enumFromValue(
        DeliveryReport.values,
        raw['d_rpt'],
      ),
      deliveryReportStatus: FieldHelper.enumFromValue(
        DeliveryReportStatus.values,
        raw['d_rpt_st'],
      ),
      expiryDate: FieldHelper.asDateTime(raw['exp']),
      messageBox: FieldHelper.enumFromValue(MessageBox.values, raw['msg_box']),
      messageClass: FieldHelper.enumFromValue(
        MessageClass.values,
        raw['m_cls'] is int ? raw['m_cls'] : int.tryParse(raw['m_cls'] ?? ''),
      ),
      messageSize: raw['m_size'],
      messageIdentifier: raw['m_id'],
      objectId: raw['object_id'],
      readReport: FieldHelper.enumFromValue(DeliveryReport.values, raw['rr']),
      readReportStatus: FieldHelper.enumFromValue(
        AndroidMessageStatus.values,
        raw['rr_st'],
      ),
      readStatus: FieldHelper.enumFromValue(
        ReadStatus.values,
        raw['read_status'] is int
            ? raw['read_status']
            : int.tryParse(raw['read_status'] ?? ''),
      ),
      replyBody: raw['re_body'],
      replyContentType: raw['re_content_type'],
      replyContentUri: raw['re_content_uri'],
      replyCountInfo: raw['re_count_info'],
      replyFileName: raw['re_file_name'],
      replyOriginalBody: raw['re_original_body'],
      replyOriginalKey: raw['re_original_key'],
      replyRecipientAddress: raw['re_recipient_address'],
      replyType: FieldHelper.enumFromValue(ReplyType.values, raw['re_type']),
      reserved: FieldHelper.asBool(raw['reserved']),
      callbackSet: FieldHelper.asBool(raw['callback_set']),
      reportAddress: raw['rpt_a'],
      responseText: raw['resp_txt'],
      responseStatus: FieldHelper.enumFromValue(
        AndroidMessageStatus.values,
        raw['resp_st'],
      ),
      retrieveStatus: FieldHelper.enumFromValue(
        AndroidMessageStatus.values,
        raw['retr_st'],
      ),
      retrievedText: raw['retr_txt'],
      retrievedTextCharset: raw['retr_txt_cs'],
      sourceLabel: raw['sourceLabel'],
      transactionId: raw['tr_id'],
      usingMode: FieldHelper.enumFromValue(UsingMode.values, raw['using_mode']),
      version: FieldHelper.asInt(raw['v']),
      safeMessage: FieldHelper.asBool(raw['safe_message']),
      secretMode: FieldHelper.asBool(raw['secret_mode']),
      textOnly: FieldHelper.asBool(raw['text_only']),
      reportAllowed: raw['report_allowed'],
    );
  }

  /// Converts this MMS instance to Android/DB raw format
  ///
  /// Outputs fields in the Android system's naming conventions (snake_case and abbreviations)
  /// for database storage or API compatibility
  Map<String, dynamic> toRaw() => {
    '_id': id,
    'parts': parts?.map((e) => e.toRaw()).toList(),
    'recipients': [
      if (recipients != null) ...recipients!.map((e) => e.toRaw()),
      if (sender != null) ...[sender!.toRaw()],
    ],
    'address': address,
    'thread_id': threadId,
    'm_type': type?.value,
    'st': status?.value,
    'pri': priority?.value,
    'sub_id': subscriptionId,
    'sub': subject,
    'read': read == true ? "1" : "0",
    'seen': seen == true ? "1" : "0",
    'deletable': deletable == true ? "1" : "0",
    'locked': locked == true ? "1" : "0",
    'favorite': favorite == true ? "1" : "0",
    'hidden': hidden == true ? "1" : "0",
    'spam_report': spamReport == true ? "1" : "0",
    'date': date?.toIso8601String(),
    'date_sent': dateSent?.toIso8601String(),
    'body': body,
    'from_address': fromAddress,
    'creator': creator,
    'service_center': serviceCenter,
    'sim_slot': simSlot,
    'sim_imsi': simImsi,
    'address_charset': addressCharset,
    'app_id': appId,
    'bin_info': binaryInfo?.value,
    'cmc_prop': cmcProp,
    'ct_cls': contentClass,
    'ct_l': contentLocation,
    'ct_t': contentType,
    'correlation_tag': correlationTag,
    'd_tm': deliveryDate?.toIso8601String(),
    'd_rpt': deliveryReport,
    'd_rpt_st': deliveryReportStatus?.value,
    'exp': expiryDate?.toIso8601String(),
    'msg_box': messageBox?.value,
    'm_cls': messageClass?.value,
    'm_size': messageSize,
    'm_id': messageIdentifier,
    'object_id': objectId,
    'rr': readReport,
    'rr_st': readReportStatus?.value,
    'read_status': readStatus?.value,
    're_body': replyBody,
    're_content_type': replyContentType,
    're_content_uri': replyContentUri,
    're_count_info': replyCountInfo,
    're_file_name': replyFileName,
    're_original_body': replyOriginalBody,
    're_original_key': replyOriginalKey,
    're_recipient_address': replyRecipientAddress,
    're_type': replyType?.value,
    'reserved': reserved == true ? "1" : "0",
    'callback_set': callbackSet == true ? "1" : "0",
    'rpt_a': reportAddress,
    'resp_txt': responseText,
    'resp_st': responseStatus,
    'retr_st': retrieveStatus?.value,
    'retr_txt': retrievedText,
    'retr_txt_cs': retrievedTextCharset,
    'sourceLabel': sourceLabel,
    'tr_id': transactionId,
    'using_mode': usingMode?.value,
    'v': version,
    'safe_message': safeMessage == true ? "1" : "0",
    'secret_mode': secretMode == true ? "1" : "0",
    'text_only': textOnly == true ? "1" : "0",
    'report_allowed': reportAllowed,
  };
}
