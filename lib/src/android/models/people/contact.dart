import 'package:simple_sms_native/src/android/models/model_helpers.dart';
import '../../../interfaces/models_interface.dart';
import '../enums/contact_enums.dart';

/// A contact row read from Android's Contacts content provider
/// (`content://com.android.contacts/contacts`).
///
/// This is the high-level "person" record. Individual phone numbers,
/// emails, and addresses belonging to this contact are modeled
/// separately as [Contactables] records — use
/// [LookupService.listContactablesForContact] to resolve them.
///
/// Fields consumers typically care about: [id] (stable Android contact
/// id), [displayName] (the resolved name Android presents in UI),
/// [displayNameSource] (whether the name came from a structured-name
/// row, the primary phone number, an email, etc.), [hasPhoneNumber] /
/// [sendToVoicemail] / [starred] flags, and [lastTimeContacted]. The
/// remaining fields (presence, chat capability, custom-ringtone URI,
/// carrier status metadata, etc.) flow through for round-trip fidelity
/// with the provider.
class AndroidContact implements ModelInterface {
  @override
  final int id;
  @override
  final Map<String, dynamic>? sourceMap;

  final DisplayNameSource? displayNameSource;
  final String? contactChatCapability;
  final String? contactPresence;
  final String? contactStatus;
  final String? contactStatusIcon;
  final String? contactStatusLabel;
  final String? contactStatusResPackage;
  final String? contactStatusTs;
  final String? customRingtone;
  final String? displayName;
  final String? displayNameAlt;
  final String? displayNameReverse;
  final String? link;
  final String? linkType1;
  final String? lookup;
  final String? phonebookLabel;
  final String? phonebookLabelAlt;
  final String? phoneticName;
  final String? phoneticNameStyle;
  final int? photoFileId;
  final int? photoId;
  final String? photoThumbUri;
  final String? photoUri;
  final String? secCallBackground;
  final String? secCustomAlert;
  final String? secCustomVibration;
  final String? secLed;
  final String? secPreferredSim;
  final int? secPreferredVideoCallAccountId;
  final String? secPreferredVideoCallAccountName;
  final String? snippet;
  final String? sortKey;
  final String? sortKeyAlt;
  final bool hasEmail;
  final bool hasPhoneNumber;
  final bool inDefaultDirectory;
  final bool inVisibleGroup;
  final bool isPrivate;
  final bool isUserProfile;
  final bool pinned;
  final bool sendToVoicemail;
  final bool starred;
  final int? contactLastUpdatedTimestamp;
  final int? dirtyContact;
  final int? lastTimeContacted;
  final int? linkCount;
  final int? nameRawContactId;
  final int? phonebookBucket;
  final int? phonebookBucketAlt;
  final int? timesContacted;

  AndroidContact({
    required this.id,
    this.sourceMap,
    this.displayName,
    this.displayNameAlt,
    this.displayNameReverse,
    this.displayNameSource,
    this.hasEmail = false,
    this.hasPhoneNumber = false,
    this.inDefaultDirectory = false,
    this.inVisibleGroup = false,
    this.isPrivate = false,
    this.isUserProfile = false,
    this.lastTimeContacted,
    this.link,
    this.linkCount,
    this.linkType1,
    this.lookup,
    this.nameRawContactId,
    this.phonebookBucket,
    this.phonebookBucketAlt,
    this.phonebookLabel,
    this.phonebookLabelAlt,
    this.phoneticName,
    this.phoneticNameStyle,
    this.photoFileId,
    this.photoId,
    this.photoThumbUri,
    this.photoUri,
    this.pinned = false,
    this.secCallBackground,
    this.secCustomAlert,
    this.secCustomVibration,
    this.secLed,
    this.secPreferredSim,
    this.secPreferredVideoCallAccountId,
    this.secPreferredVideoCallAccountName,
    this.sendToVoicemail = false,
    this.sortKey,
    this.sortKeyAlt,
    this.starred = false,
    this.timesContacted,
    this.customRingtone,
    this.contactStatusTs,
    this.contactStatusResPackage,
    this.contactStatusLabel,
    this.contactLastUpdatedTimestamp,
    this.contactPresence,
    this.contactStatus,
    this.contactStatusIcon,
    this.contactChatCapability,
    this.dirtyContact,
    this.snippet,
  });

