// To parse this JSON data, do
//
//     final MmsParticipant = MmsParticipantFromJson(jsonString);

import 'package:flutter/foundation.dart';
import 'package:simple_query/simple_query.dart';
import 'package:simple_sms/src/android/models/model_helpers.dart';

import '../../../interfaces/models_interface.dart';
import '../enums/contact_enums.dart';
import '../enums/sms_mms_enums.dart';
import '../messages/mms.dart';
import 'contact.dart';
import 'contactables.dart';

class MmsParticipant implements ModelInterface {
  Future<AndroidContact?> get contact async {
    if (contactId == null) return null;
    try {
      final response = await SimpleQuery.instance.query(
        QueryRequest(
          contentUri: 'content://com.android.contacts/contacts',
          selection: '_id = ?',
          selectionArgs: [contactId!.toString()],
        ),
      );
      if (response.rows.isEmpty || response.rows.first == null) return null;
      final row = response.rows.first as Map<Object?, Object?>;
      return AndroidContact.fromRaw(
        Map<String, dynamic>.from(
          row.map((k, v) => MapEntry(k?.toString() ?? '', v)),
        ),
      );
    } catch (e, s) {
      debugPrint(e.toString());
      debugPrint(s.toString());
      return null;
    }
  }

  Future<Contactable?> get contactable async {
    if (address == null) return null;
    try {
      final response = await SimpleQuery.instance.query(
        QueryRequest(
          contentUri:
              'content://com.android.contacts/data/phones/filter/$address',
        ),
      );
      if (response.rows.isEmpty || response.rows.first == null) return null;
      final row = response.rows.first as Map<Object?, Object?>;
      return Contactable.fromRaw(
        Map<String, dynamic>.from(
          row.map((k, v) => MapEntry(k?.toString() ?? '', v)),
        ),
      );
    } catch (e, s) {
      debugPrint(e.toString());
      debugPrint(s.toString());
      return null;
    }
  }

  Future<Mms?> get message async {
    if (msgId == null) return null;
    try {
      final response = await SimpleQuery.instance.query(
        QueryRequest(
          contentUri: 'content://mms',
          selection: '_id = ?',
          selectionArgs: [msgId!.toString()],
        ),
      );
      if (response.rows.isEmpty || response.rows.first == null) return null;
      final row = response.rows.first as Map<Object?, Object?>;
      return Mms.fromRaw(
        Map<String, dynamic>.from(
          row.map((k, v) => MapEntry(k?.toString() ?? '', v)),
        ),
      );
    } catch (e, s) {
      debugPrint(e.toString());
      debugPrint(s.toString());
      return null;
    }
  }

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

    if (participant.participantType == null) {
      debugPrint("Here");
    }
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
