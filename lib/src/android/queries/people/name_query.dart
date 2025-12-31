import '../../models/people/contact_name.dart';
import '../../models/queries/query_obj.dart';
import '../../../interfaces/query_interfaces.dart';

class NameQuery with QueryBuilder {
  @override
  final Uri contentUri;
  @override
  final List<String> columns = [
    '_id',
    'data1',
    'data2',
    'data3',
    'data4',
    'data5',
    'data6',
    'data7',
    'data8',
    'data9',
  ];
  @override
  final String lookupKey = '';

  final int id;
  final String accountType;

  NameQuery({required this.accountType, required this.id})
    : contentUri = Uri.parse('content://com.android.contacts/data');

  @override
  Future<List<AndroidContactName>> fetch() async {
    if (id <= 0) {
      return [];
    }
    final responses = await super.select(
      QueryObj(
        contentUri: contentUri,
        projection: columns,
        selection:
            "mimetype = ? AND contact_id = ? AND account_type_and_data_set = ?",
        selectionArgs: [
          "vnd.android.cursor.item/name",
          id.toString(),
          accountType,
        ],
      ),
    );

    if (responses.isEmpty) {
      return [];
    }

    final response = responses.first;

    return [AndroidContactName.fromRaw(response)];
  }
}
