import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:simple_sms/src/android/models/model_helpers.dart';
import '../../../interfaces/models_interface.dart';
import '../../queries/people/contacts_query.dart';
import '../enums/contact_enums.dart';
import 'contact.dart';

class Contactable implements ModelInterface {
  Future<AndroidContact?> get contact async {
    try {
      return (await ContactsQuery(lookupKey: contactId.toString()).fetch())
          .first;
    } catch (e, s) {
      debugPrint(e.toString());
      debugPrint(s.toString());
      return null;
    }
  }

  @override
  int id;
  @override
  Map<String, dynamic>? sourceMap;

  String parentId;
  String accountName;
  String accountType;
  String accountTypeAndDataSet;
  String backupId;
  int carrierPresence;
  String chatCapability;
  String contactChatCapability;
  int contactId;
  int contactLastUpdatedTimestamp;
  String contactPresence;
  String contactStatus;
  String contactStatusIcon;
  String contactStatusLabel;
  String contactStatusResPackage;
  String contactStatusTs;
  int creationTime;
  String customRingtone;

  // Primary value fields (varies by mimetype)

  String
  value; // data1, meaning depends on mimetype (phone number, email, formatted address, etc.)
  int type; // data2, type of data (e.g. home, work).
  String label; // data3, custom label, if TYPE_CUSTOM.
  String normalized; // data4, normalized value (eg. E164 phone).
  bool isPrimary; // data5, is this the primary value for that mimetype.
  bool isSuperPrimary; // data6, is this the super primary value.
  String auxData; // data7, auxiliary data (rarely used, eg. SIP address).
  String metaData; // data8, metadata (rarely used).
  String extraData; // data9, extra data.
  String alternateValue; // data10, alternate value representation.
  String contextData; // data11, context specific, rarely used.
  String contextType; // data12, context type, rarely used.
  String neighborhood; // data13, for postal addresses (neighborhood).
  String emailDisplayName; // data14, for emails (display name).
  Uint8List? blob; // data15, reserved for binary data like photo thumbnails.

  String dataSet;
  String dataSync1;
  String dataSync2;
  String dataSync3;
  String dataSync4;
  int dataVersion;
  int dirty;
  String displayName;
  String displayNameAlt;
  String displayNameReverse;
  DisplayNameSource? displayNameSource;
  String groupSourceid;
  bool hasPhoneNumber;
  String hashId;
  bool inDefaultDirectory;
  bool inVisibleGroup;
  bool isPrivate;
  bool isSim;
  int lastTimeContacted;
  int lastTimeUsed;
  String lookup;
  String mimetype;
  String mode;
  int nameRawContactId;
  int phonebookBucket;
  int phonebookBucketAlt;
  String phonebookLabel;
  String phonebookLabelAlt;
  String phoneticName;
  String phoneticNameStyle;
  int photoFileId;
  int photoId;
  String photoThumbUri;
  String photoUri;
  bool pinned;
  String preferredPhoneAccountComponentName;
  String preferredPhoneAccountId;
  int rawContactId;
  bool rawContactIsUserProfile;
  String resPackage;
  String secCallBackground;
  String secCustomAlert;
  String secCustomVibration;
  String secLed;
  String secPreferredSim;
  String secPreferredVideoCallAccountId;
  String secPreferredVideoCallAccountName;
  bool sendToVoicemail;
  String sortKey;
  String sortKeyAlt;
  String sourceid;
  bool starred;
  String status;
  String statusIcon;
  String statusLabel;
  String statusResPackage;
  String statusTs;
  int timesContacted;
  int timesUsed;
  int version;

