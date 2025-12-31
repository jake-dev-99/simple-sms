import 'package:simple_sms/src/interop/query_interop.dart';

import '../../models/queries/query_obj.dart';

extension StringNormalizer on String {
  String normalize() {
    return trim().toLowerCase();
  }

  String normalizePhone() => '+${replaceAll(RegExp(r'[^\d]'), '')}';
}

class CanonicalQuery {
  final Uri contentUri;
  final String id;

  CanonicalQuery({required this.id})
    : contentUri = Uri.parse('content://mms-sms/canonical-address/$id');

  Future<String> fetch() async {
    final List<Map<String, dynamic>> responses = (await QueryInterop.query(
      QueryObj(
        contentUri: contentUri,
        projection: ['address'],
        selection: null,
        selectionArgs: null,
        sortOrder: null,
      ),
    ));

    return responses.isEmpty ? null : responses.first.values.first;
  }
}
