import '../android/models/queries/query_obj.dart';
import 'query_fields_interface.dart';
import '../interfaces/models_interface.dart';
import 'package:flutter/services.dart';

abstract class QueryInterface {
  const QueryInterface({required this.columns, required this.lookupKey});

  /// The ID of the item to query - this is required for some queries (ie, "content://mms/123/addr")
  final String lookupKey;

  /// List of selected columns to use for the projection - leave empty for all
  final List<QueryFieldNamesInterface> columns;

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

  /// This parses the implemented query and builds a QueryObj
  /// which is used to query the database
  Future<List<ModelInterface>> get query async => throw UnimplementedError();
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

  Future<List<dynamic>> select(
    QueryObj queryObj, [
    List<String>? params,
  ]) async {
    final String channelName =
        'dev.flutter.pigeon.unify_messages_plus.CuriousPigeon.query';
    final BasicMessageChannel<Object?> channel = BasicMessageChannel<Object?>(
      channelName,
      StandardMessageCodec(),
    );
    final Future<Object?> sendFuture = channel.send(<Object?>[query]);
    final List<Object?>? replylist = await sendFuture as List<Object?>?;
    if (replylist == null) {
      throw PlatformException(
        code: replylist?[0]! as String,
        message: replylist?[1] as String?,
        details: replylist?[2],
      );
    } else if (replylist[0] == null) {
      throw PlatformException(
        code: 'null-error',
        message: 'Host platform returned null value for non-null return value.',
      );
    } else {
      return (replylist[0] as List<Object?>?)!.cast<Map<String, dynamic>>();
    }
  }
}

// TODO - This will need it's own method channel
mixin DeviceBuilder {
  Future<Map<String, dynamic>> getDeviceInfo() async {
    final String channelName =
        'dev.flutter.pigeon.unify_messages_plus.CuriousPigeon.query';
    final BasicMessageChannel<Object?> channel = BasicMessageChannel<Object?>(
      channelName,
      StandardMessageCodec(),
    );
    final Future<Object?> sendFuture = channel.send(<Object?>[]);
    final List<Object?>? replylist = await sendFuture as List<Object?>?;
    if (replylist == null) {
      throw PlatformException(
        code: replylist?[0]! as String,
        message: replylist?[1] as String?,
        details: replylist?[2],
      );
    } else if (replylist[0] == null) {
      throw PlatformException(
        code: 'null-error',
        message: 'Host platform returned null value for non-null return value.',
      );
    }
    return (replylist[0] as Map<String, dynamic>);
  }
}

// TODO - This will need it's own metfhod channel
mixin SimCardBuilder {
  Future<List<Map<String, dynamic>>> getSimCards() async {
    final String channelName =
        'dev.flutter.pigeon.unify_messages_plus.CuriousPigeon.query';
    final BasicMessageChannel<Object?> channel = BasicMessageChannel<Object?>(
      channelName,
      StandardMessageCodec(),
    );
    final Future<Object?> sendFuture = channel.send(<Object?>[]);
    final List<Object?>? replylist = await sendFuture as List<Object?>?;
    if (replylist == null) {
      throw PlatformException(
        code: replylist?[0]! as String,
        message: replylist?[1] as String?,
        details: replylist?[2],
      );
    } else if (replylist[0] == null) {
      throw PlatformException(
        code: 'null-error',
        message: 'Host platform returned null value for non-null return value.',
      );
    }
    return (replylist[0] as List<Object?>?)!.cast<Map<String, dynamic>>();
  }
}