  Contactable({
    this.id = -1,
    this.sourceMap,
    this.parentId = '',
    this.accountName = '',
    this.accountType = '',
    this.accountTypeAndDataSet = '',
    this.backupId = '',
    this.carrierPresence = 0,
    this.chatCapability = '',
    this.contactChatCapability = '',
    this.contactId = 0,
    this.contactLastUpdatedTimestamp = 0,
    this.contactPresence = '',
    this.contactStatus = '',
    this.contactStatusIcon = '',
    this.contactStatusLabel = '',
    this.contactStatusResPackage = '',
    this.contactStatusTs = '',
    this.creationTime = 0,
    this.customRingtone = '',
    this.value = '',
    this.type = 0,
    this.label = '',
    this.normalized = '',
    this.isPrimary = false,
    this.isSuperPrimary = false,
    this.auxData = '',
    this.metaData = '',
    this.extraData = '',
    this.alternateValue = '',
    this.contextData = '',
    this.contextType = '',
    this.neighborhood = '',
    this.emailDisplayName = '',
    this.blob,
    this.dataSet = '',
    this.dataSync1 = '',
    this.dataSync2 = '',
    this.dataSync3 = '',
    this.dataSync4 = '',
    this.dataVersion = 0,
    this.dirty = 0,
    this.displayName = '',
    this.displayNameAlt = '',
    this.displayNameReverse = '',
    this.displayNameSource = DisplayNameSource.undefined,
    this.groupSourceid = '',
    this.hasPhoneNumber = false,
    this.hashId = '',
    this.inDefaultDirectory = false,
    this.inVisibleGroup = false,
    this.isPrivate = false,
    this.isSim = false,
    this.lastTimeContacted = 0,
    this.lastTimeUsed = 0,
    this.lookup = '',
    this.mimetype = '',
    this.mode = '',
    this.nameRawContactId = 0,
    this.phonebookBucket = 0,
    this.phonebookBucketAlt = 0,
    this.phonebookLabel = '',
    this.phonebookLabelAlt = '',
    this.phoneticName = '',
    this.phoneticNameStyle = '',
    this.photoFileId = 0,
    this.photoId = 0,
    this.photoThumbUri = '',
    this.photoUri = '',
    this.pinned = false,
    this.preferredPhoneAccountComponentName = '',
    this.preferredPhoneAccountId = '',
    this.rawContactId = 0,
    this.rawContactIsUserProfile = false,
    this.resPackage = '',
    this.secCallBackground = '',
    this.secCustomAlert = '',
    this.secCustomVibration = '',
    this.secLed = '',
    this.secPreferredSim = '',
    this.secPreferredVideoCallAccountId = '',
    this.secPreferredVideoCallAccountName = '',
    this.sendToVoicemail = false,
    this.sortKey = '',
    this.sortKeyAlt = '',
    this.sourceid = '',
    this.starred = false,
    this.status = '',
    this.statusIcon = '',
    this.statusLabel = '',
    this.statusResPackage = '',
    this.statusTs = '',
    this.timesContacted = 0,
    this.timesUsed = 0,
    this.version = 0,
  });