  // == App-style JSON (camelCase) ==
  factory AndroidContact.fromJson(Map<String, dynamic> json) => AndroidContact(
    id: FieldHelper.asInt(json['id']) ?? 0,
    sourceMap: json,
    displayName: json['displayName'] ?? '',
    displayNameAlt: json['displayNameAlt'] ?? '',
    displayNameReverse: json['displayNameReverse'] ?? '',
    displayNameSource: FieldHelper.enumFromValue(
      DisplayNameSource.values,
      json['displayNameSource'],
    ),
    hasEmail: json['hasEmail'] ?? false,
    hasPhoneNumber: json['hasPhoneNumber'] ?? false,
    inDefaultDirectory: json['inDefaultDirectory'] ?? false,
    inVisibleGroup: json['inVisibleGroup'] ?? false,
    isPrivate: json['isPrivate'] ?? false,
    isUserProfile: json['isUserProfile'] ?? false,
    lastTimeContacted: json['lastTimeContacted'] ?? 0,
    link: json['link'] ?? '',
    linkCount: json['linkCount'] ?? 0,
    linkType1: json['linkType1'] ?? '',
    lookup: json['lookup'] ?? '',
    nameRawContactId: json['nameRawContactId'] ?? 0,
    phonebookBucket: json['phonebookBucket'] ?? 0,
    phonebookBucketAlt: json['phonebookBucketAlt'] ?? 0,
    phonebookLabel: json['phonebookLabel'] ?? '',
    phonebookLabelAlt: json['phonebookLabelAlt'] ?? '',
    phoneticName: json['phoneticName'] ?? '',
    phoneticNameStyle: json['phoneticNameStyle'] ?? '0',
    photoFileId: json['photoFileId'] ?? '',
    photoId: json['photoId'] ?? '',
    photoThumbUri: json['photoThumbUri'] ?? '',
    photoUri: json['photoUri'] ?? '',
    pinned: json['pinned'] == 1,
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
    starred: json['starred'] == 1,
    timesContacted: json['timesContacted'] ?? 0,
    customRingtone: json['customRingtone'] ?? '',
    contactStatusTs: json['contactStatusTs'] ?? '',
    contactStatusResPackage: json['contactStatusResPackage'] ?? '',
    contactStatusLabel: json['contactStatusLabel'] ?? '',
    contactLastUpdatedTimestamp: json['contactLastUpdatedTimestamp'] ?? 0,
    contactPresence: json['contactPresence'] ?? '',
    contactStatus: json['contactStatus'] ?? '',
    contactStatusIcon: json['contactStatusIcon'] ?? '',
    contactChatCapability: json['contactChatCapability'] ?? '',
    dirtyContact: json['dirtyContact'] ?? 0,
    snippet: json['snippet'],
  );

  Map<String, dynamic> toJson() => {
    'id': id,
    'displayName': displayName,
    'displayNameAlt': displayNameAlt,
    'displayNameReverse': displayNameReverse,
    'displayNameSource': displayNameSource?.value,
    'hasEmail': hasEmail,
    'hasPhoneNumber': hasPhoneNumber,
    'inDefaultDirectory': inDefaultDirectory,
    'inVisibleGroup': inVisibleGroup,
    'isPrivate': isPrivate,
    'isUserProfile': isUserProfile,
    'lastTimeContacted': lastTimeContacted,
    'link': link,
    'linkCount': linkCount,
    'linkType1': linkType1,
    'lookup': lookup,
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
    'starred': starred,
    'timesContacted': timesContacted,
    'customRingtone': customRingtone,
    'contactStatusTs': contactStatusTs,
    'contactStatusResPackage': contactStatusResPackage,
    'contactStatusLabel': contactStatusLabel,
    'contactLastUpdatedTimestamp': contactLastUpdatedTimestamp,
    'contactPresence': contactPresence,
    'contactStatus': contactStatus,
    'contactStatusIcon': contactStatusIcon,
    'contactChatCapability': contactChatCapability,
    'dirtyContact': dirtyContact,
    'snippet': snippet,
  };

