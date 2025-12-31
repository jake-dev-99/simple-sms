abstract interface class ModelInterface {
  const ModelInterface({required this.id, this.sourceMap});

  final int? id;
  final Map<String, dynamic>? sourceMap;

  factory ModelInterface.fromJson(Map<String, dynamic> json) =>
      throw UnimplementedError(); // TODO
}