  // ==== APP JSON ====
  factory Contactable.fromJson(Map<String, dynamic> json) => Contactable(
    id: FieldHelper.asInt(json['id'])!,
    sourceMap: json,
    parentId: json['parentId'] ?? '',
    accountName: json['accountName'] ?? '',
    accountType: json['accountType'] ?? '',
    accountTypeAndDataSet: json['accountTypeAndDataSet'] ?? '',
    backupId: json['backupId'] ?? '',
    carrierPresence: FieldHelper.asInt(json['carrierPresence']) ?? 0,
    chatCapability: json['chatCapability'] ?? '',
    contactChatCapability: json['contactChatCapability'] ?? '',
    contactId: FieldHelper.asInt(json['contactId']) ?? 0,
    contactLastUpdatedTimestamp:
        FieldHelper.asInt(json['contactLastUpdatedTimestamp']) ?? 0,
    contactPresence: json['contactPresence'] ?? '',
    contactStatus: json['contactStatus'] ?? '',
    contactStatusIcon: json['contactStatusIcon'] ?? '',
    contactStatusLabel: json['contactStatusLabel'] ?? '',
    contactStatusResPackage: json['contactStatusResPackage'] ?? '',
    contactStatusTs: json['contactStatusTs'] ?? '',
    creationTime: FieldHelper.asInt(json['creationTime']) ?? 0,
    customRingtone: json['customRingtone'] ?? '',
    value: json['value'] ?? '',
    type: FieldHelper.asInt(json['type']) ?? 0,
    label: json['label'] ?? '',
    normalized: json['normalized'] ?? '',
    isPrimary: json['isPrimary'] == 1,
    isSuperPrimary: json['isSuperPrimary'] == 1,
    auxData: json['auxData'] ?? '',
    metaData: json['metaData'] ?? '',
    extraData: json['extraData'] ?? '',
    alternateValue: json['alternateValue'] ?? '',
    contextData: json['contextData'] ?? '',
    contextType: json['contextType'] ?? '',
    neighborhood: json['neighborhood'] ?? '',
    emailDisplayName: json['emailDisplayName'] ?? '',
    blob:
        json['blob'] != null
            ? Uint8List.fromList(List<int>.from(json['blob']))
            : null,
    dataSet: json['dataSet'] ?? '',
    dataSync1: json['dataSync1'] ?? '',
    dataSync2: json['dataSync2'] ?? '',
    dataSync3: json['dataSync3'] ?? '',
    dataSync4: json['dataSync4'] ?? '',
    dataVersion: FieldHelper.asInt(json['dataVersion']) ?? 0,
    dirty: FieldHelper.asInt(json['dirty']) ?? 0,
    displayName: json['displayName'] ?? '',
    displayNameAlt: json['displayNameAlt'] ?? '',
    displayNameReverse: json['displayNameReverse'] ?? '',
    displayNameSource: FieldHelper.enumFromValue(
      DisplayNameSource.values,
      json['displayNameSource'],
    ),
    groupSourceid: json['groupSourceid'] ?? '',
    hasPhoneNumber: json['hasPhoneNumber'] ?? false,
    hashId: json['hashId'] ?? '',
    inDefaultDirectory: json['inDefaultDirectory'] ?? false,
    inVisibleGroup: json['inVisibleGroup'] ?? false,
    isPrivate: json['isPrivate'] ?? false,
    isSim: json['isSim'] ?? false,
    lastTimeContacted: FieldHelper.asInt(json['lastTimeContacted']) ?? 0,
    lastTimeUsed: FieldHelper.asInt(json['lastTimeUsed']) ?? 0,
    lookup: json['lookup'] ?? '',
    mimetype: json['mimetype'] ?? '',
    mode: json['mode'] ?? '',
    nameRawContactId: FieldHelper.asInt(json['nameRawContactId']) ?? 0,
    phonebookBucket: FieldHelper.asInt(json['phonebookBucket']) ?? 0,
    phonebookBucketAlt: FieldHelper.asInt(json['phonebookBucketAlt']) ?? 0,
    phonebookLabel: json['phonebookLabel'] ?? '',
    phonebookLabelAlt: json['phonebookLabelAlt'] ?? '',
    phoneticName: json['phoneticName'] ?? '',
    phoneticNameStyle: json['phoneticNameStyle'] ?? '',
    photoFileId: json['photoFileId'] ?? '',
    photoId: json['photoId'] ?? '',
    photoThumbUri: json['photoThumbUri'] ?? '',
    photoUri: json['photoUri'] ?? '',
    pinned: json['pinned'] == 1,
    preferredPhoneAccountComponentName:
        json['preferredPhoneAccountComponentName'] ?? '',
    preferredPhoneAccountId: json['preferredPhoneAccountId'] ?? '',
    rawContactId: FieldHelper.asInt(json['rawContactId']) ?? 0,
    rawContactIsUserProfile: json['rawContactIsUserProfile'] ?? false,
    resPackage: json['resPackage'] ?? '',
    secCallBackground: json['secCallBackground'] ?? '',
    secCustomAlert: json['secCustomAlert'] ?? '',
    secCustomVibration: json['secCustomVibration'] ?? '',
    secLed: json['secLed'] ?? '',
    secPreferredSim: json['secPreferredSim'] ?? '',
    secPreferredVideoCallAccountId:
        json['secPreferredVideoCallAccountId'] ?? '',
    secPreferredVideoCallAccountName:
        json['secPreferredVideoCallAccountName'] ?? '',
    sendToVoicemail: json['sendToVoicemail'] ?? false,
    sortKey: json['sortKey'] ?? '',
    sortKeyAlt: json['sortKeyAlt'] ?? '',
    sourceid: json['sourceid'] ?? '',
    starred: json['starred'] == 1,
    status: json['status'] ?? '',
    statusIcon: json['statusIcon'] ?? '',
    statusLabel: json['statusLabel'] ?? '',
    statusResPackage: json['statusResPackage'] ?? '',
    statusTs: json['statusTs'] ?? '',
    timesContacted: FieldHelper.asInt(json['timesContacted']) ?? 0,
    timesUsed: FieldHelper.asInt(json['timesUsed']) ?? 0,
    version: FieldHelper.asInt(json['version']) ?? 0,
  );

