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
    if (value == null) {
      return null;
    }
    if (value is String) {
      return null;
    }
    if (value is Uint8List) {
      throw Exception('Uint8List is not supported - $value');
    }
    if (value is int) {
      return value;
    } else {
      return int.tryParse(value);
    }
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

  static DateTime? asDateTime(dynamic value) {
    if (value == null) return null;
    if (value is DateTime) return value;
    if (value is String) return DateTime.tryParse(value);
    if (value is int) {
      return value < _secondsVsMillisThreshold
          ? DateTime.fromMillisecondsSinceEpoch(value * 1000)
          : DateTime.fromMillisecondsSinceEpoch(value);
    }
    return null;
  }

  static T? enumFromValue<T>(Iterable<T> values, dynamic raw) =>
      raw == null
          ? null
          : values.cast<T?>().firstWhere(
            (v) => (v as dynamic).value == raw,
            orElse: () => null,
          );
}
