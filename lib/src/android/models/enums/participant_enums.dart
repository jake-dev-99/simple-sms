// DEPRECATED: This enum duplicates AndroidParticipantType in contact_enums.dart.
// Use AndroidParticipantType from contact_enums.dart instead.
// This file can be safely removed in a future major version.

enum ParticipantType {
  bcc(value: 0x81),
  cc(value: 0x82),
  from(value: 0x89),
  to(value: 0x97);

  const ParticipantType({required this.value});
  final int value;

  static ParticipantType? fromValue(int v) {
    try {
      return ParticipantType.values.firstWhere((e) => e.value == v);
    } catch (e) {
      return null;
    }
  }
}
