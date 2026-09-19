import '../enums/sms_mms_enums.dart';
import 'normalized_message.dart';

/// Exclusive position in the newest-first (sentAt, id, channel) ordering.
///
/// Unlike a timestamp alone, this position can resume inside an SMS/MMS tie.
class ThreadPageCursor {
  const ThreadPageCursor({
    required this.sentAt,
    required this.id,
    required this.channel,
  });

  factory ThreadPageCursor.fromMessage(NormalizedMessage message) {
    final sentAt = message.sentAt;
    if (sentAt == null) {
      throw ArgumentError.value(message, 'message', 'A cursor needs sentAt');
    }
    return ThreadPageCursor(
      sentAt: sentAt,
      id: message.id,
      channel: message.channel,
    );
  }

  final DateTime sentAt;
  final int id;
  final SmsMmsType channel;
}
