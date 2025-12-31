import 'package:simple_sms/src/android/models/messages/mms.dart';
import 'package:flutter/foundation.dart' show debugPrint;
import '../../models/queries/query_obj.dart';
import '../../../interfaces/query_interfaces.dart';
import '../people/mms_participant_query.dart';
import 'mms_parts_query.dart';

class MmsQuery with QueryBuilder {
  const MmsQuery({
    this.lookupKey = '',
    this.columns = const [],
    String? selection,
    List<String>? selectionArgs,
    String? sortOrder,
  }) : _selection = selection,
       _selectionArgs = selectionArgs,
       _sortOrder = sortOrder;
  @override
  final List<String> columns;
  @override
  final String lookupKey;
  final String? _selection;
  final List<String>? _selectionArgs;
  final String? _sortOrder;
  @override
  Uri get contentUri => Uri.parse('content://mms/$lookupKey');

  @override
  String get selection => _selection ?? super.selection;

  @override
  List<String> get selectionArgs => _selectionArgs ?? super.selectionArgs;

  @override
  String get sortOrder => _sortOrder ?? '_id DESC';

  @override
  Future<List<Mms>> fetch() async {
    try {
      List<Mms> messages = [];
      Map<String, String> failedMessages = {};

      // Start by priming participants, contacts, and parts
      final allParticipants =
          await MmsParticipantsQuery(lookupKey: lookupKey).fetch();

      final allParts = await MmsPartsQuery(lookupKey: lookupKey).fetch();
      final allMessages = await super.select(
        QueryObj(
          contentUri: contentUri,
          projection: projection,
          selection: selection,
          selectionArgs: selectionArgs,
          sortOrder: sortOrder,
        ),
      );

      for (final message in allMessages) {
        String mid = '';

        try {
          mid = message['_id']!.toString();
        } catch (e, s) {
          debugPrint('Error extracting message ID: $e');
          debugPrint(s.toString());
        }

        try {
          // Parse out participants\
          message['recipients'] =
              allParticipants
                  .where((participant) => participant.msgId?.toString() == mid)
                  .toList()
                  .map((p) => p.toJson())
                  .toList();

          // Parse out the parts
          message['parts'] =
              allParts
                  .where((part) => part.messageId?.toString() == mid)
                  .where((part) => !part.contentType.value.contains('text'))
                  .toList();

          message['body'] = allParts
              .where((part) => part.messageId?.toString() == mid)
              .where((part) => part.contentType.value.contains('text'))
              .map((part) => part.text)
              .join('');

        } catch (e, s) {
          debugPrint('Error parsing message parts or participants: $e');
          debugPrint(s.toString());
        }

        try {
          messages.add(await Mms.fromRaw(message));
        } catch (e, s) {
          failedMessages[mid] = e.toString();
          debugPrint(e.toString());
          debugPrint(s.toString());
          debugPrint(message.toString());
          debugPrint(messages.toString());
          debugPrint('---');
        }
      }
      if (failedMessages.isNotEmpty) {
        debugPrint('Failed to parse messages:');
        for (final entry in failedMessages.entries) {
          debugPrint('${entry.key}: ${entry.value}');
        }
      }
      return messages;
    } catch (e, s) {
      debugPrint(e.toString());
      debugPrint(s.toString());
    }
    return [];
  }
}
