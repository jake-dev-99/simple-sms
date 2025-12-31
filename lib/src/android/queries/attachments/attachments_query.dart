import '../../../interfaces/query_interfaces.dart';
import '../../models/messages/mms_part.dart';
import '../../models/queries/query_obj.dart';

class AttachmentFields {
  static const id = '_id';
  static const parentId = 'mid';
  static const path = '_data';
  static const mimeType = 'ct';
  static const contentType = 'ct';
  static const size = 'ctt_s';
  static const name = 'name';
  static const contentUri = '_data';
  static const thumbnailUri = 'thumbnail_uri';
  static const status = 'status';
  static const createdAt = 'created_at';
  static const modifiedAt = 'modified_at';
}

class AttachmentQuery with QueryBuilder {
  const AttachmentQuery({this.lookupKey = '', this.columns = const []});
  @override
  final List<String> columns;
  @override
  final String lookupKey;
  @override
  Uri get contentUri => Uri.parse('content://mms/part/$lookupKey');

  @override
  Future<List<MmsPart>> fetch() async {
    final List<Map<String, dynamic>> attachments =
        (await super.select(
          QueryObj(
            contentUri: contentUri,
            projection: projection,
            selection: selection,
            selectionArgs: selectionArgs,
            sortOrder: sortOrder,
          ),
        )).cast<Map<String, dynamic>>();

    return attachments
        .map((attachment) => MmsPart.fromJson(attachment))
        .toList();
  }
}
