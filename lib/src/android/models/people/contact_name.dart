import 'package:simple_sms/src/android/models/model_helpers.dart';
import '../../../interfaces/models_interface.dart';

/// Android contact name model (StructuredName)
///
/// - DISPLAY_NAME (data1)
/// - GIVEN_NAME (data2)
/// - FAMILY_NAME (data3)
/// - PREFIX (data4)
/// - MIDDLE_NAME (data5)
/// - SUFFIX (data6)
/// - PHONETIC_GIVEN_NAME (data7)
/// - PHONETIC_MIDDLE_NAME (data8)
/// - PHONETIC_FAMILY_NAME (data9)
class AndroidContactName implements ModelInterface {
  @override
  final int id;

  @override
  final Map<String, dynamic>? sourceMap;

  // App-style (camelCase)
  final String? displayName;
  final String? givenName;
  final String? familyName;
  final String? prefix;
  final String? middleName;
  final String? suffix;
  final String? phoneticGivenName;
  final String? phoneticMiddleName;
  final String? phoneticFamilyName;

  const AndroidContactName({
    required this.id,
    this.sourceMap,
    this.displayName,
    this.givenName,
    this.familyName,
    this.prefix,
    this.middleName,
    this.suffix,
    this.phoneticGivenName,
    this.phoneticMiddleName,
    this.phoneticFamilyName,
  });

  factory AndroidContactName.fromJson(Map<String, dynamic> json) =>
      AndroidContactName(
        id: FieldHelper.asInt(json['id'])!,
        sourceMap: json,
        displayName: json['displayName'],
        givenName: json['givenName'],
        familyName: json['familyName'],
        prefix: json['prefix'],
        middleName: json['middleName'],
        suffix: json['suffix'],
        phoneticGivenName: json['phoneticGivenName'],
        phoneticMiddleName: json['phoneticMiddleName'],
        phoneticFamilyName: json['phoneticFamilyName'],
      );

  Map<String, dynamic> toJson() => {
    'id': id,
    'displayName': displayName,
    'givenName': givenName,
    'familyName': familyName,
    'prefix': prefix,
    'middleName': middleName,
    'suffix': suffix,
    'phoneticGivenName': phoneticGivenName,
    'phoneticMiddleName': phoneticMiddleName,
    'phoneticFamilyName': phoneticFamilyName,
  };

  // == Android/DB-style (raw, snake_case) ==
  factory AndroidContactName.fromRaw(Map<String, dynamic> raw) =>
      AndroidContactName(
        id: FieldHelper.asInt(raw['_id']) ?? FieldHelper.asInt(raw['id'])!,
        sourceMap: raw,
        displayName: raw['data1'],
        givenName: raw['data2'],
        familyName: raw['data3'],
        prefix: raw['data4'],
        middleName: raw['data5'],
        suffix: raw['data6'],
        phoneticGivenName: raw['data7'],
        phoneticMiddleName: raw['data8'],
        phoneticFamilyName: raw['data9'],
      );

  Map<String, dynamic> toRaw() => {
    '_id': id,
    'data1': displayName,
    'data2': givenName,
    'data3': familyName,
    'data4': prefix,
    'data5': middleName,
    'data6': suffix,
    'data7': phoneticGivenName,
    'data8': phoneticMiddleName,
    'data9': phoneticFamilyName,
  };

  AndroidContactName copyWith({
    int? id,
    Map<String, dynamic>? sourceMap,
    String? displayName,
    String? givenName,
    String? familyName,
    String? prefix,
    String? middleName,
    String? suffix,
    String? phoneticGivenName,
    String? phoneticMiddleName,
    String? phoneticFamilyName,
  }) {
    return AndroidContactName(
      id: id ?? this.id,
      sourceMap: sourceMap ?? this.sourceMap,
      displayName: displayName ?? this.displayName,
      givenName: givenName ?? this.givenName,
      familyName: familyName ?? this.familyName,
      prefix: prefix ?? this.prefix,
      middleName: middleName ?? this.middleName,
      suffix: suffix ?? this.suffix,
      phoneticGivenName: phoneticGivenName ?? this.phoneticGivenName,
      phoneticMiddleName: phoneticMiddleName ?? this.phoneticMiddleName,
      phoneticFamilyName: phoneticFamilyName ?? this.phoneticFamilyName,
    );
  }
}
