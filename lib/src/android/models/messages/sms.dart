import 'dart:core';

import 'package:simple_sms_native/src/android/models/model_helpers.dart';
import '../../../interfaces/models_interface.dart';
import '../enums/sms_mms_enums.dart';

/// An SMS message record read from the Android telephony content provider.
///
/// Mirrors the columns in `content://sms` with Dart-friendly names.
/// Produced by [LookupService.listSms] and by the incoming-SMS callback
/// registered through [AndroidMessaging.initialize]; consumers rarely
/// construct instances directly.
///
/// The fields consumers usually care about are [id] (stable Hive join
/// key), [threadId] (conversation grouping key — the same across every
/// SMS + MMS in a thread), [body] (message text, may be null for system
/// messages), [address] (sender for inbound, recipient for outbound),
/// [date] (wall-clock timestamp of delivery or send), [type] ([SmsMessageType]
/// — inbox / sent / outbox / draft / …), [read] + [seen] flags, and
/// [simSlot] for multi-SIM device attribution. The remainder of the
/// surface (priority, reBody, reservedCarrierTags, etc.) is provider
/// metadata that flows through for completeness but isn't usually read
/// by app code.
///
/// Use [Sms.fromJson] / [toJson] for app-layer round-trips (e.g. cached
/// records) and [Sms.fromRaw] / [toRaw] when interacting with the raw
/// platform-channel payload.
class Sms implements ModelInterface {
  @override
  final int id;
  final int threadId;

  @override
  final Map<String, dynamic>? sourceMap;

  final String? address;
  final String? announcementsScenarioId;
  final int? announcementsSubtype;
  final int? appId;
  final int? binInfo;
  /// Samsung OEM column `block_filtered_status` — unset on stock Android.
  /// Surfaces on device as an int flag for Samsung's block-filter pipeline.
  final int? blockFilteredStatus;
  final String? body;
  final String? callbackNumber;
  final String? cmcProp;
  final String? correlationTag;
  final String? creator;
  final DateTime date;
  final DateTime? dateSent;
  /// Samsung OEM column `decorate_bubble_value` — chat-bubble styling
  /// payload. Free-form; we pass it through unchanged.
  final String? decorateBubbleValue;
  final bool? deletable;
  final DateTime? deliveryDate;
  final int? deliveryReportCount;
  final String? deviceName;
  final int? errorCode;
  final bool? favorite;
  /// Samsung OEM column `group_cotag` — group-SMS correlation tag on some
  /// Samsung carrier builds.
  final String? groupCotag;
  /// Samsung OEM column `is_satellite` — true on T-Mobile/Verizon satellite
  /// roaming SMS.
  final bool? isSatellite;
  /// Samsung OEM column `re_count_info_custom_reaction` — JSON blob for
  /// emoji-reaction metadata on RCS-over-SMS threads.
  final String? reCountInfoCustomReaction;
  /// Samsung OEM column `spam_type` — spam-classification flag.
  final int? spamType;
  final String? rawSender;
  final String? groupId;
  final String? groupType;
  final bool? hidden;
  final String? linkUrl;
  final bool? locked;
  final int? messageId;
  final String? objectId;
  final String? person;
  final MessagePriority? priority;
  final int? protocol;
  final bool? read;
  final String? reBody;
  final ContentType? reContentType;
  final String? reContentUri;
  final String? reCountInfo;
  final String? reFileName;
  final String? reOriginalBody;
  final String? reOriginalKey;
  final bool? replyPathPresent;
  final String? reRecipientAddress;
  final bool? reserved;
  final SmsMessageType? reType;
  final bool? roamPending;
  final bool? safeMessage;
  final bool? secretMode;
  final bool? seen;
  final String? serviceCenter;
  final int? serviceCommand;
  final String? serviceCommandContent;
  final String? simImsi;
  final int? simSlot;
  final String? sourceLabel;
  final bool? spamReport;
  final AndroidMessageStatus? status;
  final String? subject;
  final int? subscriptionId;
  final int? teleserviceId;
  final SmsMessageType? type;
  final UsingMode? usingMode;

