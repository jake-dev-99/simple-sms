import 'package:flutter/foundation.dart';
import 'package:simple_sms/src/android/models/enums/sms_mms_enums.dart';
import '../../../interfaces/query_interfaces.dart';
import '../../models/people/mms_participant.dart';
import '../../models/queries/query_obj.dart';

/// Lookup key is the conversation id, and is required here for proper data retrieval
class MmsParticipantsQuery with QueryBuilder {
  MmsParticipantsQuery({
    this.type = SmsMmsType.mms,
    this.lookupKey = '',
    this.columns = const [],
  });

  final SmsMmsType type;

  @override
  final String lookupKey;

  @override
  final List<String> columns;

  @override
  Uri get contentUri =>
      lookupKey.isNotEmpty
          ? Uri.parse('content://mms/$lookupKey/addr')
          : Uri.parse('content://mms/addr/');

  @override
  Future<List<MmsParticipant>> fetch() async {
    List<Map<String, dynamic>> rawParticipants =
        (await super.select(
          QueryObj(
            contentUri: contentUri,
            projection: projection,
            selection: selection,
            selectionArgs: selectionArgs,
            sortOrder: sortOrder,
          ),
        )).cast<Map<String, dynamic>>();

    List<MmsParticipant> participants = [];
    for (final participant in rawParticipants) {
      try {
        participants.add(MmsParticipant.fromRaw(participant));
      } catch (e, s) {
        debugPrint(e.toString());
        debugPrint(s.toString());
      }
    }
    return participants;
  }
}
