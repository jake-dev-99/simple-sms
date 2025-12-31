import '../../../interfaces/models_interface.dart';
import '../model_helpers.dart';
import 'simcard.dart';

class AndroidDevice implements ModelInterface {
  AndroidDevice({
    required this.id,
    required this.parentId,
    required this.brand,
    required this.model,
    required this.os,
    required this.simCards,
    required this.sourceMap,
  });

  @override
  int id;
  @override
  Map<String, dynamic>? sourceMap;

  String parentId;
  String brand;
  String model;
  String os;
  List<AndroidSimCard> simCards;

  Map<String, dynamic> toJson() => {
    'id': id,
    'parentId': parentId,
    'sourceMap': sourceMap,
    'brand': brand,
    'model': model,
    'os': os,
    'simCards': simCards.map((card) => card.toJson()).toList(),
  };

  factory AndroidDevice.fromJson(Map<String, dynamic> json) => AndroidDevice(
    id: FieldHelper.asInt(json['id']) ?? 0,
    parentId: json['parentId']?.toString() ?? '',
    sourceMap: json['sourceMap'],
    brand: json['brand'],
    model: json['model'],
    os: json['os'],
    simCards:
        (json['sims'] as List)
            .map(
              (card) =>
                  AndroidSimCard.fromJson(Map<String, dynamic>.from(card)),
            )
            .toList(),
  );

  AndroidDevice copyWith({
    int? id,
    String? parentId,
    Map<String, dynamic>? sourceMap,
    String? brand,
    String? model,
    String? os,
    List<AndroidSimCard>? simCards,
  }) => AndroidDevice(
    id: id ?? this.id,
    parentId: parentId ?? this.parentId,
    sourceMap: sourceMap ?? this.sourceMap,
    brand: brand ?? this.brand,
    model: model ?? this.model,
    os: os ?? this.os,
    simCards: simCards ?? this.simCards,
  );
}
