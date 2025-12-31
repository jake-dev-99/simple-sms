import 'package:simple_sms/src/interfaces/models_interface.dart';

import '../interop/query_interop.dart';
import '../android/models/queries/query_obj.dart';

abstract interface class QueryInterface {
  const QueryInterface({
    this.columns = const [],
    this.lookupKey = '',
    selection,
    selectionArgs,
  });

  /// The ID of the item to query - this is required for some queries (ie, "content://mms/123/addr")
  final String lookupKey;

  /// List of selected columns to use for the projection - leave empty for all
  final List<String> columns;

  /// The content URI to use for the query - this should only be used within an implementation of the QueryInterface
  Uri get contentUri;

  /// The projection to use for the query - Likely only needed for the mixin to parse the selected columns
  List<String> get projection;

  /// SQL-like selection string to use for the query (ie, "_id = ? AND address = ?")
  String get selection;

  /// The arguments to use for the selection string (ie, ["123", "456"])
  List<String> get selectionArgs;

  /// The sort order to use for the query (ie, "_id ASC, address DESC")
  String get sortOrder;

  Future<List<ModelInterface>> fetch();
}

/// Houses all core query functions, include updates, inserts, and deletes
/// All queries should extend this mixin, and all fields can be overridden by the implementing class
/// if non-default values are needed
mixin QueryBuilder implements QueryInterface {
  /// This is the query that will be used to get the data from the database
  QueryObj get queryObj => QueryObj(
    contentUri: contentUri,
    projection: projection,
    selection: selection,
    selectionArgs: selectionArgs,
    sortOrder: sortOrder,
  );

  @override
  String get selection => '';
  @override
  List<String> get selectionArgs => [];
  @override
  String get sortOrder => '';
  @override
  List<String> get projection => columns.map((e) => e.toString()).toList();

  Future<List<Map<String, dynamic>>> select(
    QueryObj queryObj, [
    List<String>? params,
  ]) async => QueryInterop.query(queryObj);
}

mixin AndroidDeviceBuilder {
  Future<Map<String, dynamic>> getDeviceInfo() async =>
      QueryInterop.queryAndroidDeviceInfo();
}

mixin SimCardBuilder {
  Future<List<Map<String, dynamic>>> getSimCards() async =>
      QueryInterop.querySimInfo();
}

mixin FileBuilder {
  Future<String?> downloadFile(Uri contentUri) async =>
      QueryInterop.downloadFile(contentUri);
}