  // == Android/DB-style (raw, snake_case) ==
  factory AndroidContact.fromRaw(Map<String, dynamic> raw) => AndroidContact(
    // Telephony contacts PK is `_id` (BaseColumns). `raw['id']` is a test-only
    // legacy shim; 0 is the last-resort default.
    id: FieldHelper.asInt(raw['_id']) ?? FieldHelper.asInt(raw['id']) ?? 0,
    sourceMap: raw,
    displayName: raw['display_name']?.toString() ?? '',
    displayNameAlt: raw['display_name_alt']?.toString() ?? '',
    displayNameReverse: raw['display_name_reverse']?.toString() ?? '',
    displayNameSource: FieldHelper.enumFromValue(
      DisplayNameSource.values,
      raw['display_name_source'],
    ),
    // Flag columns: ContactsContract int columns that occasionally
    // surface as stringified ints on OEM builds; coerce via asInt before
    // the `== 1` test so a String "1" still lands as `true`.
    hasEmail: FieldHelper.asInt(raw['has_email']) == 1,
    hasPhoneNumber: FieldHelper.asInt(raw['has_phone_number']) == 1,
    inDefaultDirectory: FieldHelper.asInt(raw['in_default_directory']) == 1,
    inVisibleGroup: FieldHelper.asInt(raw['in_visible_group']) == 1,
    isPrivate: FieldHelper.asInt(raw['is_private']) == 1,
    isUserProfile: FieldHelper.asInt(raw['is_user_profile']) == 1,
    lastTimeContacted: FieldHelper.asInt(raw['last_time_contacted']) ?? 0,
    link: raw['link']?.toString() ?? '',
    linkCount: FieldHelper.asInt(raw['link_count']) ?? 0,
    linkType1: raw['link_type1']?.toString() ?? '',
    lookup: raw['lookup']?.toString() ?? '',
    nameRawContactId: FieldHelper.asInt(raw['name_raw_contact_id']) ?? 0,
    phonebookBucket: FieldHelper.asInt(raw['phonebook_bucket']) ?? 0,
    phonebookBucketAlt: FieldHelper.asInt(raw['phonebook_bucket_alt']) ?? 0,
    phonebookLabel: raw['phonebook_label']?.toString() ?? '',
    phonebookLabelAlt: raw['phonebook_label_alt']?.toString() ?? '',
    phoneticName: raw['phonetic_name']?.toString() ?? '',
    phoneticNameStyle: raw['phonetic_name_style']?.toString() ?? '0',
    photoFileId: FieldHelper.asInt(raw['photo_file_id']) ?? 0,
    photoId: FieldHelper.asInt(raw['photo_id']) ?? 0,
    photoThumbUri: raw['photo_thumb_uri']?.toString() ?? '',
    photoUri: raw['photo_uri']?.toString() ?? '',
    pinned: FieldHelper.asInt(raw['pinned']) == 1,
    secCallBackground: raw['sec_call_background']?.toString() ?? '',
    secCustomAlert: raw['sec_custom_alert']?.toString() ?? '',
    secCustomVibration: raw['sec_custom_vibration']?.toString() ?? '',
    secLed: raw['sec_led']?.toString() ?? '',
    secPreferredSim: raw['sec_preferred_sim']?.toString() ?? '',
    secPreferredVideoCallAccountId:
        FieldHelper.asInt(raw['sec_preferred_video_call_account_id']) ?? 0,
    secPreferredVideoCallAccountName:
        raw['sec_preferred_video_call_account_name']?.toString() ?? '',
    sendToVoicemail: FieldHelper.asInt(raw['send_to_voicemail']) == 1,
    sortKey: raw['sort_key']?.toString() ?? '',
    sortKeyAlt: raw['sort_key_alt']?.toString() ?? '',
    starred: FieldHelper.asInt(raw['starred']) == 1,
    timesContacted: FieldHelper.asInt(raw['times_contacted']) ?? 0,
    customRingtone: raw['custom_ringtone']?.toString() ?? '',
    contactStatusTs: raw['contact_status_ts']?.toString() ?? '',
    contactStatusResPackage: raw['contact_status_res_package']?.toString() ?? '',
    contactStatusLabel: raw['contact_status_label']?.toString() ?? '',
    contactLastUpdatedTimestamp:
        FieldHelper.asInt(raw['contact_last_updated_timestamp']) ?? 0,
    contactPresence: raw['contact_presence']?.toString() ?? '',
    contactStatus: raw['contact_status']?.toString() ?? '',
    contactStatusIcon: raw['contact_status_icon']?.toString() ?? '',
    contactChatCapability: raw['contact_chat_capability']?.toString() ?? '',
    dirtyContact: FieldHelper.asInt(raw['dirty_contact']) ?? 0,
    snippet: raw['snippet']?.toString(),
  );

