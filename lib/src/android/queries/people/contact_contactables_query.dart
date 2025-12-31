import '../../models/people/contactables.dart';
import '../../models/queries/query_obj.dart';
import '../../../interfaces/query_interfaces.dart';
import 'package:flutter/foundation.dart';

class ContactableQuery with QueryBuilder {
  ContactableQuery({this.lookupKey = '', this.columns = const []})
    : contentUri = Uri.parse(
        'content://com.android.contacts/data/contactables/$lookupKey',
      );

  ContactableQuery.byPhone({required String phoneNum, this.columns = const []})
    : lookupKey = phoneNum,
      contentUri = Uri.parse(
        'content://com.android.contacts/data/phones/filter/$phoneNum',
      );

  ContactableQuery.byEmail({required String email, this.columns = const []})
    : lookupKey = email,
      contentUri = Uri.parse(
        'content://com.android.contacts/data/emails/filter/$email',
      );

  ContactableQuery.byCommon({required String value, this.columns = const []})
    : lookupKey = value,
      contentUri = Uri.parse(
        'content://com.android.contacts/data/contactables/filter/$value',
      );

  @override
  final Uri contentUri;

  @override
  final List<String> columns;
  @override
  final String lookupKey;

  @override
  String get selection => '';
  @override
  List<String> get selectionArgs => [];
  @override
  String get sortOrder => '';

  Future<Contactable?> fetchOne() async => (await fetch()).firstOrNull;

  @override
  Future<List<Contactable>> fetch() async {
    final List<Map<String, dynamic>> allContacts =
        List<Map<String, dynamic>>.from(
          await super.select(
            QueryObj(
              contentUri: contentUri,
              sortOrder: sortOrder,
              projection: projection,
            ),
          ),
        );

    List<Contactable> contacts = [];
    for (final contactMap in allContacts) {
      try {
        contacts.add(Contactable.fromRaw(contactMap));
      } catch (e, s) {
        debugPrint(e.toString());
        debugPrint(s.toString());
      }
    }

    return contacts;
  }
}
