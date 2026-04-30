import 'dart:typed_data';

/// Helper functions for value type conversion
///
/// These functions safely convert different raw value types to Dart types
/// to handle the variations in how data might be represented in the Android system
class FieldHelper {
  // --- Helper Functions ---
  static bool? asBool(dynamic value) {
    if (value == null) return null;
    if (value is bool) return value;
    if (value is int) return value == 1;
    if (value is String) return value == "1" || value.toLowerCase() == "true";
    return null;
  }

  static int? asInt(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is Uint8List) {
      throw Exception('Uint8List is not supported - $value');
    }
    // Android ContentProviders commonly surface numeric columns as Strings
    // (the `sub_id` / `thread_id` / `seq` fields show up stringified on some
    // OEMs). `int.tryParse` handles both decimal and whitespace-trimmed
    // numerics, and returns null for non-numeric junk — which matches our
    // nullable return contract.
    if (value is String) return int.tryParse(value.trim());
    // `double.nan.toInt()` and `double.infinity.toInt()` both throw
    // `UnsupportedError`, which breaks this helper's "null for bad input"
    // contract. Rare from a ContentProvider cursor, but `fromJson` paths
    // can deserialize non-finite doubles from server payloads, so guard.
    if (value is double) return value.isFinite ? value.toInt() : null;
    if (value is bool) return value ? 1 : 0;
    return null;
  }

  static Uint8List? asUInt8List(dynamic value) {
    if (value == null) return null;
    if (value is Uint8List) return value;
    return null;
  }

  static int? boolToInt(bool? v) => v == null ? null : (v ? 1 : 0);

  /// Threshold to distinguish seconds from milliseconds timestamps.
  /// Timestamps below this value (roughly year 2001 in millis) are treated
  /// as seconds and multiplied by 1000. Android ContentProviders inconsistently
  /// store timestamps as seconds (MMS) or milliseconds (SMS).
  static const int _secondsVsMillisThreshold = 1000000000000;

  /// Maximum `millisecondsSinceEpoch` accepted by `DateTime` (≈ year 275760).
  /// Android ContentProviders occasionally surface sentinel values like
  /// `Long.MAX_VALUE` (9223372036854775807) in columns such as `exp`/`d_tm`
  /// to mean "unset"; these blow past Dart's DateTime range and throw
  /// `RangeError` from `DateTime.fromMillisecondsSinceEpoch`. Clamp to null
  /// so an unset sentinel reads as absent instead of crashing the parse.
  static const int _maxDartMillis = 8640000000000000;

  static DateTime? asDateTime(dynamic value) {
    if (value == null) return null;
    if (value is DateTime) return value;
    if (value is String) {
      final trimmed = value.trim();
      if (trimmed.isEmpty) return null;
      // Try ISO-8601 first (how app-layer JSON round-trips carry dates),
      // then fall back to stringified-int epochs (how some ContentProvider
      // columns surface — `toRaw()` round-trips pre-fix, OEM quirks, etc.).
      final iso = DateTime.tryParse(trimmed);
      if (iso != null) return iso;
      final asInt = int.tryParse(trimmed);
      if (asInt != null) return asDateTime(asInt);
      return null;
    }
    if (value is int) {
      // Bail out on pathological sentinels before multiplying by 1000 —
      // a huge negative seconds value wraps on 64-bit Dart native and
      // could produce an in-range `millis` that looks like a valid
      // (garbage) date. We only accept seconds whose millisecond-
      // equivalent fits Dart's DateTime range.
      if (value.abs() > _maxDartMillis) return null;
      final millis =
          value.abs() < _secondsVsMillisThreshold ? value * 1000 : value;
      if (millis.abs() > _maxDartMillis) return null;
      return DateTime.fromMillisecondsSinceEpoch(millis);
    }
    return null;
  }

  /// Coerces an empty String to null, preserving any other String value.
  /// Useful for Samsung provider columns (`retr_txt_cs`, `st`, `d_tm`, …)
  /// that surface `""` as a "unset" sentinel rather than a real absence,
  /// and would otherwise leak through typed String? fields as
  /// empty-string markers.
  static String? emptyToNull(dynamic value) {
    if (value is String && value.isEmpty) return null;
    return value as String?;
  }

  /// Reads a primary-key int from a raw provider row, trying `_id` first
  /// (the BaseColumns PK every Android provider uses) and falling back to
  /// `id` for the legacy test-fixture shape. Defaults to 0 when both keys
  /// are missing or unparseable, matching the "unparseable row" semantics
  /// used elsewhere in these parsers rather than throwing at construction.
  static int primaryKey(Map<String, dynamic> raw) =>
      asInt(raw['_id']) ?? asInt(raw['id']) ?? 0;

  /// Resolve an enum value from a raw provider field, returning `null`
  /// for **either** a missing field OR an unrecognized value.
  ///
  /// Use this **only when null genuinely means "field absent / not set"**
  /// — e.g. optional MMS headers like `priority`, `replyType`,
  /// `readStatus`. Caller cannot distinguish "field absent in row" from
  /// "field present but value isn't in the enum"; that ambiguity has
  /// historically masked real provider-schema mismatches (Samsung OEM
  /// extensions, new AOSP enum values added in later API levels).
  ///
  /// For required fields where unrecognized input is a real bug, use
  /// [enumFromValueOrThrow] instead so the failure surfaces to
  /// crashlytics with the offending raw value named.
  static T? enumFromValueOrNull<T>(Iterable<T> values, dynamic raw) =>
      raw == null
          ? null
          : values.cast<T?>().firstWhere(
            (v) => (v as dynamic).value == raw,
            orElse: () => null,
          );

  /// Resolve an enum value from a raw provider field, throwing
  /// [StateError] when the field is **present but unrecognized**.
  /// Returns `null` only when the field is genuinely absent
  /// (`raw == null`) — which is still an unsafe state for required
  /// fields, but distinguishable.
  ///
  /// Use this for fields where an unrecognized value is a real bug —
  /// e.g. `MmsMessageType` (drives inbound vs outbound classification),
  /// `MessageBox`, `SmsMessageType`, `ContentType`. Silent fallbacks
  /// in those positions historically caused user-visible message-loss
  /// bugs (HEIC photos rendering as empty messages because
  /// `image/heic` silently became `text/plain`).
  ///
  /// The error message includes [fieldName] and the [raw] input so
  /// the offender shows up in crashlytics with enough context to fix
  /// the root cause (typically: add a missing enum entry, or
  /// understand a new OEM-specific schema).
  static T? enumFromValueOrThrow<T>(
    Iterable<T> values,
    dynamic raw, {
    required String fieldName,
  }) {
    if (raw == null) return null;
    for (final v in values) {
      if ((v as dynamic).value == raw) return v;
    }
    throw StateError(
      'Unknown $fieldName value: $raw (type ${raw.runtimeType}). '
      'Add an enum entry for this value or fix the provider mapping. '
      'Silent fallbacks in this position have historically caused '
      'user-visible message-loss bugs.',
    );
  }

  /// Backwards-compat alias for [enumFromValueOrNull]. Prefer the
  /// explicit name in new code so reviewers can immediately see whether
  /// the callsite is intentionally tolerating unrecognized values.
  ///
  /// Existing callsites that have already been audited and confirmed
  /// safe under null-on-unrecognized may keep using this alias. New
  /// callsites should not use it.
  @Deprecated(
    'Use enumFromValueOrNull (for optional fields) or '
    'enumFromValueOrThrow (for required fields) explicitly.',
  )
  static T? enumFromValue<T>(Iterable<T> values, dynamic raw) =>
      enumFromValueOrNull(values, raw);
}