  Map<String, dynamic> toJson() => {
    'id': id,
    'parentId': parentId,
    'accountName': accountName,
    'accountType': accountType,
    'accountTypeAndDataSet': accountTypeAndDataSet,
    'backupId': backupId,
    'carrierPresence': carrierPresence,
    'chatCapability': chatCapability,
    'contactChatCapability': contactChatCapability,
    'contactId': contactId,
    'contactLastUpdatedTimestamp': contactLastUpdatedTimestamp,
    'contactPresence': contactPresence,
    'contactStatus': contactStatus,
    'contactStatusIcon': contactStatusIcon,
    'contactStatusLabel': contactStatusLabel,
    'contactStatusResPackage': contactStatusResPackage,
    'contactStatusTs': contactStatusTs,
    'creationTime': creationTime,
    'customRingtone': customRingtone,
    'value': value,
    'type': type,
    'label': label,
    'normalized': normalized,
    'isPrimary': isPrimary,
    'isSuperPrimary': isSuperPrimary,
    'auxData': auxData,
    'metaData': metaData,
    'extraData': extraData,
    'alternateValue': alternateValue,
    'contextData': contextData,
    'contextType': contextType,
    'neighborhood': neighborhood,
    'emailDisplayName': emailDisplayName,
    'blob': blob,
    'dataSet': dataSet,
    'dataSync1': dataSync1,
    'dataSync2': dataSync2,
    'dataSync3': dataSync3,
    'dataSync4': dataSync4,
    'dataVersion': dataVersion,
    'dirty': dirty,
    'displayName': displayName,
    'displayNameAlt': displayNameAlt,
    'displayNameReverse': displayNameReverse,
    'displayNameSource': displayNameSource?.value,
    'groupSourceid': groupSourceid,
    'hasPhoneNumber': hasPhoneNumber,
    'hashId': hashId,
    'inDefaultDirectory': inDefaultDirectory,
    'inVisibleGroup': inVisibleGroup,
    'isPrivate': isPrivate,
    'isSim': isSim,
    'lastTimeContacted': lastTimeContacted,
    'lastTimeUsed': lastTimeUsed,
    'lookup': lookup,
    'mimetype': mimetype,
    'mode': mode,
    'nameRawContactId': nameRawContactId,
    'phonebookBucket': phonebookBucket,
    'phonebookBucketAlt': phonebookBucketAlt,
    'phonebookLabel': phonebookLabel,
    'phonebookLabelAlt': phonebookLabelAlt,
    'phoneticName': phoneticName,
    'phoneticNameStyle': phoneticNameStyle,
    'photoFileId': photoFileId,
    'photoId': photoId,
    'photoThumbUri': photoThumbUri,
    'photoUri': photoUri,
    'pinned': pinned,
    'preferredPhoneAccountComponentName': preferredPhoneAccountComponentName,
    'preferredPhoneAccountId': preferredPhoneAccountId,
    'rawContactId': rawContactId,
    'rawContactIsUserProfile': rawContactIsUserProfile,
    'resPackage': resPackage,
    'secCallBackground': secCallBackground,
    'secCustomAlert': secCustomAlert,
    'secCustomVibration': secCustomVibration,
    'secLed': secLed,
    'secPreferredSim': secPreferredSim,
    'secPreferredVideoCallAccountId': secPreferredVideoCallAccountId,
    'secPreferredVideoCallAccountName': secPreferredVideoCallAccountName,
    'sendToVoicemail': sendToVoicemail,
    'sortKey': sortKey,
    'sortKeyAlt': sortKeyAlt,
    'sourceid': sourceid,
    'starred': starred,
    'status': status,
    'statusIcon': statusIcon,
    'statusLabel': statusLabel,
    'statusResPackage': statusResPackage,
    'statusTs': statusTs,
    'timesContacted': timesContacted,
    'timesUsed': timesUsed,
    'version': version,
  };

