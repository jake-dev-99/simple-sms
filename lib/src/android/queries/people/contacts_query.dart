import '../../models/queries/query_obj.dart';
import '../../../interfaces/query_interfaces.dart';
import '../../models/people/contact.dart';
import 'package:flutter/foundation.dart';

extension StringNormalizer on String {
  String normalize() {
    return trim().toLowerCase();
  }

  String normalizePhone() => '+${replaceAll(RegExp(r'[^\d]'), '')}';
}

class ContactsQuery with QueryBuilder {
  @override
  final Uri contentUri;
  @override
  final List<String> columns;
  @override
  final String lookupKey;
  final String? _selection;
  final List<String>? _selectionArgs;
  final String? _sortOrder;

  ContactsQuery({
    this.lookupKey = '',
    this.columns = const [],
    String? selection,
    List<String>? selectionArgs,
    String? sortOrder,
  }) : _selection = selection,
       _selectionArgs = selectionArgs,
       _sortOrder = sortOrder,
       contentUri = Uri.parse('content://com.android.contacts/contacts');

  ContactsQuery.withId({
    required String id,
    this.columns = const [],
    String? selection,
    List<String>? selectionArgs,
    String? sortOrder,
  }) : lookupKey = id.normalize(),
       _selection = selection,
       _selectionArgs = selectionArgs,
       _sortOrder = sortOrder,
       contentUri = Uri.parse('content://com.android.contacts/contacts/$id');

  ContactsQuery.withPhone({
    required String phone,
    this.columns = const [],
    String? selection,
    List<String>? selectionArgs,
    String? sortOrder,
  }) : lookupKey = phone.normalizePhone(),
       _selection = selection,
       _selectionArgs = selectionArgs,
       _sortOrder = sortOrder,
       contentUri = Uri.parse(
         'content://com.android.contacts/contacts/filter/$phone',
       );

  @override
  String get selection => _selection ?? super.selection;

  @override
  List<String> get selectionArgs => _selectionArgs ?? super.selectionArgs;

  @override
  String get sortOrder => _sortOrder ?? super.sortOrder;

  @override
  Future<List<AndroidContact>> fetch() async {
    final List<Map<String, dynamic>> foundContacts = await super.select(
      QueryObj(
        contentUri: contentUri,
        projection: projection,
        selection: selection,
        selectionArgs: selectionArgs,
        sortOrder: sortOrder,
      ),
    );

    List<AndroidContact> contacts = [];
    for (final contactMap in foundContacts) {
      try {
        contacts.add(AndroidContact.fromRaw(contactMap));
      } catch (e, s) {
        debugPrint(e.toString());
        debugPrint(s.toString());
      }
    }
    return contacts;
  }
}
