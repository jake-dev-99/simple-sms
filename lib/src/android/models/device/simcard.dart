import 'package:simple_sms/src/android/models/model_helpers.dart';

import '../../../interfaces/models_interface.dart';
import '../enums/device_enums.dart';

class AndroidSimCard implements ModelInterface {
  AndroidSimCard({
    this.id = 0,
    this.parentId = '',
    this.sourceMap,
    this.type,
    this.isNetworkRoaming,
    this.slot,
    this.state,
    this.operatorName,
    this.countryIso,
    this.serialNumber,
    this.carrierName,
    this.mcc, //  Mobile Country Code
    this.mnc, //  Mobile Network Code
    this.error,
    this.phoneNumber,
    this.imei,
    this.externalId,
  });

  @override
  int id = 0;
  @override
  Map<String, dynamic>? sourceMap;

  String parentId = '';
  String? type;
  bool? isNetworkRoaming;
  int? slot;
  AndroidSimCardState? state;
  String? operatorName;
  String? countryIso;
  String? serialNumber;
  String? carrierName;
  String? mcc; //  Mobile Country Code
  String? mnc; //  Mobile Network Code
  String? error;
  String? phoneNumber;
  String? imei;
  String? externalId;

  Map<String, dynamic> toJson() => {
    'id': id,
    'parentId': parentId,
    'sourceMap': sourceMap,
    'type': type,
    'isNetworkRoaming': isNetworkRoaming,
    'slot': slot,
    'state': state?.name,
    'operatorName': operatorName,
    'countryIso': countryIso,
    'serialNumber': serialNumber,
    'carrierName': carrierName,
    'mcc': mcc,
    'mnc': mnc,
    'error': error,
    'phoneNumber': phoneNumber,
    'imei': imei,
    'externalId': externalId,
  };

  factory AndroidSimCard.fromJson(Map<String, dynamic> json) => AndroidSimCard(
    id: FieldHelper.asInt(json['id']) ?? 0,
    parentId: json['phoneNumber']?.toString() ?? '',
    sourceMap: json['sourceMap'],
    type: json['type'],
    isNetworkRoaming: json['isNetworkRoaming'],
    slot: json['slot'],
    state:
        json['state'] != null
            ? AndroidSimCardState.values.byName(
              json['state']?.toString().toLowerCase() ?? '',
            )
            : null,
    operatorName: json['operatorName'],
    countryIso: json['countryIso'],
    serialNumber: json['serialNumber'],
    carrierName: json['carrierName'],
    mcc: json['mcc'],
    mnc: json['mnc'],
    error: json['error'],
    phoneNumber: json['phoneNumber'],
    imei: json['imei'],
    externalId: json['externalId']?.toString(),
  );

  AndroidSimCard copyWith({
    String? id,
    String? parentId,
    Map<String, dynamic>? sourceMap,
    String? type,
    bool? isNetworkRoaming,
    int? slot,
    AndroidSimCardState? state,
    String? operatorName,
    String? countryIso,
    String? serialNumber,
    String? carrierName,
    String? mcc,
    String? mnc,
    String? error,
    String? phoneNumber,
    String? imei,
    String? externalId,
  }) => AndroidSimCard(
    id: id != null ? int.tryParse(id) ?? this.id : this.id,
    parentId: parentId ?? this.parentId,
    sourceMap: sourceMap ?? this.sourceMap,
    type: type ?? this.type,
    isNetworkRoaming: isNetworkRoaming ?? this.isNetworkRoaming,
    slot: slot ?? this.slot,
    state: state ?? this.state,
    operatorName: operatorName ?? this.operatorName,
    countryIso: countryIso ?? this.countryIso,
    serialNumber: serialNumber ?? this.serialNumber,
    carrierName: carrierName ?? this.carrierName,
    mcc: mcc ?? this.mcc,
    mnc: mnc ?? this.mnc,
    error: error ?? this.error,
    phoneNumber: phoneNumber ?? this.phoneNumber,
    imei: imei ?? this.imei,
    externalId: externalId ?? this.externalId,
  );
}
