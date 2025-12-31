// ignore_for_file: constant_identifier_names

enum AndroidParticipantType {
  sender(value: 0x89),
  recipient(value: 0x97),
  recipient_cc(value: 0x82),
  recipient_bcc(value: 0x81);

  const AndroidParticipantType({required this.value});
  final int value;
}

enum DisplayNameSource {
  undefined(0),
  email(10),
  phone(20),
  organization(30),
  nickname(35),
  structured(40);

  final int value;
  const DisplayNameSource(this.value);

  int get toInt => value;
}