  Sms({
    required this.id,
    required this.threadId,
    this.sourceMap,

    this.address,
    this.announcementsScenarioId,
    this.announcementsSubtype,
    this.appId,
    this.binInfo,
    this.blockFilteredStatus,
    this.body,
    this.callbackNumber,
    this.cmcProp,
    this.correlationTag,
    this.creator,
    required this.date,
    this.dateSent,
    this.decorateBubbleValue,
    this.deletable,
    this.deliveryDate,
    this.deliveryReportCount,
    this.deviceName,
    this.errorCode,
    this.favorite,
    this.groupCotag,
    this.isSatellite,
    this.reCountInfoCustomReaction,
    this.spamType,
    this.rawSender,
    this.groupId,
    this.groupType,
    this.hidden,
    this.linkUrl,
    this.locked,
    this.messageId,
    this.objectId,
    this.person,
    this.priority,
    this.protocol,
    this.read,
    this.reBody,
    this.reContentType,
    this.reContentUri,
    this.reCountInfo,
    this.reFileName,
    this.reOriginalBody,
    this.reOriginalKey,
    this.replyPathPresent,
    this.reRecipientAddress,
    this.reserved,
    this.reType,
    this.roamPending,
    this.safeMessage,
    this.secretMode,
    this.seen,
    this.serviceCenter,
    this.serviceCommand,
    this.serviceCommandContent,
    this.simImsi,
    this.simSlot,
    this.sourceLabel,
    this.spamReport,
    this.status,
    this.subject,
    this.subscriptionId,
    this.teleserviceId,
    this.type,
    this.usingMode,
  });

  // ---- JSON (App/Server) ----
  factory Sms.fromJson(Map<String, dynamic> json) => Sms(
    // Match fromRaw's fallback semantics (was `!` force-unwrap). A
    // cached/server payload missing `id` previously threw; 0 is the
    // "unparseable row" default used elsewhere in these models.
    id: FieldHelper.asInt(json["id"]) ?? 0,
    address: json["address"],
    announcementsScenarioId: json["announcementsScenarioId"],
    announcementsSubtype: FieldHelper.asInt(json["announcementsSubtype"]),
    appId: FieldHelper.asInt(json["appId"]),
    binInfo: FieldHelper.asInt(json["binInfo"]),
    blockFilteredStatus: FieldHelper.asInt(json["blockFilteredStatus"]),
    body: json["body"],
    callbackNumber: json["callbackNumber"],
    cmcProp: json["cmcProp"],
    correlationTag: json["correlationTag"],
    creator: json["creator"],
    date:
        json["date"] != null
            ? (FieldHelper.asDateTime(json["date"]) ??
                DateTime.fromMillisecondsSinceEpoch(0))
            : DateTime.fromMillisecondsSinceEpoch(0),
    dateSent: FieldHelper.asDateTime(json["dateSent"]),
    decorateBubbleValue: json["decorateBubbleValue"]?.toString(),
    deletable: FieldHelper.asBool(json["deletable"]),
    deliveryDate: FieldHelper.asDateTime(json["deliveryDate"]),
    deliveryReportCount: FieldHelper.asInt(json["deliveryReportCount"]),
    deviceName: json["deviceName"],
    errorCode: FieldHelper.asInt(json["errorCode"]),
    favorite: FieldHelper.asBool(json["favorite"]),
    groupCotag: json["groupCotag"]?.toString(),
    isSatellite: FieldHelper.asBool(json["isSatellite"]),
    reCountInfoCustomReaction: json["reCountInfoCustomReaction"]?.toString(),
    spamType: FieldHelper.asInt(json["spamType"]),
    rawSender: json["fromAddress"],
    groupId: json["groupId"],
    groupType: json["groupType"],
    hidden: FieldHelper.asBool(json["hidden"]),
    linkUrl: json["linkUrl"],
    locked: FieldHelper.asBool(json["locked"]),
    // Coerce stringified IDs too — some providers emit msg_id as a
    // stringified long, and cached round-trips preserve whatever
    // shape the provider produced originally.
    messageId: FieldHelper.asInt(json["messageId"]),
    objectId: json["objectId"],
    person: json["person"],
    priority: FieldHelper.enumFromValue(
      MessagePriority.values,
      json["priority"],
    ),
    protocol: FieldHelper.asInt(json["protocol"]),
    read: FieldHelper.asBool(json["read"]),
    reBody: json["reBody"],
    reContentType: FieldHelper.enumFromValue(
      ContentType.values,
      json["reContentType"],
    ),
    reContentUri: json["reContentUri"],
    reCountInfo: json["reCountInfo"],
    reFileName: json["reFileName"],
    reOriginalBody: json["reOriginalBody"],
    reOriginalKey: json["reOriginalKey"],
    replyPathPresent: FieldHelper.asBool(json["replyPathPresent"]),
    reRecipientAddress: json["reRecipientAddress"],
    reserved: FieldHelper.asBool(json["reserved"]),
    reType: FieldHelper.enumFromValue(SmsMessageType.values, json["reType"]),
    roamPending: FieldHelper.asBool(json["roamPending"]),
    safeMessage: FieldHelper.asBool(json["safeMessage"]),
    secretMode: FieldHelper.asBool(json["secretMode"]),
    seen: FieldHelper.asBool(json["seen"]),
    serviceCenter: json["serviceCenter"],
    serviceCommand: FieldHelper.asInt(json["serviceCommand"]),
    serviceCommandContent: json["serviceCommandContent"],
    simImsi: json["simImsi"],
    simSlot: FieldHelper.asInt(json["simSlot"]),
    sourceLabel: json["sourceLabel"],
    spamReport: FieldHelper.asBool(json["spamReport"]),
    status:
        FieldHelper.enumFromValue(
          AndroidMessageStatus.values,
          json["status"],
        ) ??
        AndroidMessageStatus.retrieved,
    subject: json["subject"],
    subscriptionId: FieldHelper.asInt(json["subscriptionId"]),
    teleserviceId: FieldHelper.asInt(json["teleserviceId"]),
    threadId: FieldHelper.asInt(json["threadId"]) ?? 0,
    // Standardized on nullable with a fallback at call sites, matching
    // fromRaw. Removes the `!` force-unwrap that would throw on any
    // legacy payload missing `type`.
    type: FieldHelper.enumFromValue(SmsMessageType.values, json["type"]),
    usingMode:
        FieldHelper.enumFromValue(UsingMode.values, json["usingMode"]) ??
        UsingMode.normal,
    sourceMap: json,
  );

