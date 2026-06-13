// To parse this JSON data, do
//
//     final MmsParticipant = MmsParticipantFromJson(jsonString);

import 'package:simple_sms_native/src/android/models/model_helpers.dart';

import '../enums/contact_enums.dart';
import '../enums/sms_mms_enums.dart';
import 'message_participant.dart';

class MmsParticipant implements MessageParticipant {
  final int id;

  final Map<String, dynamic>? sourceMap;

  final CharSet? charset;
  final AndroidParticipantType? participantType;
  @override
  final String? address;
  @override
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

  // ---- MessageParticipant (UNFY-165) ----

  /// Maps the MMS WAP-PDU [participantType] onto the source-neutral
  /// [ParticipantRole]. A `null` participant type (only reachable on a
  /// hand-constructed instance — the parsers require it) surfaces as
  /// [ParticipantRole.unknown] rather than a silent default.
  @override
  ParticipantRole get role => switch (participantType) {
        AndroidParticipantType.sender => ParticipantRole.sender,
        AndroidParticipantType.recipient => ParticipantRole.to,
        AndroidParticipantType.recipient_cc => ParticipantRole.cc,
        AndroidParticipantType.recipient_bcc => ParticipantRole.bcc,
        null => ParticipantRole.unknown,
      };

  /// Always `null` on the raw provider-parse path: the `addr` table carries
  /// [address] + [contactId] but no resolved name — host enrichment resolves
  /// the display name from those.
  @override
  String? get displayName => null;

  static MmsParticipant fromJson(Map<String, dynamic> json) => MmsParticipant(
    address: json["address"],
    charset: FieldHelper.enumFromValueOrNull(
      CharSet.values,
      FieldHelper.asInt(json["charset"]),
    ),
    contactId: FieldHelper.asInt(json["contact_id"]),
    id: FieldHelper.primaryKey(json),
    msgId: FieldHelper.asInt(json["msg_id"]),
    sourceLabel: json["sourceLabel"],
    // REQUIRED — drives FROM/TO/CC/BCC classification of MMS
    // recipients; the inbound persister depends on this to identify
    // sender vs recipients in group MMS.
    participantType: FieldHelper.enumFromValueOrThrow(
      AndroidParticipantType.values,
      FieldHelper.asInt(json["type"]),
      fieldName: 'MmsParticipant.participantType',
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
      charset: FieldHelper.enumFromValueOrNull(
        CharSet.values,
        FieldHelper.asInt(raw["charset"]),
      ),
      contactId: FieldHelper.asInt(raw["contact_id"]),
      msgId: FieldHelper.asInt(raw["msg_id"]),
      sourceLabel: raw["sourceLabel"],
      // REQUIRED — see fromJson comment.
      participantType: raw['type'] is AndroidParticipantType
          ? raw['type'] as AndroidParticipantType
          : FieldHelper.enumFromValueOrThrow(
              AndroidParticipantType.values,
              FieldHelper.asInt(raw["type"]),
              fieldName: 'MmsParticipant.participantType',
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
