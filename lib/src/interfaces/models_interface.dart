/// Base interface for all Android messaging models.
///
/// Provides a common shape with an [id] field and optional [sourceMap]
/// containing the original data from which the model was constructed.
abstract interface class ModelInterface {
  const ModelInterface({required this.id, this.sourceMap});

  /// Unique database identifier for this record.
  final int? id;

  /// The original raw data map this model was constructed from.
  final Map<String, dynamic>? sourceMap;
}