  Map<String, dynamic> toJson() => {
    "_id": id,
    "address": address,
    "announcementsScenarioId": announcementsScenarioId,
    "announcementsSubtype": announcementsSubtype,
    "appId": appId,
    "binInfo": binInfo,
    "blockFilteredStatus": blockFilteredStatus,
    "body": body,
    "callbackNumber": callbackNumber,
    "cmcProp": cmcProp,
    "correlationTag": correlationTag,
    "creator": creator,
    "date": date.toIso8601String(),
    "dateSent": dateSent?.toIso8601String(),
    "decorateBubbleValue": decorateBubbleValue,
    "deletable": deletable,
    "deliveryDate": deliveryDate?.toIso8601String(),
    "deliveryReportCount": deliveryReportCount,
    "deviceName": deviceName,
    "errorCode": errorCode,
    "favorite": favorite,
    "groupCotag": groupCotag,
    "isSatellite": isSatellite,
    "reCountInfoCustomReaction": reCountInfoCustomReaction,
    "spamType": spamType,
    "fromAddress": rawSender,
    "groupId": groupId,
    "groupType": groupType,
    "hidden": hidden,
    "linkUrl": linkUrl,
    "locked": locked,
    "messageId": messageId,
    "objectId": objectId,
    "person": person,
    "priority": priority?.value,
    "protocol": protocol,
    "read": read,
    "reBody": reBody,
    "reContentType": reContentType?.value,
    "reContentUri": reContentUri,
    "reCountInfo": reCountInfo,
    "reFileName": reFileName,
    "reOriginalBody": reOriginalBody,
    "reOriginalKey": reOriginalKey,
    "replyPathPresent": replyPathPresent,
    "reRecipientAddress": reRecipientAddress,
    "reserved": reserved,
    "reType": reType?.value,
    "roamPending": roamPending,
    "safeMessage": safeMessage,
    "secretMode": secretMode,
    "seen": seen,
    "serviceCenter": serviceCenter,
    "serviceCommand": serviceCommand,
    "serviceCommandContent": serviceCommandContent,
    "simImsi": simImsi,
    "simSlot": simSlot,
    "sourceLabel": sourceLabel,
    "spamReport": spamReport,
    "status": status?.value,
    "subject": subject,
    "subscriptionId": subscriptionId,
    "teleserviceId": teleserviceId,
    "threadId": threadId,
    "type": type?.value,
    "usingMode": usingMode?.value,
  };

