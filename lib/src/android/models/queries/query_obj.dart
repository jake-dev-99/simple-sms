class QueryObj {
  QueryObj({
    required this.contentUri,
    this.projection,
    this.selection,
    this.selectionArgs,
    this.sortOrder,
  });

  Uri contentUri;
  List<String>? projection;
  String? selection;
  List<String>? selectionArgs;
  String? sortOrder;

  QueryObj copyWith({
    Uri? contentUri,
    List<String>? projection,
    String? selection,
    List<String>? selectionArgs,
    String? sortOrder,
  }) {
    return QueryObj(
      contentUri: contentUri ?? this.contentUri,
      projection: projection ?? this.projection,
      selection: selection ?? this.selection,
      selectionArgs: selectionArgs ?? this.selectionArgs,
      sortOrder: sortOrder ?? this.sortOrder,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'contentUri': contentUri.toString(),
      'projection': projection,
      'selection': selection,
      'selectionArgs': selectionArgs,
      'sortOrder': sortOrder,
    };
  }

  factory QueryObj.fromJson(Map<String, dynamic> map) {
    return QueryObj(
      contentUri: Uri.parse(map['contentUri']),
      projection:
          map['projection'] == null
              ? null
              : List<String>.from(map['projection'] as List<String>),
      selection: map['selection'] == null ? null : map['selection'] as String,
      selectionArgs:
          map['selectionArgs'] == null
              ? null
              : List<String>.from(map['selectionArgs'] as List<String>),
      sortOrder: map['sortOrder'] == null ? null : map['sortOrder'] as String,
    );
  }
}
