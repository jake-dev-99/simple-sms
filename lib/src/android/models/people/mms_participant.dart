// To parse this JSON data, do
//
//     final MmsParticipant = MmsParticipantFromJson(jsonString);

import 'package:simple_sms/src/android/models/model_helpers.dart';

import '../../../interfaces/models_interface.dart';
import '../enums/contact_enums.dart';
import '../enums/sms_mms_enums.dart';

class MmsParticipant implements ModelInterface {
  @override
  final int id;

  @override
  final Map<String, dynamic>? sourceMap;

  final CharSet? charset;
  final AndroidParticipantType? participantType;
  final String? address;
  final int? contactId;
  final String? sourceLabel;
  final int? msgId;

  MmsParticipant({
    required this.id,
    this.sourceMap,

    this.msgId,
    this.participantType,
    this.sourceLabel,
    this.charset,
    this.address,
    this.contactId,
  });

  static MmsParticipant fromJson(Map<String, dynamic> json) => MmsParticipant(
    address: json["address"],
    charset: json["charset"],
    contactId: json["contact_id"],
    id: json["_id"],
    msgId: json["msg_id"],
    sourceLabel: json["sourceLabel"],
    participantType: FieldHelper.enumFromValue(
      AndroidParticipantType.values,
      json["type"],
    ),
  );

  Map<String, dynamic> toJson() => {
    "_id": id,
    "address": address,
    "charset": charset,
    "contact_id": contactId,
    "msg_id": msgId,
    "sourceLabel": sourceLabel,
    "type": participantType?.value,
  };

  factory MmsParticipant.fromRaw(Map<String, dynamic> raw) {
    final participant = MmsParticipant(
      id: FieldHelper.asInt(raw['_id']) ?? FieldHelper.asInt(raw['id'])!,
      address: raw["address"]?.toString(),
      charset: FieldHelper.enumFromValue(CharSet.values, raw["charset"]),
      contactId: FieldHelper.asInt(raw["contact_id"]),
      msgId: FieldHelper.asInt(raw["msg_id"]),
      sourceLabel: raw["sourceLabel"],
      participantType:
          raw['type'] is AndroidParticipantType
              ? raw['type']
              : FieldHelper.enumFromValue(
                AndroidParticipantType.values,
                raw["type"],
              ),
    );

    return participant;
  }

  Map<String, dynamic> toRaw() => {
    "_id": id,
    "address": address,
    "charset": charset,
    "contact_id": contactId,
    "msg_id": msgId,
    "sourceLabel": sourceLabel,
    "type": participantType?.value,
  };
}
