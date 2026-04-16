import 'package:simple_sms_native/src/android/models/model_helpers.dart';
import '../../../interfaces/models_interface.dart';
import '../enums/sms_mms_enums.dart';

/// A single part of a multipart MMS message — text body, image, video,
/// audio clip, or SMIL layout descriptor.
///
/// An [Mms] decomposes into one or more [MmsPart]s, each addressable by
/// its own [id] and grouped by [parentId] (matching `Mms.id`). Typical
/// messages contain:
///   * one `text/plain` part with the message body,
///   * one `application/smil` layout part,
///   * zero or more `image/*` / `video/*` / `audio/*` attachment parts.
///
/// For app consumption, the useful fields are [parentId] (which MMS
/// this belongs to), [contentType], [contentLocation] (typically a
/// `content://` URI an attachment can be resolved from),
/// [contentDisposition] (inline / attachment), and the textual payload
/// shortcuts when present. Raw provider metadata (transfer-encoding,
/// carrier reserved flags, etc.) flows through unchanged for round-trip
/// fidelity.
class MmsPart implements ModelInterface {
  @override
  final int id;

  @override
  final Map<String, dynamic>? sourceMap;

  final String parentId;
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
    this.parentId = '',
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
        charset: FieldHelper.enumFromValue(CharSet.values, raw["chset"]),
      contentDisposition: raw["cd"],
      contentId: raw["cid"],
      contentLocation: raw["cl"] ?? '',
      contentType:
          FieldHelper.enumFromValue(ContentType.values, raw["ct"]) ??
          ContentType.textPlain,
      contentTypeSub: raw["ctt_s"],
      contentTypeTransferEncoding: raw["ctt_t"],
      dataLocation: _parseUri(raw["dataLocation"]),
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
    "dataLocation": dataLocation?.toString(),
    "_id": id,
    "cd": contentDisposition,
    "chset": charset?.value,
    "cid": contentId,
    "cl": contentLocation,
    "ct": contentType.value,
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

  /// Parse a Uri from various input types (Uri, String, or null)
  static Uri? _parseUri(dynamic value) {
    if (value == null) return null;
    if (value is Uri) return value;
    if (value is String && value.isNotEmpty) return Uri.tryParse(value);
    return null;
  }
}
