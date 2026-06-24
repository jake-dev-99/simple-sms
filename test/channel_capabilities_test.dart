import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/android.dart';

/// Pins the Android SMS/MMS provider's capability flags (ADR-0014,
/// UNFY-212). These are protocol facts — flipping any one of these
/// silently would break the host's generic renderer (or worse, advertise a
/// feature the wire format can't deliver). Tests are not nitpicks: they
/// are the contract the host trusts.

void main() {
  group('androidSmsMmsCapabilities', () {
    test('declares the four read/write basics SMS/MMS supports', () {
      const c = androidSmsMmsCapabilities;
      expect(c.canSend, true,
          reason: 'OutboundMessagingHandler exists on every supported Android');
      expect(c.canReceive, true,
          reason: 'InboundMessaging broadcast receiver exists');
      expect(c.canDelete, true,
          reason: 'AndroidDestructiveAction.deleteMessage/Thread');
      expect(c.canMarkRead, true,
          reason: 'AndroidAction.markMessageAsRead/markConversationAsRead');
    });

    test('declares the message-shape capabilities SMS/MMS carries', () {
      const c = androidSmsMmsCapabilities;
      expect(c.supportsAttachments, true,
          reason: 'MMS carries binary parts; outbound auto-promotes from SMS');
      expect(c.supportsGroupConversations, true,
          reason: 'Multi-recipient threads ride as group MMS');
      expect(c.supportsDeliveryReceipts, true,
          reason: 'MMS M-delivery.ind (PDU 0x86); SMS status column');
      expect(c.supportsReadReceipts, true,
          reason: 'MMS M-read-rec.ind (PDU 0x87)');
    });

    test(
        'declares the three protocol gaps classic SMS/MMS does NOT have '
        '(must stay false until / unless RCS support lands)', () {
      const c = androidSmsMmsCapabilities;
      expect(c.supportsReactions, false,
          reason:
              'no native reaction PDU; Apple tapbacks rendered into the body '
              'are not a protocol capability');
      expect(c.supportsEdits, false,
          reason: 'a dispatched SMS/MMS PDU cannot be recalled or rewritten');
      expect(c.supportsTypingIndicators, false,
          reason: 'no typing-status signal in classic SMS/MMS');
    });
  });

  group('LookupService.capabilities', () {
    test('exposes the same SMS/MMS constant (so the host queries one place)',
        () {
      // Identity, not equality — the façade must return THE constant, not
      // a freshly-built equivalent (so consumers can cache by identity).
      expect(
          identical(LookupService().capabilities, androidSmsMmsCapabilities),
          true);
    });
  });
}
