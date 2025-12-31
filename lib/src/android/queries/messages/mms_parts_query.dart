import 'package:simple_sms/src/android/models/queries/query_obj.dart';
import 'dart:core';
import 'package:flutter/foundation.dart' show debugPrint;

import '../../../interfaces/query_interfaces.dart';
import '../../models/messages/mms_part.dart';

class MmsPartsQuery with QueryBuilder, FileBuilder {
  const MmsPartsQuery({
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
  Uri get contentUri =>
      lookupKey.isEmpty
          ? Uri.parse('content://mms/part/')
          : Uri.parse('content://mms/$lookupKey/part/');

  @override
  List<String> get projection => columns.map((e) => e.toString()).toList();
  @override
  String get selection => _selection ?? '';
  @override
  List<String> get selectionArgs => _selectionArgs ?? const [];
  @override
  String get sortOrder => _sortOrder ?? '';

  @override
  Future<List<MmsPart>> fetch() async {
    final List<Map<String, dynamic>> parts = await super.select(
      QueryObj(
        contentUri: contentUri,
        projection: projection,
        selection: selection,
        selectionArgs: selectionArgs,
        sortOrder: sortOrder,
      ),
    );

    final List<MmsPart> finalParts = [];
    for (final part in parts) {
      try {
        MmsPart mmsPart = MmsPart.fromRaw(part);
        if (!mmsPart.contentType.value.contains('text') &&
            !mmsPart.contentType.value.contains('smil')) {}
        finalParts.add(mmsPart);
      } catch (e, s) {
        debugPrint(e.toString());
        debugPrint(s.toString());
      }
    }
    return finalParts;
  }

  @override
  Future<String?> downloadFile(Uri contentUri) async {
    final String? filePath = await super.downloadFile(contentUri);
    return filePath;
  }
}