  // ==== ANDROID/DB RAW ====
  factory Contactable.fromRaw(Map<String, dynamic> raw) => Contactable(
    id: FieldHelper.asInt(raw['_id']) ?? FieldHelper.asInt(raw['id'])!,
    sourceMap: raw,
    parentId: raw['parent_id'] ?? '',
    accountName: raw['account_name'] ?? '',
    accountType: raw['account_type'] ?? '',
    accountTypeAndDataSet: raw['account_type_and_data_set'] ?? '',
    backupId: raw['backup_id'] ?? '',
    carrierPresence: FieldHelper.asInt(raw['carrier_presence']) ?? 0,
    chatCapability: raw['chat_capability'] ?? '',
    contactChatCapability: raw['contact_chat_capability'] ?? '',
    contactId: FieldHelper.asInt(raw['contact_id']) ?? 0,
    contactLastUpdatedTimestamp:
        FieldHelper.asInt(raw['contact_last_updated_timestamp']) ?? 0,
    contactPresence: raw['contact_presence'] ?? '',
    contactStatus: raw['contact_status'] ?? '',
    contactStatusIcon: raw['contact_status_icon'] ?? '',
    contactStatusLabel: raw['contact_status_label'] ?? '',
    contactStatusResPackage: raw['contact_status_res_package'] ?? '',
    contactStatusTs: raw['contact_status_ts'] ?? '',
    creationTime: FieldHelper.asInt(raw['creation_time']) ?? 0,
    customRingtone: raw['custom_ringtone'] ?? '',
    value: raw['data1'] ?? '',
    type: FieldHelper.asInt(raw['data2']) ?? 0,
    label: raw['data3'] ?? '',
    normalized: raw['data4'] ?? '',
    isPrimary: raw['data5'] == 1,
    isSuperPrimary: raw['data6'] == 1,
    auxData: raw['data7'] ?? '',
    metaData: raw['data8'] ?? '',
    extraData: raw['data9'] ?? '',
    alternateValue: raw['data10'] ?? '',
    contextData: raw['data11'] ?? '',
    contextType: raw['data12'] ?? '',
    neighborhood: raw['data13'] ?? '',
    emailDisplayName: raw['data14'] ?? '',
    blob: FieldHelper.asUInt8List(raw['data15']),
    dataSet: raw['data_set'] ?? '',
    dataSync1: raw['data_sync1'] ?? '',
    dataSync2: raw['data_sync2'] ?? '',
    dataSync3: raw['data_sync3'] ?? '',
    dataSync4: raw['data_sync4'] ?? '',
    dataVersion: FieldHelper.asInt(raw['data_version']) ?? 0,
    dirty: FieldHelper.asInt(raw['dirty']) ?? 0,
    displayName: raw['display_name'] ?? '',
    displayNameAlt: raw['display_name_alt'] ?? '',
    displayNameReverse: raw['display_name_reverse'] ?? '',
    displayNameSource: FieldHelper.enumFromValue(
      DisplayNameSource.values,
      raw['display_name_source'],
    ),
    groupSourceid: raw['group_sourceid'] ?? '',
    hasPhoneNumber: (raw['has_phone_number'] ?? 0) == 1,
    hashId: raw['hash_id'] ?? '',
    inDefaultDirectory: (raw['in_default_directory'] ?? 0) == 1,
    inVisibleGroup: (raw['in_visible_group'] ?? 0) == 1,
    isPrivate: (raw['is_private'] ?? 0) == 1,
    isSim: (raw['is_sim'] ?? 0) == 1,
    lastTimeContacted: FieldHelper.asInt(raw['last_time_contacted']) ?? 0,
    lastTimeUsed: FieldHelper.asInt(raw['last_time_used']) ?? 0,
    lookup: raw['lookup'] ?? '',
    mimetype: raw['mimetype'] ?? '',
    mode: raw['mode'] ?? '',
    nameRawContactId: FieldHelper.asInt(raw['name_raw_contact_id']) ?? 0,
    phonebookBucket: FieldHelper.asInt(raw['phonebook_bucket']) ?? 0,
    phonebookBucketAlt: FieldHelper.asInt(raw['phonebook_bucket_alt']) ?? 0,
    phonebookLabel: raw['phonebook_label'] ?? '',
    phonebookLabelAlt: raw['phonebook_label_alt'] ?? '',
    phoneticName: raw['phonetic_name'] ?? '',
    phoneticNameStyle: raw['phonetic_name_style'] ?? '',
    photoFileId: FieldHelper.asInt(raw['photo_file_id']) ?? 0,
    photoId: FieldHelper.asInt(raw['photo_id']) ?? 0,
    photoThumbUri: raw['photo_thumb_uri'] ?? '',
    photoUri: raw['photo_uri'] ?? '',
    pinned: (raw['pinned'] ?? 0) == 1,
    preferredPhoneAccountComponentName:
        raw['preferred_phone_account_component_name'] ?? '',
    preferredPhoneAccountId: raw['preferred_phone_account_id'] ?? '',
    rawContactId: FieldHelper.asInt(raw['raw_contact_id']) ?? 0,
    rawContactIsUserProfile: (raw['raw_contact_is_user_profile'] ?? 0) == 1,
    resPackage: raw['res_package'] ?? '',
    secCallBackground: raw['sec_call_background'] ?? '',
    secCustomAlert: raw['sec_custom_alert'] ?? '',
    secCustomVibration: raw['sec_custom_vibration'] ?? '',
    secLed: raw['sec_led'] ?? '',
    secPreferredSim: raw['sec_preferred_sim'] ?? '',
    secPreferredVideoCallAccountId:
        raw['sec_preferred_video_call_account_id'] ?? '',
    secPreferredVideoCallAccountName:
        raw['sec_preferred_video_call_account_name'] ?? '',
    sendToVoicemail: (raw['send_to_voicemail'] ?? 0) == 1,
    sortKey: raw['sort_key'] ?? '',
    sortKeyAlt: raw['sort_key_alt'] ?? '',
    sourceid: raw['sourceid'] ?? '',
    starred: (raw['starred'] ?? 0) == 1,
    status: raw['status'] ?? '',
    statusIcon: raw['status_icon'] ?? '',
    statusLabel: raw['status_label'] ?? '',
    statusResPackage: raw['status_res_package'] ?? '',
    statusTs: raw['status_ts'] ?? '',
    timesContacted: FieldHelper.asInt(raw['times_contacted']) ?? 0,
    timesUsed: FieldHelper.asInt(raw['times_used']) ?? 0,
    version: FieldHelper.asInt(raw['version']) ?? 0,
  );

