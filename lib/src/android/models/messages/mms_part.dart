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

  // --- Samsung OEM extensions (present on Samsung Android 16; absent on
  //     stock AOSP). Pass-through fields preserved for round-trip fidelity.

  /// Samsung column `sub_id` — subscription / SIM slot id. `-1` = unknown.
  final int? subId;

  /// Samsung column `coupon_data` — JSON blob of Samsung-Messages coupon
  /// metadata; empty on messages without a coupon.
  final String? couponData;

  /// Samsung column `coupon_status` — int flag for coupon state.
  final int? couponStatus;

  /// Samsung column `decorate_bubble_value` — chat-bubble styling payload.
  final String? decorateBubbleValue;

  /// Samsung column `smart_suggestion_classification` — ML classification
  /// flag used by Samsung's suggested-reply feature.
  final int? smartSuggestionClassification;

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
    // Samsung extensions.
    this.subId,
    this.couponData,
    this.couponStatus,
    this.decorateBubbleValue,
    this.smartSuggestionClassification,
  });

  factory MmsPart.fromJson(Map<String, dynamic> json) => MmsPart(
    id: FieldHelper.asInt(json["id"]) ?? 0,
    sourceMap: json,
    charset: FieldHelper.enumFromValue(
      CharSet.values,
      FieldHelper.asInt(json["charset"]),
    ),
    contentDisposition: json["contentDisposition"],
    contentId: json["contentId"],
    contentLocation: json["contentLocation"] ?? '',
    // `contentType` is required (non-nullable); fall back to textPlain
    // so a malformed cache row doesn't throw at the constructor. The
    // server side is expected to emit the enum `.value` (a MIME string
    // like "text/plain") so the lookup works on the forward path.
    contentType:
        FieldHelper.enumFromValue(ContentType.values, json["contentType"]) ??
            ContentType.textPlain,
    contentTypeSub: json["contentTypeSub"],
    contentTypeTransferEncoding: json["contentTypeTransferEncoding"],
    // `Uri.tryParse` used to throw here when the key was absent (its first
    // parameter is non-nullable String); route through `_parseUri` so
    // `null` / empty round-trips cleanly.
    dataLocation: _parseUri(json["dataLocation"]),
    fileName: json["fileName"],
    messageId: FieldHelper.asInt(json["messageId"]),
    name: json["name"],
    sefType: FieldHelper.asInt(json["sefType"]),
    sequence: FieldHelper.asInt(json["sequence"]),
    sourceLabel: json["sourceLabel"],
    text: json["text"],
    // Samsung extensions.
    subId: FieldHelper.asInt(json["subId"]),
    couponData: json["couponData"],
    couponStatus: FieldHelper.asInt(json["couponStatus"]),
    decorateBubbleValue: json["decorateBubbleValue"]?.toString(),
    smartSuggestionClassification:
        FieldHelper.asInt(json["smartSuggestionClassification"]),
  );

  Map<String, dynamic> toJson() => {
    // Enums serialize as their underlying `.value` (int for CharSet,
    // String for ContentType) so `jsonEncode(mms.toJson())` works —
    // previously these emitted Enum instances directly, which isn't
    // JSON-encodable and blew up at encode time.
    "charset": charset?.value,
    "contentDisposition": contentDisposition,
    "contentId": contentId,
    "contentLocation": contentLocation,
    "contentType": contentType.value,
    "contentTypeSub": contentTypeSub,
    "contentTypeTransferEncoding": contentTypeTransferEncoding,
    "dataLocation": dataLocation?.toString(),
    "fileName": fileName,
    "id": id,
    "messageId": messageId,
    "name": name,
    "sefType": sefType,
    "sequence": sequence,
    "sourceLabel": sourceLabel,
    "text": text,
    // Samsung extensions.
    "subId": subId,
    "couponData": couponData,
    "couponStatus": couponStatus,
    "decorateBubbleValue": decorateBubbleValue,
    "smartSuggestionClassification": smartSuggestionClassification,
  };

  factory MmsPart.fromRaw(Map<String, dynamic> raw) {
    // `Telephony.Mms.Part` primary key is `_id` (BaseColumns). There is no
    // `id` column; the old fallback `?? raw['id']!` was dead code that would
    // throw on any row where the `_id` coercion returned null. A 0 default
    // matches the other "unparseable row" behaviours elsewhere in the parser.
    MmsPart part = MmsPart(
      id: FieldHelper.asInt(raw['_id']) ?? 0,
      sourceMap: raw,
      charset: FieldHelper.enumFromValue(
        CharSet.values,
        FieldHelper.asInt(raw["chset"]),
      ),
      contentDisposition: raw["cd"],
      contentId: raw["cid"],
      contentLocation: raw["cl"] ?? '',
      contentType:
          FieldHelper.enumFromValue(ContentType.values, raw["ct"]) ??
          ContentType.textPlain,
      contentTypeSub: raw["ctt_s"],
      contentTypeTransferEncoding: raw["ctt_t"],
      // Real column on `content://mms/part` is `_data` (the file path to the
      // part blob). The previous `raw["dataLocation"]` key never matches any
      // provider row, so the URI was silently null and attachment extraction
      // fell through to the `partId`-based plugin fallback. Prospector dump
      // confirms `_data` is the authoritative column.
      dataLocation: _parseUri(raw["_data"]),
      fileName: raw["fn"],
      messageId: FieldHelper.asInt(raw["mid"]),
      name: raw["name"],
      sefType: FieldHelper.asInt(raw["sef_type"]),
      sequence: FieldHelper.asInt(raw["seq"]),
      sourceLabel: raw["sourceLabel"],
      text: raw["text"],
      // Samsung extensions, pass-through for round-trip fidelity.
      subId: FieldHelper.asInt(raw["sub_id"]),
      couponData: raw["coupon_data"],
      couponStatus: FieldHelper.asInt(raw["coupon_status"]),
      decorateBubbleValue: raw["decorate_bubble_value"]?.toString(),
      smartSuggestionClassification:
          FieldHelper.asInt(raw["smart_suggestion_classification"]),
    );

    return part;
  }

  Map<String, dynamic> toRaw() => {
    "_data": dataLocation?.toString(),
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
    // Samsung extensions.
    "sub_id": subId,
    "coupon_data": couponData,
    "coupon_status": couponStatus,
    "decorate_bubble_value": decorateBubbleValue,
    "smart_suggestion_classification": smartSuggestionClassification,
  };

  /// Parse a Uri from various input types (Uri, String, or null)
  static Uri? _parseUri(dynamic value) {
    if (value == null) return null;
    if (value is Uri) return value;
    if (value is String && value.isNotEmpty) return Uri.tryParse(value);
    return null;
  }
}
