// ignore_for_file: public_member_api_docs, non_constant_identifier_names, avoid_as, unused_import, unnecessary_parenthesis, prefer_null_aware_operators, omit_local_variable_types, unused_shown_name, unnecessary_import, no_leading_underscores_for_local_identifiers
import 'dart:core';
import 'dart:async';

// Flutter
import 'package:flutter/services.dart';

import 'package:flutter/foundation.dart';
import 'package:simple_sms/src/android/queries/people/canonical_query.dart';

import '../../interfaces/provider_interfaces/provider_query_interface.dart';
import '../models/conversations/mms_sms_conversations.dart';
import '../models/conversations/mms_sms_simple_conversations.dart';
import '../models/device/device.dart';
import '../models/device/simcard.dart';
import '../models/enums/attachment_enums.dart';
import '../models/enums/sms_mms_enums.dart';
import '../models/messages/mms.dart';
import '../models/messages/mms_part.dart';
import '../models/messages/sms.dart';
import '../models/people/contact.dart';
import '../models/people/contact_name.dart';
import '../models/people/contactables.dart';
import '../models/people/mms_participant.dart';
import '../models/queries/query_obj.dart';
import 'conversations/full_conversation_query.dart';
import 'conversations/simple_conversation_query.dart';
import 'device/device_query.dart';
import 'device/simcard_query.dart';
import 'messages/mms_parts_query.dart';
import 'messages/mms_query.dart';
import 'messages/sms_mms_query.dart';
import 'messages/sms_query.dart';
import 'people/contacts_query.dart';
import 'people/contact_contactables_query.dart';
import 'people/mms_participant_query.dart';
import 'people/name_query.dart';
import 'raw_query.dart';

class AndroidQuery {
  static final ContactQueries contacts = ContactQueries();
  static final ConversationQueries conversations = ConversationQueries();
  static final MessageQueries messages = MessageQueries();
  static final AndroidDeviceQueries device = AndroidDeviceQueries();
  static Future<List<Map<String, dynamic>>> raw(QueryObj queryObj) async =>
      (await RawQuery().fetch(queryObj));
}

class ContactQueries {
  Future<List<Contactable>> fromPhoneNum(int phoneNumber) =>
      ContactableQuery(lookupKey: phoneNumber.toString()).fetch();

  Future<String> getCanonical({required String id}) async =>
      await CanonicalQuery(id: id).fetch();

  Future<AndroidContactName?> getName({
    required String accountType,
    required int contactId,
  }) async =>
      (await NameQuery(accountType: accountType, id: contactId).fetch())
          .firstOrNull;

  Future<List<Contactable>> getContactables({
    String lookupKey = '',
    List<String> columns = const [],
  }) => ContactableQuery(lookupKey: lookupKey, columns: columns).fetch();

  Future<List<AndroidContact>> getContacts({
    String lookupKey = '',
    List<String> columns = const [],
    String? selection,
    List<String>? selectionArgs,
    String? sortOrder,
  }) =>
      ContactsQuery(
        lookupKey: lookupKey,
        columns: columns,
        selection: selection,
        selectionArgs: selectionArgs,
        sortOrder: sortOrder,
      ).fetch();
}

class ConversationQueries {
  Future<List<AndroidFullConversation>> getFullConversations({
    String lookupKey = '',
    List<String> columns = const [],
    String? selection,
    List<String>? selectionArgs,
    String? sortOrder,
  }) async =>
      FullConversationQuery(lookupKey: lookupKey, columns: columns).fetch();

  Future<List<AndroidSimpleConversation>> getAllConversations({
    String lookupKey = '',
    List<String> columns = const [],
    String? selection,
    List<String>? selectionArgs,
    String? sortOrder,
  }) async =>
      AndroidSimpleConversationQuery(
        lookupKey: lookupKey,
        columns: columns,
        selection: selection,
        selectionArgs: selectionArgs,
        sortOrder: sortOrder,
      ).fetch();
}

class MessageQueries {
  Future<List<Mms>> getMms({
    String lookupKey = '',
    List<String> columns = const [],
    String? selection,
    List<String>? selectionArgs,
    String? sortOrder,
  }) async =>
      await MmsQuery(
        lookupKey: lookupKey,
        columns: columns,
        selection: selection,
        selectionArgs: selectionArgs,
        sortOrder: sortOrder,
      ).fetch();

  Future<List<Sms>> getSms({
    String lookupKey = '',
    List<String> columns = const [],
    String? selection,
    List<String>? selectionArgs,
    String? sortOrder,
  }) async =>
      await SmsQuery(
        lookupKey: lookupKey,
        columns: columns,
        selection: selection,
        selectionArgs: selectionArgs,
        sortOrder: sortOrder,
      ).fetch();

  Future<List<Map<String, dynamic>>> getSmsMms({
    String lookupKey = '',
    List<String> columns = const [],
  }) async => await MmsSmsQuery(lookupKey: lookupKey, columns: columns).fetch();

  Future<List<MmsPart>> getMmsParts({
    String lookupKey = '',
    List<String> columns = const [],
    String? selection,
    List<String>? selectionArgs,
    String? sortOrder,
  }) async =>
      await MmsPartsQuery(
        lookupKey: lookupKey,
        columns: columns,
        selection: selection,
        selectionArgs: selectionArgs,
        sortOrder: sortOrder,
      ).fetch();

  Future<String?> downloadMmsAttachment(String fileId) async =>
      await MmsPartsQuery().downloadFile(
        Uri.parse('content://mms/part/$fileId'),
      );

  Future<List<MmsParticipant>> getParticipants({
    SmsMmsType type = SmsMmsType.mms,
    String messageId = '',
    List<String> columns = const [],
  }) async =>
      await MmsParticipantsQuery(type: type, lookupKey: messageId).fetch();
}

class AndroidDeviceQueries {
  Future<AndroidDevice> getDevice({
    String lookupKey = '',
    List<String> columns = const [],
  }) async => await AndroidDeviceQuery().fetch();
  Future<List<AndroidSimCard>> getSimCards({
    String lookupKey = '',
    List<String> columns = const [],
  }) async => await SimCardQuery().fetch();
}

// TODO: Link up to Attachments (creating them if they don't exist)

// TODO: Check for SMS permissions and default SMS app

// TODO: Also check for multi-sim conundrums
// > https://developer.android.com/reference/android/telephony/SmsManager#getSubscriptionId()
// > https://developer.android.com/reference/android/telephony/SmsManager#getSmsManagerForSubscriptionId(int)

// TODO: Also check for any barriers, ie SMS capacity on the SIM card
// > https://developer.android.com/reference/android/telephony/SmsManager#getSmsCapacityOnIcc()

// TODO: https://docs.flutter.dev/platform-integration/android/restore-state-andr
