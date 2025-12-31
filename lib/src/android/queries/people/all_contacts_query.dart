import '../../models/queries/query_obj.dart';
import '../../../interfaces/query_interfaces.dart';
import '../../models/people/contact.dart';

class ContactsQuery with QueryBuilder {
  @override
  Uri get contentUri => Uri.parse(
    lookupKey.isEmpty
        ? 'content://com.android.contacts/contacts'
        : 'content://com.android.contacts/contacts/filter/${Uri.encodeQueryComponent(lookupKey)}',
  );
  @override
  final List<String> columns;
  @override
  final String lookupKey;

  const ContactsQuery({this.lookupKey = '', this.columns = const []});

  @override
  Future<List<AndroidContact>> fetch() async {
    final List<Map<String, dynamic>> foundContacts = (await super.select(
      QueryObj(
        contentUri: contentUri,
        projection: projection,
        selection: selection,
        selectionArgs: selectionArgs,
        sortOrder: sortOrder,
      ),
    ));

    List<AndroidContact> contacts = [];
    for (final contactMap in foundContacts) {
      contacts.add(AndroidContact.fromRaw(contactMap));
    }
    return contacts;
  }
}