  Map<String, dynamic> toRaw() => {
    '_id': id,
    'display_name': displayName,
    'display_name_alt': displayNameAlt,
    'display_name_reverse': displayNameReverse,
    'display_name_source': displayNameSource?.value,
    'has_email': hasEmail ? 1 : 0,
    'has_phone_number': hasPhoneNumber ? 1 : 0,
    'in_default_directory': inDefaultDirectory ? 1 : 0,
    'in_visible_group': inVisibleGroup ? 1 : 0,
    'is_private': isPrivate ? 1 : 0,
    'is_user_profile': isUserProfile ? 1 : 0,
    'last_time_contacted': lastTimeContacted,
    'link': link,
    'link_count': linkCount,
    'link_type1': linkType1,
    'lookup': lookup,
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
    'starred': starred ? 1 : 0,
    'times_contacted': timesContacted,
    'custom_ringtone': customRingtone,
    'contact_status_ts': contactStatusTs,
    'contact_status_res_package': contactStatusResPackage,
    'contact_status_label': contactStatusLabel,
    'contact_last_updated_timestamp': contactLastUpdatedTimestamp,
    'contact_presence': contactPresence,
    'contact_status': contactStatus,
    'contact_status_icon': contactStatusIcon,
    'contact_chat_capability': contactChatCapability,
    'dirty_contact': dirtyContact,
    'snippet': snippet,
  };

