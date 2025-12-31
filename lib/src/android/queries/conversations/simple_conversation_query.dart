import 'package:flutter/cupertino.dart';

import '../../models/conversations/mms_sms_simple_conversations.dart';
import '../../models/queries/query_obj.dart';
import '../../../interfaces/query_interfaces.dart';

class AndroidSimpleConversationQuery with QueryBuilder {
  AndroidSimpleConversationQuery({
    this.lookupKey = '',
    this.columns = const [],
    String? selection,
    List<String>? selectionArgs,
    String? sortOrder,
  }) : _selection = selection,
       _selectionArgs = selectionArgs,
       _sortOrder = sortOrder;

  @override
  final String lookupKey;

  @override
  final List<String> columns;
  final String? _selection;
  final List<String>? _selectionArgs;
  final String? _sortOrder;

  @override
  Uri get contentUri =>
      Uri.parse('content://mms-sms/conversations?simple=true');

  @override
  List<String> get projection => [];

  @override
  String get selection {
    String _sel = _selection ?? '';
    if (lookupKey.isNotEmpty) {
      if (_sel.isNotEmpty) {
        _sel += ' AND ';
      }
      _sel += '(_id = ?)';
    }
    return _sel;
  }

  @override
  List<String> get selectionArgs {
    List<String> args = super.selectionArgs;
    if (lookupKey.isNotEmpty) {
      args.add(lookupKey);
    }
    return _selectionArgs ?? args;
  }

  @override
  String get sortOrder => _sortOrder ?? super.sortOrder;

  @override
  Future<List<AndroidSimpleConversation>> fetch() async => _parseResponse(
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

  List<AndroidSimpleConversation> _parseResponse(
    List<Map<String, dynamic>> responses,
  ) {
    List<AndroidSimpleConversation> returnable = [];
    for (final response in responses) {
      try {
        returnable.add(AndroidSimpleConversation.fromRaw(response));
      } catch (e, s) {
        debugPrint(e.toString());
        debugPrint(s.toString());
      }
    }
    return returnable;
  }
}
