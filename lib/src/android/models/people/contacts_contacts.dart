// DEPRECATED: This auto-generated Android ContactsContract model is not used.
// The plugin uses simplified models (AndroidContact, Contactable, MmsParticipant, AndroidContactName).
// This file can be safely removed in a future major version.

class ContactsContacts {
  String? sourceLabel;
  String? phoneticName;
  String? customRingtone;
  String? contactStatusTs;
  dynamic photoFileId;
  String? contactStatusResPackage;
  String? displayNameAlt;
  String? sortKeyAlt;
  int? starred;
  String? contactStatusLabel;
  int? isUserProfile;
  int? hasPhoneNumber;
  int? contactLastUpdatedTimestamp;
  String? secCustomVibration;
  String? photoUri;
  int? phonebookBucket;
  String? displayName;
  String? sortKey;
  String? photoThumbUri;
  String? contactPresence;
  String? secCustomAlert;
  int? inDefaultDirectory;
  int? timesContacted;
  int? id;
  String? secCallBackground;
  int? nameRawContactId;
  int? phonebookBucketAlt;
  int? lastTimeContacted;
  int? isPrivate;
  int? pinned;
  String? secPreferredVideoCallAccountName;
  dynamic photoId;
  int? linkCount;
  String? link;
  String? contactChatCapability;
  String? contactStatusIcon;
  int? inVisibleGroup;
  String? phonebookLabel;
  int? displayNameSource;
  int? hasEmail;
  String? phoneticNameStyle;
  int? sendToVoicemail;
  String? lookup;
  String? phonebookLabelAlt;
  String? secPreferredSim;
  String? contactStatus;
  String? linkType1;
  String? secLed;
  String? displayNameReverse;
  String? secPreferredVideoCallAccountId;
  int? dirtyContact;

  ContactsContacts({
    this.sourceLabel,
    this.phoneticName,
    this.customRingtone,
    this.contactStatusTs,
    this.photoFileId,
    this.contactStatusResPackage,
    this.displayNameAlt,
    this.sortKeyAlt,
    this.starred,
    this.contactStatusLabel,
    this.isUserProfile,
    this.hasPhoneNumber,
    this.contactLastUpdatedTimestamp,
    this.secCustomVibration,
    this.photoUri,
    this.phonebookBucket,
    this.displayName,
    this.sortKey,
    this.photoThumbUri,
    this.contactPresence,
    this.secCustomAlert,
    this.inDefaultDirectory,
    this.timesContacted,
    this.id,
    this.secCallBackground,
    this.nameRawContactId,
    this.phonebookBucketAlt,
    this.lastTimeContacted,
    this.isPrivate,
    this.pinned,
    this.secPreferredVideoCallAccountName,
    this.photoId,
    this.linkCount,
    this.link,
    this.contactChatCapability,
    this.contactStatusIcon,
    this.inVisibleGroup,
    this.phonebookLabel,
    this.displayNameSource,
    this.hasEmail,
    this.phoneticNameStyle,
    this.sendToVoicemail,
    this.lookup,
    this.phonebookLabelAlt,
    this.secPreferredSim,
    this.contactStatus,
    this.linkType1,
    this.secLed,
    this.displayNameReverse,
    this.secPreferredVideoCallAccountId,
    this.dirtyContact,
  });

