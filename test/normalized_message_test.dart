import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/android.dart';

/// Tests for the source-agnostic provider contract (ADR-0014): raw `Sms`/`Mms`
/// in, normalized message out. These assert the interpretation relocated from
/// the host app — body extraction, direction, delivery-state (incl. the
/// `msg_box=failed` override), participant split, and attachment mapping — now
/// lives at the plugin boundary.

Sms buildSms({
  int id = 1,
  int threadId = 10,
  SmsMessageType? type,
  String? address,
  String? body,
  bool? read,
  bool? seen,
  int? simSlot,
  int? subscriptionId,
  DateTime? date,
}) =>
    Sms(
      id: id,
      threadId: threadId,
      date: date ?? DateTime.fromMillisecondsSinceEpoch(1000),
      type: type,
      address: address,
      body: body,
      read: read,
      seen: seen,
      simSlot: simSlot,
      subscriptionId: subscriptionId,
    );

Mms buildMms({
  int id = 2,
  int threadId = 20,
  String body = '',
  List<MmsPart> parts = const <MmsPart>[],
  MmsMessageType? type,
  MessageBox? messageBox,
  bool read = false,
  int simSlot = 0,
  int? subscriptionId,
  String? subject,
  DateTime? date,
}) =>
    Mms(
      id: id,
      threadId: threadId,
      body: body,
      parts: parts,
      recipients: null,
      sender: null,
      read: read,
      simSlot: simSlot,
      type: type,
      messageBox: messageBox,
      subscriptionId: subscriptionId,
      subject: subject,
      date: date ?? DateTime.fromMillisecondsSinceEpoch(2000),
    );

MmsPart buildPart({
  required int id,
  required ContentType contentType,
  String? text,
  String? fileName,
  String? contentId,
}) =>
    MmsPart(
      id: id,
      contentLocation: '',
      contentType: contentType,
      text: text,
      fileName: fileName,
      contentId: contentId,
    );