  Map<String, dynamic> toRaw() => {
    '_id': id,
    'parent_id': parentId,
    'account_name': accountName,
    'account_type': accountType,
    'account_type_and_data_set': accountTypeAndDataSet,
    'backup_id': backupId,
    'carrier_presence': carrierPresence,
    'chat_capability': chatCapability,
    'contact_chat_capability': contactChatCapability,
    'contact_id': contactId,
    'contact_last_updated_timestamp': contactLastUpdatedTimestamp,
    'contact_presence': contactPresence,
    'contact_status': contactStatus,
    'contact_status_icon': contactStatusIcon,
    'contact_status_label': contactStatusLabel,
    'contact_status_res_package': contactStatusResPackage,
    'contact_status_ts': contactStatusTs,
    'creation_time': creationTime,
    'custom_ringtone': customRingtone,
    'data1': value,
    'data2': type,
    'data3': label,
    'data4': normalized,
    'data5': isPrimary,
    'data6': isSuperPrimary,
    'data7': auxData,
    'data8': metaData,
    'data9': extraData,
    'data10': alternateValue,
    'data11': contextData,
    'data12': contextType,
    'data13': neighborhood,
    'data14': emailDisplayName,
    'data15': blob,
    'data_set': dataSet,
    'data_sync1': dataSync1,
    'data_sync2': dataSync2,
    'data_sync3': dataSync3,
    'data_sync4': dataSync4,
    'data_version': dataVersion,
    'dirty': dirty,
    'display_name': displayName,
    'display_name_alt': displayNameAlt,
    'display_name_reverse': displayNameReverse,
    'display_name_source': displayNameSource?.value,
    'group_sourceid': groupSourceid,
    'has_phone_number': hasPhoneNumber ? 1 : 0,
    'hash_id': hashId,
    'in_default_directory': inDefaultDirectory ? 1 : 0,
    'in_visible_group': inVisibleGroup ? 1 : 0,
    'is_private': isPrivate ? 1 : 0,
    'is_sim': isSim ? 1 : 0,
    'is_super_primary': isSuperPrimary ? 1 : 0,
    'last_time_contacted': lastTimeContacted,
    'last_time_used': lastTimeUsed,
    'lookup': lookup,
    'mimetype': mimetype,
    'mode': mode,
    'name_raw_contact_id': nameRawContactId,
    'phonebook_bucket': phonebookBucket,
    'phonebook_bucket_alt': phonebookBucketAlt,
    'phonebook_label': phonebookLabel,
    'phonebook_label_alt': phonebookLabelAlt,
    'phonetic_name': phoneticName,
    'phonetic_name_style': phoneticNameStyle,
    'photo_file_id': photoFileId,
    'photo_id': photoId,
    'photo_thumb_uri': photoThumbUri,
    'photo_uri': photoUri,
    'pinned': pinned ? 1 : 0,
    'preferred_phone_account_component_name':
        preferredPhoneAccountComponentName,
    'preferred_phone_account_id': preferredPhoneAccountId,
    'raw_contact_id': rawContactId,
    'raw_contact_is_user_profile': rawContactIsUserProfile ? 1 : 0,
    'res_package': resPackage,
    'sec_call_background': secCallBackground,
    'sec_custom_alert': secCustomAlert,
    'sec_custom_vibration': secCustomVibration,
    'sec_led': secLed,
    'sec_preferred_sim': secPreferredSim,
    'sec_preferred_video_call_account_id': secPreferredVideoCallAccountId,
    'sec_preferred_video_call_account_name': secPreferredVideoCallAccountName,
    'send_to_voicemail': sendToVoicemail ? 1 : 0,
    'sort_key': sortKey,
    'sort_key_alt': sortKeyAlt,
    'sourceid': sourceid,
    'starred': starred ? 1 : 0,
    'status': status,
    'status_icon': statusIcon,
    'status_label': statusLabel,
    'status_res_package': statusResPackage,
    'status_ts': statusTs,
    'times_contacted': timesContacted,
    'times_used': timesUsed,
    'version': version,
  };
}
