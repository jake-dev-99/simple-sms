import 'package:simple_sms/src/android/models/model_helpers.dart';
import '../../../interfaces/models_interface.dart';
import '../enums/sms_mms_enums.dart';

class MmsPart implements ModelInterface {
  @override
  final int id;

  @override
  Map<String, dynamic>? sourceMap;

  String parentId = '';
  final String contentLocation;
  final ContentType contentType;

  final CharSet? charset;
  final String? contentDisposition;
  final String? contentId;
  final String? contentTypeSub;
  final String? contentTypeTransferEncoding;
  final String? fileName;
  final String? name;
  final String? sourceLabel;
  final String? text;
  final Uri? dataLocation;
  final int? messageId;
  final int? sefType;
  final int? sequence;

  bool get isText => contentType.value.contains("text");
  bool get isSmil => contentType.value.contains("smil");

  MmsPart({
    required this.id,
    required this.contentLocation,
    required this.contentType,
    this.sourceMap,
    this.charset,
    this.contentDisposition,
    this.contentId,
    this.contentTypeSub,
    this.contentTypeTransferEncoding,
    this.dataLocation,
    this.fileName,
    this.messageId,
    this.name,
    this.sefType,
    this.sequence,
    this.sourceLabel,
    this.text,
  });

  factory MmsPart.fromJson(Map<String, dynamic> json) => MmsPart(
    id: FieldHelper.asInt(json["id"]) ?? 0,
    sourceMap: json,
    // binary: json["_data"],
    charset: FieldHelper.enumFromValue(CharSet.values, json["charset"]),
    contentDisposition: json["contentDisposition"],
    contentId: json["contentId"],
    contentLocation: json["contentLocation"],
    contentType: json["contentType"],
    contentTypeSub: json["contentTypeSub"],
    contentTypeTransferEncoding: json["contentTypeTransferEncoding"],
    dataLocation: Uri.tryParse(json["dataLocation"]),
    fileName: json["fileName"],
    messageId: json["messageId"],
    name: json["name"],
    sefType: json["sefType"],
    sequence: json["sequence"],
    sourceLabel: json["sourceLabel"],
    text: json["text"],
  );

  Map<String, dynamic> toJson() => {
    "charset": charset,
    "contentDisposition": contentDisposition,
    "contentId": contentId,
    "contentLocation": contentLocation,
    "contentType": contentType,
    "contentTypeSub": contentTypeSub,
    "contentTypeTransferEncoding": contentTypeTransferEncoding,
    "dataLocation": dataLocation,
    "fileName": fileName,
    "id": id,
    "messageId": messageId,
    "name": name,
    "sefType": sefType,
    "sequence": sequence,
    "sourceLabel": sourceLabel,
    "text": text,
  };

  factory MmsPart.fromRaw(Map<String, dynamic> raw) {
    MmsPart part = MmsPart(
      id: FieldHelper.asInt(raw['_id']) ?? FieldHelper.asInt(raw['id'])!,
      sourceMap: raw,
      // binary: json["_data"],
      charset: FieldHelper.enumFromValue(CharSet.values, raw["chset"]),
      contentDisposition: raw["cd"],
      contentId: raw["cid"],
      contentLocation: raw["cl"] ?? '',
      contentType:
          FieldHelper.enumFromValue(ContentType.values, raw["ct"]) ??
          ContentType.textPlain,
      contentTypeSub: raw["ctt_s"],
      contentTypeTransferEncoding: raw["ctt_t"],
      dataLocation: Uri.tryParse(raw["dataLocation"] ?? ''),
      fileName: raw["fn"],
      messageId: raw["mid"],
      name: raw["name"],
      sefType: raw["sef_type"],
      sequence: raw["seq"],
      sourceLabel: raw["sourceLabel"],
      text: raw["text"],
    );

    return part;
  }

  Map<String, dynamic> toRaw() => {
    "dataLocation": dataLocation,
    "_id": id,
    "cd": contentDisposition,
    "chset": charset,
    "cid": contentId,
    "cl": contentLocation,
    "ct": contentType,
    "ctt_s": contentTypeSub,
    "ctt_t": contentTypeTransferEncoding,
    "fn": fileName,
    "mid": messageId,
    "name": name,
    "sef_type": sefType,
    "seq": sequence,
    "sourceLabel": sourceLabel,
    "text": text,
  };
}