  AndroidContact copyWith({
    int? id,
    Map<String, dynamic>? sourceMap,
    String? displayName,
    String? displayNameAlt,
    String? displayNameReverse,
    DisplayNameSource? displayNameSource,
    bool? hasEmail,
    bool? hasPhoneNumber,
    bool? inDefaultDirectory,
    bool? inVisibleGroup,
    bool? isPrivate,
    bool? isUserProfile,
    int? lastTimeContacted,
    String? link,
    int? linkCount,
    String? linkType1,
    String? lookup,
    int? nameRawContactId,
    int? phonebookBucket,
    int? phonebookBucketAlt,
    String? phonebookLabel,
    String? phonebookLabelAlt,
    String? phoneticName,
    String? phoneticNameStyle,
    int? photoFileId,
    int? photoId,
    String? photoThumbUri,
    String? photoUri,
    bool? pinned,
    String? secCallBackground,
    String? secCustomAlert,
    String? secCustomVibration,
    String? secLed,
    String? secPreferredSim,
    int? secPreferredVideoCallAccountId,
    String? secPreferredVideoCallAccountName,
    bool? sendToVoicemail,
    String? sortKey,
    String? sortKeyAlt,
    bool? starred,
    int? timesContacted,
    String? snippet,
    String? customRingtone,
    String? contactStatusTs,
    String? contactStatusResPackage,
    String? contactStatusLabel,
    int? contactLastUpdatedTimestamp,
    String? contactPresence,
    String? contactStatus,
    String? contactStatusIcon,
    String? contactChatCapability,
    int? dirtyContact,
  }) {
    return AndroidContact(
      id: id ?? this.id,
      sourceMap: sourceMap ?? this.sourceMap,
      displayName: displayName ?? this.displayName,
      displayNameAlt: displayNameAlt ?? this.displayNameAlt,
      displayNameReverse: displayNameReverse ?? this.displayNameReverse,
      displayNameSource: displayNameSource ?? this.displayNameSource,
      hasEmail: hasEmail ?? this.hasEmail,
      hasPhoneNumber: hasPhoneNumber ?? this.hasPhoneNumber,
      inDefaultDirectory: inDefaultDirectory ?? this.inDefaultDirectory,
      inVisibleGroup: inVisibleGroup ?? this.inVisibleGroup,
      isPrivate: isPrivate ?? this.isPrivate,
      isUserProfile: isUserProfile ?? this.isUserProfile,
      lastTimeContacted: lastTimeContacted ?? this.lastTimeContacted,
      link: link ?? this.link,
      linkCount: linkCount ?? this.linkCount,
      linkType1: linkType1 ?? this.linkType1,
      lookup: lookup ?? this.lookup,
      nameRawContactId: nameRawContactId ?? this.nameRawContactId,
      phonebookBucket: phonebookBucket ?? this.phonebookBucket,
      phonebookBucketAlt: phonebookBucketAlt ?? this.phonebookBucketAlt,
      phonebookLabel: phonebookLabel ?? this.phonebookLabel,
      phonebookLabelAlt: phonebookLabelAlt ?? this.phonebookLabelAlt,
      phoneticName: phoneticName ?? this.phoneticName,
      phoneticNameStyle: phoneticNameStyle ?? this.phoneticNameStyle,
      photoFileId: photoFileId ?? this.photoFileId,
      photoId: photoId ?? this.photoId,
      photoThumbUri: photoThumbUri ?? this.photoThumbUri,
      photoUri: photoUri ?? this.photoUri,
      pinned: pinned ?? this.pinned,
      secCallBackground: secCallBackground ?? this.secCallBackground,
      secCustomAlert: secCustomAlert ?? this.secCustomAlert,
      secCustomVibration: secCustomVibration ?? this.secCustomVibration,
      secLed: secLed ?? this.secLed,
      secPreferredSim: secPreferredSim ?? this.secPreferredSim,
      secPreferredVideoCallAccountId:
          secPreferredVideoCallAccountId ?? this.secPreferredVideoCallAccountId,
      secPreferredVideoCallAccountName:
          secPreferredVideoCallAccountName ??
          this.secPreferredVideoCallAccountName,
      sendToVoicemail: sendToVoicemail ?? this.sendToVoicemail,
      sortKey: sortKey ?? this.sortKey,
      sortKeyAlt: sortKeyAlt ?? this.sortKeyAlt,
      starred: starred ?? this.starred,
      timesContacted: timesContacted ?? this.timesContacted,
      snippet: snippet ?? this.snippet,
      customRingtone: customRingtone ?? this.customRingtone,
      contactStatusTs: contactStatusTs ?? this.contactStatusTs,
      contactStatusResPackage:
          contactStatusResPackage ?? this.contactStatusResPackage,
      contactStatusLabel: contactStatusLabel ?? this.contactStatusLabel,
      contactLastUpdatedTimestamp:
          contactLastUpdatedTimestamp ?? this.contactLastUpdatedTimestamp,
      contactPresence: contactPresence ?? this.contactPresence,
      contactStatus: contactStatus ?? this.contactStatus,
      contactStatusIcon: contactStatusIcon ?? this.contactStatusIcon,
      contactChatCapability:
          contactChatCapability ?? this.contactChatCapability,
      dirtyContact: dirtyContact ?? this.dirtyContact,
    );
  }
}
