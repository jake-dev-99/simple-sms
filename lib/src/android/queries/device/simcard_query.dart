import '../../models/device/simcard.dart';
import '../../models/enums/device_enums.dart';
import '../../../interfaces/query_interfaces.dart';

class SimCardFields {
  static const sourceMap = 'sourceMap';
  static const isNetworkRoaming = 'isNetworkRoaming';
  static const slot = 'slot';
  static const state = 'state';
  static const operatorName = 'operatorName';
  static const countryIso = 'countryIso';
  static const serialNumber = 'serialNumber';
  static const carrierName = 'carrierName';
  static const mcc = 'mcc';
  static const mnc = 'mnc';
  static const error = 'error';
  static const phoneNumber = 'phoneNumber';
  static const imei = 'imei';
  static const type = 'type';
  static const externalId = 'externalId';
}

class SimCardQuery with SimCardBuilder {
  const SimCardQuery();

  Future<List<AndroidSimCard>> fetch() async {
    final List<Map<String, dynamic>> simCards = await super.getSimCards();
    return simCards.map((sim) => AndroidSimCard.fromJson(sim)).toList();
  }
}

extension SimCardJson on AndroidSimCard {
  Map<String, dynamic> toJson(AndroidSimCard simCard) => {
    SimCardFields.isNetworkRoaming: simCard.isNetworkRoaming,
    SimCardFields.slot: simCard.slot,
    SimCardFields.state: simCard.state,
    SimCardFields.operatorName: simCard.operatorName,
    SimCardFields.countryIso: simCard.countryIso,
    SimCardFields.serialNumber: simCard.serialNumber,
    SimCardFields.carrierName: simCard.carrierName,
    SimCardFields.mcc: simCard.mcc,
    SimCardFields.mnc: simCard.mnc,
    SimCardFields.error: simCard.error,
    SimCardFields.phoneNumber: simCard.phoneNumber,
    SimCardFields.imei: simCard.imei,
    SimCardFields.externalId: simCard.externalId,
  };

  AndroidSimCard fromJson(Map<String, dynamic> json) {
    return AndroidSimCard(
      isNetworkRoaming: json[SimCardFields.isNetworkRoaming] as bool? ?? false,
      slot: json[SimCardFields.slot] as int? ?? 0,
      state: _parseAndroidSimCardState(json[SimCardFields.state]?.toString()),
      operatorName: json[SimCardFields.operatorName]?.toString().trim() ?? '',
      countryIso: json[SimCardFields.countryIso]?.toString() ?? '',
      serialNumber: json[SimCardFields.serialNumber]?.toString() ?? '',
      carrierName: json[SimCardFields.carrierName]?.toString().trim() ?? '',
      mcc: json[SimCardFields.mcc]?.toString() ?? '',
      mnc: json[SimCardFields.mnc]?.toString() ?? '',
      error: json[SimCardFields.error]?.toString() ?? '',
      phoneNumber: json[SimCardFields.phoneNumber]?.toString() ?? '',
      imei: json[SimCardFields.imei]?.toString() ?? '',
      sourceMap: json,
      type: json[SimCardFields.type]?.toString() ?? '',
      externalId: json[SimCardFields.externalId]?.toString() ?? '-1',
    );
  }

  AndroidSimCardState _parseAndroidSimCardState(String? stateStr) {
    if (stateStr == null) return AndroidSimCardState.unknown;

    switch (stateStr.toLowerCase()) {
      case 'ready':
        return AndroidSimCardState.ready;
      case 'absent':
        return AndroidSimCardState.absent;
      case 'pin_required':
        return AndroidSimCardState.pinRequired;
      case 'puk_required':
        return AndroidSimCardState.pukRequired;
      case 'network_locked':
        return AndroidSimCardState.networkLocked;
      case 'card_io_error':
        return AndroidSimCardState.cardIoError;
      case 'restricted':
        return AndroidSimCardState.cardRestricted;
      default:
        return AndroidSimCardState.unknown;
    }
  }
}
