// ignore_for_file: public_member_api_docs, non_constant_identifier_names, avoid_as, unused_import, unnecessary_parenthesis, prefer_null_aware_operators, omit_local_variable_types, unused_shown_name, unnecessary_import, no_leading_underscores_for_local_identifiers

import 'dart:convert';
import 'dart:io';

import 'package:simple_sms/src/interop/query_interop.dart';

import '../models/queries/query_obj.dart';

class RawQuery {
  Future<List<Map<String, dynamic>>> fetch(QueryObj queryObj) async =>
      QueryInterop.query(queryObj).then((dynamic filepath) {
        List<Map<String, dynamic>> results = [];
        if (filepath is String && filepath.isNotEmpty) {
          Uri filepathUri = Uri.parse(filepath);

          // Retrieve the data from the file
          results.addAll(
            jsonDecode(File.fromUri(filepathUri).readAsStringSync()),
          );
        }
        return results;
      });
}