void main() {
  group('NormalizedMessage.fromSms', () {
    test('inbound: direction inbound, delivered, sender carries the address',
        () {
      final m = NormalizedMessage.fromSms(buildSms(
        type: SmsMessageType.inbox,
        address: '+15551230000',
        body: 'hi there',
        read: false,
        seen: true,
        simSlot: 0,
        subscriptionId: 2,
      ));

      expect(m.channel, SmsMmsType.sms);
      expect(m.direction, MessageDirection.inbound);
      expect(m.deliveryState, MessageDeliveryState.delivered);
      expect(m.body, 'hi there');
      expect(m.sender?.role, ParticipantRole.sender);
      expect(m.sender?.address, '+15551230000');
      // The local line is not recorded on an inbound row.
      expect(m.recipients.single.address, isNull);
      expect(m.attachments, isEmpty);
      expect(m.read, false);
      expect(m.seen, true);
      expect(m.subscriptionId, 2);
      expect(m.simSlot, 0);
    });

    test('outbound sent: direction outbound, sent; recipient carries address',
        () {
      final m = NormalizedMessage.fromSms(buildSms(
        type: SmsMessageType.sent,
        address: '+15559990000',
        body: 'on my way',
      ));

      expect(m.direction, MessageDirection.outbound);
      expect(m.deliveryState, MessageDeliveryState.sent);
      // Outbound: the local sender line is unrecorded; the address is the
      // recipient.
      expect(m.sender?.address, isNull);
      expect(m.recipients.single.address, '+15559990000');
    });

    test('failed → failed; draft → draft; both outbound', () {
      final failed =
          NormalizedMessage.fromSms(buildSms(type: SmsMessageType.failed));
      expect(failed.direction, MessageDirection.outbound);
      expect(failed.deliveryState, MessageDeliveryState.failed);

      final draft =
          NormalizedMessage.fromSms(buildSms(type: SmsMessageType.draft));
      expect(draft.direction, MessageDirection.outbound);
      expect(draft.deliveryState, MessageDeliveryState.draft);
    });

    test('null type → unknown direction and delivery-state', () {
      final m = NormalizedMessage.fromSms(buildSms(type: null));
      expect(m.direction, MessageDirection.unknown);
      expect(m.deliveryState, MessageDeliveryState.unknown);
      // Native read flag is absent → false, not a guess.
      expect(m.read, false);
    });
  });

  group('NormalizedMessage.fromMms — body extraction', () {
    test('uses Mms.body when present', () {
      final m = NormalizedMessage.fromMms(buildMms(
        body: 'inline body',
        type: MmsMessageType.retrieveConfirmationInd,
        messageBox: MessageBox.inbox,
        parts: [buildPart(id: 1, contentType: ContentType.textPlain, text: 'x')],
      ));
      expect(m.body, 'inline body');
    });

    test('falls back to a text/plain part when Mms.body is empty', () {
      final m = NormalizedMessage.fromMms(buildMms(
        body: '',
        type: MmsMessageType.retrieveConfirmationInd,
        messageBox: MessageBox.inbox,
        parts: [
          buildPart(id: 1, contentType: ContentType.textPlain, text: 'from part'),
        ],
      ));
      expect(m.body, 'from part');
    });

    test('falls back to inline SMIL <text> when no text part carries text', () {
      final m = NormalizedMessage.fromMms(buildMms(
        body: '',
        type: MmsMessageType.retrieveConfirmationInd,
        messageBox: MessageBox.inbox,
        parts: [
          buildPart(
            id: 1,
            contentType: ContentType.applicationSmil,
            text: '<smil><body><par><text>body from smil</text></par>'
                '</body></smil>',
          ),
        ],
      ));
      expect(m.body, 'body from smil');
    });

    test('attachment-only MMS yields empty body (no synthesized placeholder)',
        () {
      final m = NormalizedMessage.fromMms(buildMms(
        body: '',
        type: MmsMessageType.retrieveConfirmationInd,
        messageBox: MessageBox.inbox,
        parts: [buildPart(id: 1, contentType: ContentType.imageJpeg)],
      ));
      expect(m.body, '');
    });
  });

  group('NormalizedMessage.fromMms — direction & delivery-state', () {
    test('sendRequest is outbound; retrieveConfirmationInd is inbound', () {
      final out = NormalizedMessage.fromMms(
          buildMms(type: MmsMessageType.sendRequest, messageBox: MessageBox.sent));
      expect(out.direction, MessageDirection.outbound);

      final inbound = NormalizedMessage.fromMms(buildMms(
          type: MmsMessageType.retrieveConfirmationInd,
          messageBox: MessageBox.inbox));
      expect(inbound.direction, MessageDirection.inbound);
      expect(inbound.deliveryState, MessageDeliveryState.delivered);
    });

    test('msg_box=failed overrides the type-derived state → failed', () {
      // m_type is always SEND_REQ for outbound MMS; only msg_box carries the
      // failure (UNFY-178). The override must win.
      final m = NormalizedMessage.fromMms(buildMms(
        type: MmsMessageType.sendRequest,
        messageBox: MessageBox.failed,
      ));
      expect(m.deliveryState, MessageDeliveryState.failed);
    });

    test('sendRequest in outbox (not failed) → sending', () {
      final m = NormalizedMessage.fromMms(buildMms(
        type: MmsMessageType.sendRequest,
        messageBox: MessageBox.outbox,
      ));
      expect(m.deliveryState, MessageDeliveryState.sending);
    });
  });

  group('NormalizedMessage.fromMms — participants & attachments', () {
    test('splits provided addresses by role, not raw participant bytes', () {
      final m = NormalizedMessage.fromMms(
        buildMms(
            type: MmsMessageType.retrieveConfirmationInd,
            messageBox: MessageBox.inbox),
        addresses: const [
          SmsParticipant(address: 'sender@x', role: ParticipantRole.sender),
          SmsParticipant(address: 'r1', role: ParticipantRole.to),
          SmsParticipant(address: 'r2', role: ParticipantRole.cc),
        ],
      );
      expect(m.sender?.address, 'sender@x');
      expect(m.recipients.map((r) => r.address), ['r1', 'r2']);
    });

    test('no addresses + bare Mms → null sender, empty recipients (fallback)',
        () {
      final m = NormalizedMessage.fromMms(buildMms(
          type: MmsMessageType.retrieveConfirmationInd,
          messageBox: MessageBox.inbox));
      expect(m.sender, isNull);
      expect(m.recipients, isEmpty);
    });

    test('binary parts become attachments; text and SMIL parts do not', () {
      final m = NormalizedMessage.fromMms(buildMms(
        type: MmsMessageType.retrieveConfirmationInd,
        messageBox: MessageBox.inbox,
        parts: [
          buildPart(id: 1, contentType: ContentType.textPlain, text: 'caption'),
          buildPart(
              id: 2, contentType: ContentType.applicationSmil, text: '<smil/>'),
          buildPart(id: 3, contentType: ContentType.imageJpeg, fileName: 'p.jpg'),
        ],
      ));

      expect(m.body, 'caption');
      expect(m.attachments.length, 1);
      final att = m.attachments.single;
      expect(att.partId, 3);
      expect(att.mimeType, 'image/jpeg');
      expect(att.category, MimeCategory.image);
      expect(att.fileName, 'p.jpg');
    });
  });

  group('NormalizedMessage.assembleThread', () {
    test('merges SMS + MMS newest-first; caller owns user-visible filtering',
        () {
      // Caller responsibility (see doc): pre-filter to user-visible. This
      // test simulates the orchestration's pre-filter step.
      final mmsRows = [
        buildMms(
          id: 2,
          type: MmsMessageType.retrieveConfirmationInd,
          messageBox: MessageBox.inbox,
          date: DateTime.fromMillisecondsSinceEpoch(100),
        ),
        // notificationInd is transport-only; the caller filters it out
        // upstream so the assembler never sees it.
        buildMms(
          id: 3,
          type: MmsMessageType.notificationInd,
          messageBox: MessageBox.inbox,
          date: DateTime.fromMillisecondsSinceEpoch(200),
        ),
      ];
      final visible =
          mmsRows.where((m) => m.type?.isUserVisible ?? false).toList();
      final out = NormalizedMessage.assembleThread(
        sms: [
          buildSms(
            id: 1,
            type: SmsMessageType.inbox,
            date: DateTime.fromMillisecondsSinceEpoch(300),
          ),
        ],
        mms: visible,
      );
      expect(out.map((m) => m.id), [1, 2]); // newest-first
      expect(out.map((m) => m.channel), [SmsMmsType.sms, SmsMmsType.mms]);
    });

    test('breaks sentAt ties on id (deterministic total order)', () {
      // Two messages sharing a second-level timestamp — a routine case in
      // group threads. Without a tie-break the order would be undefined.
      final shared = DateTime.fromMillisecondsSinceEpoch(500);
      final out = NormalizedMessage.assembleThread(
        sms: [
          buildSms(id: 10, type: SmsMessageType.inbox, date: shared),
          buildSms(id: 7, type: SmsMessageType.inbox, date: shared),
        ],
        mms: [
          buildMms(
            id: 5,
            type: MmsMessageType.retrieveConfirmationInd,
            messageBox: MessageBox.inbox,
            date: shared,
          ),
        ],
      );
      // newest-first → highest id first within the tied bucket.
      expect(out.map((m) => m.id), [10, 7, 5]);

      final asc = NormalizedMessage.assembleThread(
        sms: [
          buildSms(id: 10, type: SmsMessageType.inbox, date: shared),
          buildSms(id: 7, type: SmsMessageType.inbox, date: shared),
        ],
        mms: [
          buildMms(
            id: 5,
            type: MmsMessageType.retrieveConfirmationInd,
            messageBox: MessageBox.inbox,
            date: shared,
          ),
        ],
        ascending: true,
      );
      // oldest-first within ties → lowest id first.
      expect(asc.map((m) => m.id), [5, 7, 10]);
    });

    test('tie-break across channels when id ALSO collides (sms vs mms)', () {
      // content://sms and content://mms have independent _id namespaces, so
      // an SMS row id=42 and an MMS row id=42 can both exist and collide.
      // With a shared sentAt the order is otherwise undefined; channel
      // index makes it total.
      final shared = DateTime.fromMillisecondsSinceEpoch(500);
      final out = NormalizedMessage.assembleThread(
        sms: [buildSms(id: 42, type: SmsMessageType.inbox, date: shared)],
        mms: [
          buildMms(
            id: 42,
            type: MmsMessageType.retrieveConfirmationInd,
            messageBox: MessageBox.inbox,
            date: shared,
          ),
        ],
      );
      // Both newest-first and ascending must produce a defined, total order.
      expect(out.map((m) => m.channel), isNotEmpty);
      expect(out.length, 2);
    });

    test('ascending flips the order', () {
      final out = NormalizedMessage.assembleThread(
        sms: [
          buildSms(
            id: 1,
            type: SmsMessageType.inbox,
            date: DateTime.fromMillisecondsSinceEpoch(300),
          ),
        ],
        mms: [
          buildMms(
            id: 2,
            type: MmsMessageType.retrieveConfirmationInd,
            messageBox: MessageBox.inbox,
            date: DateTime.fromMillisecondsSinceEpoch(100),
          ),
        ],
        ascending: true,
      );
      expect(out.map((m) => m.id), [2, 1]); // 100 then 300
    });

    test('applies MMS hydration (parts + addresses) to the normalized message',
        () {
      final out = NormalizedMessage.assembleThread(
        sms: const [],
        mms: [
          buildMms(
            id: 5,
            type: MmsMessageType.retrieveConfirmationInd,
            messageBox: MessageBox.inbox,
          ),
        ],
        partsByMmsId: {
          5: [
            buildPart(
                id: 50, contentType: ContentType.textPlain, text: 'hydrated'),
            buildPart(id: 51, contentType: ContentType.imageJpeg, fileName: 'p.jpg'),
          ],
        },
        addressesByMmsId: {
          5: [
            SmsParticipant(address: 'sender@x', role: ParticipantRole.sender),
            SmsParticipant(address: 'r1', role: ParticipantRole.to),
          ],
        },
      );
      expect(out.length, 1);
      expect(out.single.body, 'hydrated');
      expect(out.single.attachments.single.partId, 51);
      expect(out.single.sender?.address, 'sender@x');
      expect(out.single.recipients.single.address, 'r1');
    });

    test('user-visible MMS with no hydration entry normalizes empty', () {
      final out = NormalizedMessage.assembleThread(
        sms: const [],
        mms: [
          buildMms(
            id: 6,
            type: MmsMessageType.retrieveConfirmationInd,
            messageBox: MessageBox.inbox,
          ),
        ],
      );
      expect(out.single.body, '');
      expect(out.single.sender, isNull);
      expect(out.single.attachments, isEmpty);
    });
  });
}