  factory ContactsContacts.fromJson(Map<String, dynamic> json) =>
      ContactsContacts(
        sourceLabel: json["sourceLabel"],
        phoneticName: json["phonetic_name"],
        customRingtone: json["custom_ringtone"],
        contactStatusTs: json["contact_status_ts"],
        photoFileId: json["photo_file_id"],
        contactStatusResPackage: json["contact_status_res_package"],
        displayNameAlt: json["display_name_alt"],
        sortKeyAlt: json["sort_key_alt"],
        starred: json["starred"],
        contactStatusLabel: json["contact_status_label"],
        isUserProfile: json["is_user_profile"],
        hasPhoneNumber: json["has_phone_number"],
        contactLastUpdatedTimestamp: json["contact_last_updated_timestamp"],
        secCustomVibration: json["sec_custom_vibration"],
        photoUri: json["photo_uri"],
        phonebookBucket: json["phonebook_bucket"],
        displayName: json["display_name"],
        sortKey: json["sort_key"],
        photoThumbUri: json["photo_thumb_uri"],
        contactPresence: json["contact_presence"],
        secCustomAlert: json["sec_custom_alert"],
        inDefaultDirectory: json["in_default_directory"],
        timesContacted: json["times_contacted"],
        id: json["_id"],
        secCallBackground: json["sec_call_background"],
        nameRawContactId: json["name_raw_contact_id"],
        phonebookBucketAlt: json["phonebook_bucket_alt"],
        lastTimeContacted: json["last_time_contacted"],
        isPrivate: json["is_private"],
        pinned: json["pinned"],
        secPreferredVideoCallAccountName:
            json["sec_preferred_video_call_account_name"],
        photoId: json["photo_id"],
        linkCount: json["link_count"],
        link: json["link"],
        contactChatCapability: json["contact_chat_capability"],
        contactStatusIcon: json["contact_status_icon"],
        inVisibleGroup: json["in_visible_group"],
        phonebookLabel: json["phonebook_label"],
        displayNameSource: json["display_name_source"],
        hasEmail: json["has_email"],
        phoneticNameStyle: json["phonetic_name_style"],
        sendToVoicemail: json["send_to_voicemail"],
        lookup: json["lookup"],
        phonebookLabelAlt: json["phonebook_label_alt"],
        secPreferredSim: json["sec_preferred_sim"],
        contactStatus: json["contact_status"],
        linkType1: json["link_type1"],
        secLed: json["sec_led"],
        displayNameReverse: json["display_name_reverse"],
        secPreferredVideoCallAccountId:
            json["sec_preferred_video_call_account_id"],
        dirtyContact: json["dirty_contact"],
      );

  Map<String, dynamic> toJson() => {
    "sourceLabel": sourceLabel,
    "phonetic_name": phoneticName,
    "custom_ringtone": customRingtone,
    "contact_status_ts": contactStatusTs,
    "photo_file_id": photoFileId,
    "contact_status_res_package": contactStatusResPackage,
    "display_name_alt": displayNameAlt,
    "sort_key_alt": sortKeyAlt,
    "starred": starred,
    "contact_status_label": contactStatusLabel,
    "is_user_profile": isUserProfile,
    "has_phone_number": hasPhoneNumber,
    "contact_last_updated_timestamp": contactLastUpdatedTimestamp,
    "sec_custom_vibration": secCustomVibration,
    "photo_uri": photoUri,
    "phonebook_bucket": phonebookBucket,
    "display_name": displayName,
    "sort_key": sortKey,
    "photo_thumb_uri": photoThumbUri,
    "contact_presence": contactPresence,
    "sec_custom_alert": secCustomAlert,
    "in_default_directory": inDefaultDirectory,
    "times_contacted": timesContacted,
    "_id": id,
    "sec_call_background": secCallBackground,
    "name_raw_contact_id": nameRawContactId,
    "phonebook_bucket_alt": phonebookBucketAlt,
    "last_time_contacted": lastTimeContacted,
    "is_private": isPrivate,
    "pinned": pinned,
    "sec_preferred_video_call_account_name": secPreferredVideoCallAccountName,
    "photo_id": photoId,
    "link_count": linkCount,
    "link": link,
    "contact_chat_capability": contactChatCapability,
    "contact_status_icon": contactStatusIcon,
    "in_visible_group": inVisibleGroup,
    "phonebook_label": phonebookLabel,
    "display_name_source": displayNameSource,
    "has_email": hasEmail,
    "phonetic_name_style": phoneticNameStyle,
    "send_to_voicemail": sendToVoicemail,
    "lookup": lookup,
    "phonebook_label_alt": phonebookLabelAlt,
    "sec_preferred_sim": secPreferredSim,
    "contact_status": contactStatus,
    "link_type1": linkType1,
    "sec_led": secLed,
    "display_name_reverse": displayNameReverse,
    "sec_preferred_video_call_account_id": secPreferredVideoCallAccountId,
    "dirty_contact": dirtyContact,
  };
}
