import 'dart:core';

import 'package:flutter/material.dart';
import 'package:simple_sms/src/android/models/model_helpers.dart';
import 'package:simple_sms/src/android/queries/people/contact_contactables_query.dart';
import '../../../interfaces/models_interface.dart';
import '../enums/sms_mms_enums.dart';
import '../people/contactables.dart';

// --- Main Class ---
class Sms implements ModelInterface {
  // Recipient - "Address" is represented by SMS Type 1 (Inbox)
  Future<Contactable?>? get recipient async {
    try {
      return type == SmsMessageType.inbox
          ? rawSender != null
              ? (await ContactableQuery.byPhone(phoneNum: rawSender!).fetch())
                  .firstOrNull
              : null
          : null;
    } catch (e, s) {
      debugPrint(e.toString());
      debugPrint(s.toString());
      return null;
    }
  }

  // Sender - "Address" is NOT represented by SMS Type 1 (Inbox)
  Future<Contactable?>? get sender async {
    try {
      return type != SmsMessageType.inbox
          ? rawSender != null
              ? (await ContactableQuery.byPhone(phoneNum: rawSender!).fetch())
                  .firstOrNull
              : null
          : null;
    } catch (e, s) {
      debugPrint(e.toString());
      debugPrint(s.toString());
      return null;
    }
  }

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
  final String? body;
  final String? callbackNumber;
  final String? cmcProp;
  final String? correlationTag;
  final String? creator;
  final DateTime date;
  final DateTime? dateSent;
  final bool? deletable;
  final DateTime? deliveryDate;
  final int? deliveryReportCount;
  final String? deviceName;
  final int? errorCode;
  final bool? favorite;
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
    this.body,
    this.callbackNumber,
    this.cmcProp,
    this.correlationTag,
    this.creator,
    required this.date,
    this.dateSent,
    this.deletable,
    this.deliveryDate,
    this.deliveryReportCount,
    this.deviceName,
    this.errorCode,
    this.favorite,
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
    id: FieldHelper.asInt(json["id"])!,
    address: json["address"],
    announcementsScenarioId: json["announcementsScenarioId"],
    announcementsSubtype: json["announcementsSubtype"],
    appId: json["appId"],
    binInfo: json["binInfo"],
    body: json["body"],
    callbackNumber: json["callbackNumber"],
    cmcProp: json["cmcProp"],
    correlationTag: json["correlationTag"],
    creator: json["creator"],
    date:
        json["date"] != null
            ? FieldHelper.asDateTime(json["date"])!
            : DateTime(2097, 12, 31, 23, 59, 59),
    dateSent:
        json["dateSent"] != null
            ? FieldHelper.asDateTime(json["dateSent"]!)
            : DateTime(2098, 12, 31, 23, 59, 59),
    deletable: FieldHelper.asBool(json["deletable"]),
    deliveryDate: FieldHelper.asDateTime(json["deliveryDate"]),
    deliveryReportCount: json["deliveryReportCount"],
    deviceName: json["deviceName"],
    errorCode: json["errorCode"],
    favorite: FieldHelper.asBool(json["favorite"]),
    rawSender: json["fromAddress"],
    groupId: json["groupId"],
    groupType: json["groupType"],
    hidden: FieldHelper.asBool(json["hidden"]),
    linkUrl: json["linkUrl"],
    locked: FieldHelper.asBool(json["locked"]),
    messageId: json["messageId"],
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
    serviceCommand: json["serviceCommand"],
    serviceCommandContent: json["serviceCommandContent"],
    simImsi: json["simImsi"],
    simSlot: json["simSlot"],
    sourceLabel: json["sourceLabel"],
    spamReport: FieldHelper.asBool(json["spamReport"]),
    status:
        FieldHelper.enumFromValue(
          AndroidMessageStatus.values,
          json["status"],
        ) ??
        AndroidMessageStatus.retrieved,
    subject: json["subject"],
    subscriptionId: json["subscriptionId"],
    teleserviceId: json["teleserviceId"],
    threadId: json["threadId"],
    type: FieldHelper.enumFromValue(SmsMessageType.values, json["type"])!,
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
    "body": body,
    "callbackNumber": callbackNumber,
    "cmcProp": cmcProp,
    "correlationTag": correlationTag,
    "creator": creator,
    "date": date.toIso8601String(),
    "dateSent": dateSent?.toIso8601String(),
    "deletable": deletable,
    "deliveryDate": deliveryDate?.toIso8601String(),
    "deliveryReportCount": deliveryReportCount,
    "deviceName": deviceName,
    "errorCode": errorCode,
    "favorite": favorite,
    "sender": sender,
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
    id: FieldHelper.asInt(raw['_id']) ?? FieldHelper.asInt(raw['id'])!,
    address: raw["address"],
    announcementsScenarioId: raw["announcements_scenario_id"],
    announcementsSubtype: raw["announcements_subtype"],
    appId: raw["app_id"],
    binInfo: raw["bin_info"],
    body: raw["body"],
    callbackNumber: raw["callback_number"],
    cmcProp: raw["cmc_prop"],
    correlationTag: raw["correlation_tag"],
    creator: raw["creator"],
    date:
        raw["date"] != null
            ? FieldHelper.asDateTime(raw["date"]!)!
            : DateTime(2099, 12, 31, 23, 59, 59),
    dateSent:
        raw["dateSent"] != null
            ? FieldHelper.asDateTime(raw["dateSent"]!)
            : DateTime(2199, 12, 31, 23, 59, 59),
    deletable: FieldHelper.asBool(raw["deletable"]),
    deliveryDate: FieldHelper.asDateTime(raw["delivery_date"]),
    deliveryReportCount: raw["d_rpt_cnt"],
    deviceName: raw["device_name"],
    errorCode: raw["error_code"],
    favorite: FieldHelper.asBool(raw["favorite"]),
    rawSender: raw["from_address"],
    groupId: raw["group_id"],
    groupType: raw["group_type"],
    hidden: FieldHelper.asBool(raw["hidden"]),
    linkUrl: raw["link_url"],
    locked: FieldHelper.asBool(raw["locked"]),
    messageId: raw["msg_id"],
    objectId: raw["object_id"],
    person: raw["person"],
    priority: FieldHelper.enumFromValue(MessagePriority.values, raw["pri"]),
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
    reType: FieldHelper.enumFromValue(SmsMessageType.values, raw["re_type"]),
    roamPending: FieldHelper.asBool(raw["roam_pending"]),
    safeMessage: FieldHelper.asBool(raw["safe_message"]),
    secretMode: FieldHelper.asBool(raw["secret_mode"]),
    seen: FieldHelper.asBool(raw["seen"]),
    serviceCenter: raw["service_center"],
    serviceCommand: raw["svc_cmd"],
    serviceCommandContent: raw["svc_cmd_content"],
    simImsi: raw["sim_imsi"],
    simSlot: raw["sim_slot"],
    sourceLabel: raw["sourceLabel"],
    spamReport: FieldHelper.asBool(raw["spam_report"]),
    status: FieldHelper.enumFromValue(
      AndroidMessageStatus.values,
      raw["status"],
    ),
    subject: raw["subject"],
    subscriptionId: raw["sub_id"],
    teleserviceId: raw["teleservice_id"],
    threadId: raw["thread_id"],
    type: FieldHelper.enumFromValue(SmsMessageType.values, raw["type"]),
    usingMode: FieldHelper.enumFromValue(UsingMode.values, raw["using_mode"]),
    sourceMap: raw,
  );

  Map<String, dynamic> toRaw() => {
    "_id": id,
    "address": address,
    "announcements_scenario_id": announcementsScenarioId,
    "announcements_subtype": announcementsSubtype,
    "app_id": appId,
    "bin_info": binInfo,
    "body": body,
    "callback_number": callbackNumber,
    "cmc_prop": cmcProp,
    "correlation_tag": correlationTag,
    "creator": creator,
    "date": date.millisecondsSinceEpoch,
    "date_sent": dateSent?.millisecondsSinceEpoch,
    "deletable": FieldHelper.boolToInt(deletable),
    "delivery_date": deliveryDate?.millisecondsSinceEpoch.toString(),
    "d_rpt_cnt": deliveryReportCount,
    "device_name": deviceName,
    "error_code": errorCode,
    "favorite": FieldHelper.boolToInt(favorite),
    "from_address": sender,
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