  // ---- Android/Raw ----
  factory Sms.fromRaw(Map<String, dynamic> raw) => Sms(
    // Telephony.Sms PK per BaseColumns is `_id`. The fallback to `id` is a
    // legacy shim preserved only for unit tests that mock rows without `_id`;
    // real provider rows always have `_id`. Zero is a last-resort default
    // that matches the "unparseable row" behaviour used elsewhere.
    id: FieldHelper.asInt(raw['_id']) ?? FieldHelper.asInt(raw['id']) ?? 0,
    address: raw["address"],
    announcementsScenarioId: raw["announcements_scenario_id"],
    announcementsSubtype: FieldHelper.asInt(raw["announcements_subtype"]),
    appId: FieldHelper.asInt(raw["app_id"]),
    binInfo: FieldHelper.asInt(raw["bin_info"]),
    blockFilteredStatus: FieldHelper.asInt(raw["block_filtered_status"]),
    body: raw["body"],
    callbackNumber: raw["callback_number"],
    cmcProp: raw["cmc_prop"],
    correlationTag: raw["correlation_tag"],
    creator: raw["creator"],
    date:
        raw["date"] != null
            ? (FieldHelper.asDateTime(raw["date"]!) ??
                DateTime.fromMillisecondsSinceEpoch(0))
            : DateTime.fromMillisecondsSinceEpoch(0),
    dateSent: FieldHelper.asDateTime(raw["date_sent"]),
    decorateBubbleValue: raw["decorate_bubble_value"]?.toString(),
    deletable: FieldHelper.asBool(raw["deletable"]),
    deliveryDate: FieldHelper.asDateTime(raw["delivery_date"]),
    deliveryReportCount: FieldHelper.asInt(raw["d_rpt_cnt"]),
    deviceName: raw["device_name"],
    errorCode: FieldHelper.asInt(raw["error_code"]),
    favorite: FieldHelper.asBool(raw["favorite"]),
    groupCotag: raw["group_cotag"],
    isSatellite: FieldHelper.asBool(raw["is_satellite"]),
    reCountInfoCustomReaction: raw["re_count_info_custom_reaction"]?.toString(),
    spamType: FieldHelper.asInt(raw["spam_type"]),
    rawSender: raw["from_address"],
    groupId: raw["group_id"],
    groupType: raw["group_type"],
    hidden: FieldHelper.asBool(raw["hidden"]),
    linkUrl: raw["link_url"],
    locked: FieldHelper.asBool(raw["locked"]),
    messageId: FieldHelper.asInt(raw["msg_id"]),
    objectId: raw["object_id"],
    person: raw["person"],
    priority: FieldHelper.enumFromValue(
      MessagePriority.values,
      FieldHelper.asInt(raw["pri"]),
    ),
    protocol: FieldHelper.asInt(raw["protocol"]),
    read: FieldHelper.asBool(raw["read"]),
    reBody: raw["re_body"],
    reContentType: FieldHelper.enumFromValue(
      ContentType.values,
      raw["re_content_type"],
    ),
    reContentUri: raw["re_content_uri"],
    reCountInfo: raw["re_count_info"],
    reFileName: raw["re_file_name"],
    reOriginalBody: raw["re_original_body"],
    reOriginalKey: raw["re_original_key"],
    replyPathPresent: FieldHelper.asBool(raw["reply_path_present"]),
    reRecipientAddress: raw["re_recipient_address"],
    reserved: FieldHelper.asBool(raw["reserved"]),
    reType: FieldHelper.enumFromValue(
      SmsMessageType.values,
      FieldHelper.asInt(raw["re_type"]),
    ),
    roamPending: FieldHelper.asBool(raw["roam_pending"]),
    safeMessage: FieldHelper.asBool(raw["safe_message"]),
    secretMode: FieldHelper.asBool(raw["secret_mode"]),
    seen: FieldHelper.asBool(raw["seen"]),
    serviceCenter: raw["service_center"],
    serviceCommand: FieldHelper.asInt(raw["svc_cmd"]),
    serviceCommandContent: raw["svc_cmd_content"],
    simImsi: raw["sim_imsi"],
    simSlot: FieldHelper.asInt(raw["sim_slot"]),
    sourceLabel: raw["sourceLabel"],
    spamReport: FieldHelper.asBool(raw["spam_report"]),
    status: FieldHelper.enumFromValue(
      AndroidMessageStatus.values,
      FieldHelper.asInt(raw["status"]),
    ),
    subject: raw["subject"],
    subscriptionId: FieldHelper.asInt(raw["sub_id"]),
    teleserviceId: FieldHelper.asInt(raw["teleservice_id"]),
    threadId: FieldHelper.asInt(raw["thread_id"]) ?? 0,
    type: FieldHelper.enumFromValue(
      SmsMessageType.values,
      FieldHelper.asInt(raw["type"]),
    ),
    usingMode: FieldHelper.enumFromValue(
      UsingMode.values,
      FieldHelper.asInt(raw["using_mode"]),
    ),
    sourceMap: raw,
  );

