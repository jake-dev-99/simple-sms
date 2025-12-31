import '../../models/conversations/mms_sms_conversations.dart';
import '../../models/queries/query_obj.dart';
import '../../../interfaces/query_interfaces.dart';

class FullConversationQuery with QueryBuilder {
  const FullConversationQuery({this.lookupKey = '', this.columns = const []});

  @override
  final String lookupKey;
  @override
  final List<String> columns;
  @override
  Uri get contentUri => Uri.parse('content://mms-sms/conversations');
  @override
  List<String> get projection => [];

  @override
  Future<List<AndroidFullConversation>> fetch() async => _parseResponse(
    await super.select(
      QueryObj(
        contentUri: contentUri,
        sortOrder: sortOrder,
        projection: projection,
        selection: selection,
        selectionArgs: selectionArgs,
      ),
    ),
  );

  List<AndroidFullConversation> _parseResponse(
    List<Map<String, dynamic>> responses,
  ) {
    List<AndroidFullConversation> returnable = [];
    for (final response in responses) {
      returnable.add(AndroidFullConversation.fromRaw(response));
    }
    return returnable;
  }
}
