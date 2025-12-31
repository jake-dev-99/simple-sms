import 'package:simple_sms/simple_sms.dart';
import '../../../interfaces/query_interfaces.dart';
import 'package:flutter/foundation.dart' show debugPrint;


class SmsQuery with QueryBuilder {
  const SmsQuery({
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
  Uri get contentUri => Uri.parse('content://sms/$lookupKey');

  @override
  String get selection => _selection ?? super.selection;

  @override
  List<String> get selectionArgs => _selectionArgs ?? super.selectionArgs;

  @override
  String get sortOrder => _sortOrder ?? super.sortOrder;

  @override
  Future<List<Sms>> fetch() async {
    final messages = await super.select(
      QueryObj(
        contentUri: contentUri,
        projection: projection,
        selection: selection,
        selectionArgs: selectionArgs,
        sortOrder: sortOrder,
      ),
    );

    List<Sms> result = [];
    for (final message in messages) {
      try {
        result.add(Sms.fromRaw(message));
      } catch (e, s) {
        debugPrint(e.toString());
        debugPrint(s.toString());
      }
    }
    return messages.map((msg) => Sms.fromRaw(msg)).toList();
  }
}