  Map<String, dynamic> toRaw() => {
    "_id": id,
    "address": address,
    "announcements_scenario_id": announcementsScenarioId,
    "announcements_subtype": announcementsSubtype,
    "app_id": appId,
    "bin_info": binInfo,
    "block_filtered_status": blockFilteredStatus,
    "body": body,
    "callback_number": callbackNumber,
    "cmc_prop": cmcProp,
    "correlation_tag": correlationTag,
    "creator": creator,
    "date": date.millisecondsSinceEpoch,
    "date_sent": dateSent?.millisecondsSinceEpoch,
    "decorate_bubble_value": decorateBubbleValue,
    "deletable": FieldHelper.boolToInt(deletable),
    // Emit as int millis so fromRaw's FieldHelper.asDateTime picks it
    // up on round-trip (previously serialized as a numeric String that
    // only parsed via the now-hardened asDateTime String→int fallback).
    "delivery_date": deliveryDate?.millisecondsSinceEpoch,
    "d_rpt_cnt": deliveryReportCount,
    "device_name": deviceName,
    "error_code": errorCode,
    "favorite": FieldHelper.boolToInt(favorite),
    "group_cotag": groupCotag,
    "is_satellite": FieldHelper.boolToInt(isSatellite),
    "re_count_info_custom_reaction": reCountInfoCustomReaction,
    "spam_type": spamType,
    "from_address": rawSender,
    "group_id": groupId,
    "group_type": groupType,
    "hidden": FieldHelper.boolToInt(hidden),
    "link_url": linkUrl,
    "locked": FieldHelper.boolToInt(locked),
    "msg_id": messageId,
    "object_id": objectId,
    "person": person,
    "pri": priority?.value,
    "protocol": protocol,
    "read": FieldHelper.boolToInt(read),
    "re_body": reBody,
    "re_content_type": reContentType?.value,
    "re_content_uri": reContentUri,
    "re_count_info": reCountInfo,
    "re_file_name": reFileName,
    "re_original_body": reOriginalBody,
    "re_original_key": reOriginalKey,
    "reply_path_present": FieldHelper.boolToInt(replyPathPresent),
    "re_recipient_address": reRecipientAddress,
    "reserved": FieldHelper.boolToInt(reserved),
    "re_type": reType?.value,
    "roam_pending": FieldHelper.boolToInt(roamPending),
    "safe_message": FieldHelper.boolToInt(safeMessage),
    "secret_mode": FieldHelper.boolToInt(secretMode),
    "seen": FieldHelper.boolToInt(seen),
    "service_center": serviceCenter,
    "svc_cmd": serviceCommand,
    "svc_cmd_content": serviceCommandContent,
    "sim_imsi": simImsi,
    "sim_slot": simSlot,
    "sourceLabel": sourceLabel,
    "spam_report": FieldHelper.boolToInt(spamReport),
    "status": status?.value,
    "subject": subject,
    "sub_id": subscriptionId,
    "teleservice_id": teleserviceId,
    "thread_id": threadId,
    "type": type?.value,
    "using_mode": usingMode?.value,
  };
}
