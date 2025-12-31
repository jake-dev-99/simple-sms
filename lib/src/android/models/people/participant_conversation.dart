// DEPRECATED: This model is not currently used by simple_sms or consuming applications.
// It was created for potential future conversation participant features but remains unused.
// Consider removing in a future major version.

import '../conversations/mms_sms_simple_conversations.dart';
import '../enums/contact_enums.dart';
import '../../../interfaces/models_interface.dart';
import 'contactables.dart';

class ConversationParticipant implements ModelInterface {
  @override
  final int id;
  @override
  final Map<String, dynamic>? sourceMap;

  final Contactable contactable;
  final AndroidSimpleConversation conversation;
  final AndroidParticipantType? type;

  ConversationParticipant({
    required this.contactable,
    required this.conversation,
    this.type,
    this.sourceMap = const {},
  }) : id = contactable.contactId;

  factory ConversationParticipant.fromContact({
    required Contactable contactable,
    required AndroidSimpleConversation conversation,
    required AndroidParticipantType? type,
  }) => ConversationParticipant(
    contactable: contactable,
    conversation: conversation,
    type: type,
    sourceMap: {
      'contact': contactable.sourceMap,
      'conversation': conversation.sourceMap,
    },
  );

  factory ConversationParticipant.fromJson(Map<String, dynamic> json) =>
      ConversationParticipant(
        contactable: Contactable.fromJson(json['contactable']),
        conversation: AndroidSimpleConversation.fromRaw(json['conversation']),
        type: AndroidParticipantType.values[json['type']],
        sourceMap: json,
      );

  Map<String, dynamic> toJson() => {
    'id': id,
    'contactable': contactable.toJson(),
    'conversation': conversation.sourceMap,
    'sourceMap': sourceMap,
  };
}
