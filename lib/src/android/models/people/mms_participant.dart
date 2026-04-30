// To parse this JSON data, do
//
//     final MmsParticipant = MmsParticipantFromJson(jsonString);

import 'package:simple_sms_native/src/android/models/model_helpers.dart';

import '../enums/contact_enums.dart';
import '../enums/sms_mms_enums.dart';

class MmsParticipant {
  final int id;

  final Map<String, dynamic>? sourceMap;

  final CharSet? charset;
  final AndroidParticipantType? participantType;
  final String? address;
  final int? contactId;
  final String? sourceLabel;
  final int? msgId;

  /// Samsung column `sub_id` — subscription / SIM slot id. `-1` = unknown.
  /// Surfaces on every mms_addr row on Samsung Android 16.
  final int? subId;

  MmsParticipant({
    required this.id,
    this.sourceMap,

    this.msgId,
    this.participantType,
    this.sourceLabel,
    this.charset,
    this.address,
    this.contactId,
    this.subId,
  });

  static MmsParticipant fromJson(Map<String, dynamic> json) => MmsParticipant(
    address: json["address"],
    charset: FieldHelper.enumFromValue(
      CharSet.values,
      FieldHelper.asInt(json["charset"]),
    ),
    contactId: FieldHelper.asInt(json["contact_id"]),
    id: FieldHelper.primaryKey(json),
    msgId: FieldHelper.asInt(json["msg_id"]),
    sourceLabel: json["sourceLabel"],
    participantType: FieldHelper.enumFromValue(
      AndroidParticipantType.values,
      FieldHelper.asInt(json["type"]),
    ),
    subId: FieldHelper.asInt(json["sub_id"]),
  );

  Map<String, dynamic> toJson() => {
    "_id": id,
    "address": address,
    "charset": charset?.value,
    "contact_id": contactId,
    "msg_id": msgId,
    "sourceLabel": sourceLabel,
    "type": participantType?.value,
    "sub_id": subId,
  };

  factory MmsParticipant.fromRaw(Map<String, dynamic> raw) {
    final participant = MmsParticipant(
      // `_id` is the BaseColumns PK on mms_addr; `id` is a legacy test shim.
      id: FieldHelper.primaryKey(raw),
      address: raw["address"]?.toString(),
      charset: FieldHelper.enumFromValue(
        CharSet.values,
        FieldHelper.asInt(raw["charset"]),
      ),
      contactId: FieldHelper.asInt(raw["contact_id"]),
      msgId: FieldHelper.asInt(raw["msg_id"]),
      sourceLabel: raw["sourceLabel"],
      participantType:
          raw['type'] is AndroidParticipantType
              ? raw['type']
              : FieldHelper.enumFromValue(
                AndroidParticipantType.values,
                FieldHelper.asInt(raw["type"]),
              ),
      subId: FieldHelper.asInt(raw["sub_id"]),
    );

    return participant;
  }

  Map<String, dynamic> toRaw() => {
    "_id": id,
    "address": address,
    "charset": charset?.value,
    "contact_id": contactId,
    "msg_id": msgId,
    "sourceLabel": sourceLabel,
    "type": participantType?.value,
    "sub_id": subId,
  };
}
