import 'package:flutter/foundation.dart';
import 'package:simple_sms/src/android/models/messages/sms.dart';

import '../../models/enums/sms_mms_enums.dart';
import '../../models/messages/mms.dart';
import 'mms_query.dart';
import 'sms_query.dart';

class MmsSmsQuery {
  const MmsSmsQuery({this.lookupKey = '', this.columns = const []});
  final List<String> columns;
  final String lookupKey;
  String get contentUri => 'content://mms/part';
  List<String> get projection => columns.map((e) => e.toString()).toList();
  String get selection => '';
  List<String> get selectionArgs => [];
  String get sortOrder => '';

  /// Get all messages and combine them into a unified list
  Future<List<Map<String, dynamic>>> fetch() async {
    debugPrint('Fetching SMS and MMS messages');

    // Fetch messages in parallel for better performance
    final futures = await Future.wait([SmsQuery().fetch(), MmsQuery().fetch()]);

    final allSms = futures[0] as List<Sms>;
    final allMms = futures[1] as List<Mms>;

    debugPrint("Total messages found: ${allSms.length + allMms.length}");

    return allSms
        .map((e) => e.toJson())
        .followedBy(allMms.map((m) => m.toJson()))
        .toList();
  }

  /// Get the send status of a message
  Future<AndroidMessageStatus> getMessageSendStatus({
    required String lookupId,
    required SmsMmsType type,
  }) async {
    switch (type) {
      case SmsMmsType.mms:
        final mms = await MmsQuery(lookupKey: lookupId).fetch();
        if (mms.isEmpty) return AndroidMessageStatus.indeterminate;
        return mms.first.status!;
      case SmsMmsType.sms:
        final sms = await SmsQuery(lookupKey: lookupId).fetch();
        if (sms.isEmpty) return AndroidMessageStatus.indeterminate;
        return sms.first.status!;
    }
  }
}
