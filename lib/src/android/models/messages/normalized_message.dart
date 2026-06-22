import '../enums/sms_mms_enums.dart';
import '../people/message_participant.dart';
import 'mms.dart';
import 'mms_part.dart';
import 'sms.dart';

/// Whether a [NormalizedMessage] was sent by the local user or received.
///
/// Resolved at the plugin boundary from the source row's direction columns
/// (`Sms.type`; `Mms.m_type`), so a consumer never re-derives direction from
/// raw columns — the inversion that produced the "Unknown Sender" class of
/// bug (ADR-0011), generalized to the whole message (ADR-0014).
enum MessageDirection {
  /// The local user received this message.
  inbound,

  /// The local user sent this message.
  outbound,

  /// Direction could not be determined from the source row (e.g. an MMSC
  /// confirmation PDU that is neither inbound nor outbound in our DB).
  unknown,
}

/// Normalized transport/delivery state of a message.
///
/// This is the **delivery** axis only — strictly distinct from the local
/// user's read-state (`isRead`/`readAt`), which is app-owned overlay and is
/// not represented here. Read-report PDUs (`readReceivedInd` /
/// `readOriginatedInd`) report that the *remote* party read our *outbound*
/// message; they resolve to [delivered] on this axis and never touch local
/// read-state.
enum MessageDeliveryState {
  /// A locally-composed draft, not yet sent.
  draft,

  /// Handed to the platform / on its way out; no carrier ack yet.
  sending,

  /// Carrier-acked send. A delivery report may or may not have arrived.
  sent,

  /// Delivered (inbound rows are delivered-by-definition; outbound rows with
  /// a delivery-ind are promoted here).
  delivered,

  /// The send failed (SMS `failed` type; MMS `msg_box = failed`).
  failed,

  /// State could not be determined from the source row.
  unknown,
}

/// A binary attachment on a [NormalizedMessage], described for on-demand
/// fetch.
///
/// The bytes are **not** carried here — under the provider model the binary
/// stays in the native store and is fetched on demand (via
/// `LookupService.extractMmsPart` keyed by [partId]), not eagerly copied.
/// The `text/plain` body part and the SMIL layout part are excluded (their
/// content is folded into [NormalizedMessage.body]). Other `text/*` parts
/// (vCard, vCalendar, html) are currently excluded too — a pre-existing gap
/// (UNFY-223) where they should instead surface as attachments.
class NormalizedAttachment {
  const NormalizedAttachment({
    required this.partId,
    required this.mimeType,
    required this.category,
    this.fileName,
    this.contentId,
  });

  /// The MMS part `_id` — the key `LookupService.extractMmsPart` needs to
  /// stream the bytes on demand.
  final int partId;

  /// The raw MIME string as the provider stored it (e.g. `image/jpeg`).
  final String mimeType;

  /// The top-level MIME category, for renderer selection without re-parsing
  /// the MIME string.
  final MimeCategory category;

  /// A display filename, when the part carried one.
  final String? fileName;

  /// The part's content-id, when present (used by SMIL layout references).
  final String? contentId;
}

/// A single, source-agnostic message as the provider hands it to the host.
///
/// This is the **provider contract** (ADR-0014): SMS and MMS are normalized
/// at the plugin boundary into one shape, with body extracted, direction and
/// delivery-state derived, participants split, and attachments described —
/// so the host carries **zero** source-specific interpretation (no SMIL
/// parsing, no PDU-type tables, no `participantType` splitting). It
/// generalizes ADR-0011's participant contract from one field to the whole
/// message.
///
/// What is **not** here, by design: the host's app-owned overlay state
/// (local read-state, soft-delete tombstones, snooze/park, classification,
/// Person/Circles identity). The provider owns the external system's truth;
/// the host owns the value it adds on top. [read] and [seen] are the *native
/// store's* flags (external truth) — the host maintains its own read-state
/// overlay and decides how to reconcile (the read-state-authority decision,
/// tracked separately).
class NormalizedMessage {
  const NormalizedMessage({
    required this.id,
    required this.threadId,
    required this.channel,
    required this.direction,
    required this.deliveryState,
    required this.body,
    required this.sender,
    required this.recipients,
    required this.attachments,
    required this.read,
    required this.seen,
    this.sentAt,
    this.subscriptionId,
    this.simSlot,
    this.subject,
  });

  /// The native row id (`_id`). The host composes its own external id from
  /// [channel] + [id]; the provider does not bake a `SMS-`/`MMS-` prefix in.
  final int id;

  /// The native conversation/thread id.
  final int threadId;

  /// Which underlying provider produced this message.
  final SmsMmsType channel;

  /// Inbound vs outbound, resolved at the boundary.
  final MessageDirection direction;

  /// Normalized delivery state (distinct from local read-state).
  final MessageDeliveryState deliveryState;

  /// The message body, already extracted — including from a `text/plain`
  /// part or SMIL layout for MMS. Empty string when there is no text (a
  /// legitimate attachment-only MMS); never a synthesized placeholder.
  final String body;

  /// The sender, resolved at the boundary. `null` when the source row has no
  /// recorded sender (e.g. an outbound SMS, whose local line the provider
  /// does not store).
  final MessageParticipant? sender;

  /// The recipients, resolved at the boundary.
  final List<MessageParticipant> recipients;

  /// Binary attachments described for on-demand fetch (text/SMIL excluded).
  final List<NormalizedAttachment> attachments;

  /// The native store's read flag (external truth, not the host overlay).
  final bool read;

  /// The native store's seen flag (external truth, not the host overlay).
  final bool seen;

  /// When the message was sent/received, per the source row's date column.
  final DateTime? sentAt;

  /// The SIM subscription id the message used, when recorded.
  final int? subscriptionId;

  /// The SIM slot the message used, when recorded.
  final int? simSlot;

  /// The MMS subject, when present. Always `null` for SMS.
  final String? subject;

  /// Normalize an SMS row into the source-agnostic contract.
  factory NormalizedMessage.fromSms(Sms sms) {
    return NormalizedMessage(
      id: sms.id,
      threadId: sms.threadId,
      channel: SmsMmsType.sms,
      direction: _smsDirection(sms.type),
      deliveryState: _smsDeliveryState(sms.type),
      body: sms.body ?? '',
      sender: sms.sender,
      recipients: <MessageParticipant>[sms.recipient],
      attachments: const <NormalizedAttachment>[],
      read: sms.read ?? false,
      seen: sms.seen ?? false,
      sentAt: sms.date,
      subscriptionId: sms.subscriptionId,
      simSlot: sms.simSlot,
    );
  }

  /// Normalize an MMS row (with its hydrated [parts] and [addresses]) into the
  /// source-agnostic contract.
  ///
  /// [parts] and [addresses] are the separately-fetched hydration that
  /// `listMms` does not inline (it returns `parts: []` / `recipients: []` by
  /// design). When omitted, falls back to whatever the [mms] already carries.
  /// Participants are split by the source-agnostic [MessageParticipant.role]
  /// (ADR-0011), not the raw `participantType` byte.
  factory NormalizedMessage.fromMms(
    Mms mms, {
    List<MmsPart>? parts,
    List<MessageParticipant>? addresses,
  }) {
    final effectiveParts = parts ?? mms.parts ?? const <MmsPart>[];

    final MessageParticipant? sender = addresses != null
        ? addresses
            .where((a) => a.role == ParticipantRole.sender)
            .firstOrNull
        : mms.sender;
    final List<MessageParticipant> recipients = addresses != null
        ? addresses.where((a) => a.role != ParticipantRole.sender).toList()
        : <MessageParticipant>[...?mms.recipients];

    final body = mms.body.isNotEmpty
        ? mms.body
        : (_extractTextBody(effectiveParts) ?? '');

    final attachments = effectiveParts
        // TODO(UNFY-223): `!p.isText` also drops vCard/vCalendar/html parts
        // that belong here — narrow to the text/plain body + SMIL only.
        .where((p) => !p.isText && !p.isSmil)
        .map(
          (p) => NormalizedAttachment(
            partId: p.id,
            mimeType: p.contentType.value,
            category: p.contentType.category,
            fileName: p.fileName ?? p.name,
            contentId: p.contentId,
          ),
        )
        .toList(growable: false);

    return NormalizedMessage(
      id: mms.id,
      threadId: mms.threadId,
      channel: SmsMmsType.mms,
      direction: _mmsDirection(mms.type),
      deliveryState: _mmsDeliveryState(mms.type, mms.messageBox),
      body: body,
      sender: sender,
      recipients: recipients,
      attachments: attachments,
      read: mms.read,
      seen: mms.seen ?? false,
      sentAt: mms.date,
      subscriptionId: mms.subscriptionId,
      simSlot: mms.simSlot,
      subject: mms.subject,
    );
  }

  /// Assemble one thread's messages into the normalized contract — SMS plus
  /// the **user-visible** MMS (transport-only PDUs are dropped per
  /// [MmsMessageType.isUserVisible]) — each normalized, merged, and sorted by
  /// [sentAt] (newest first unless [ascending]).
  ///
  /// MMS hydration is supplied by the caller (the provider fetches parts +
  /// addresses per message): [partsByMmsId] / [addressesByMmsId] keyed by MMS
  /// id; a missing entry normalizes that MMS with no parts/addresses. Rows
  /// with a null [sentAt] sort last regardless of direction.
  static List<NormalizedMessage> assembleThread({
    required List<Sms> sms,
    required List<Mms> mms,
    Map<int, List<MmsPart>> partsByMmsId = const {},
    Map<int, List<MessageParticipant>> addressesByMmsId = const {},
    bool ascending = false,
  }) {
    final out = <NormalizedMessage>[
      for (final s in sms) NormalizedMessage.fromSms(s),
      for (final m in mms)
        if (m.type?.isUserVisible ?? false)
          NormalizedMessage.fromMms(
            m,
            parts: partsByMmsId[m.id],
            addresses: addressesByMmsId[m.id],
          ),
    ];
    out.sort((a, b) {
      final at = a.sentAt;
      final bt = b.sentAt;
      if (at == null || bt == null) {
        if (at == null && bt == null) return 0;
        return at == null ? 1 : -1; // nulls last
      }
      final cmp = at.compareTo(bt);
      return ascending ? cmp : -cmp;
    });
    return out;
  }
}

// ── Direction / delivery-state derivation ───────────────────────────────
//
// Ported from the host app's `enum_type_converter.dart` (which is deleted at
// the Unify cutover, ADR-0014). These encode WAP-MMS PduHeaders / Android
// `type`-column semantics — source-format knowledge that belongs at the
// plugin boundary. Target enums are the plugin's own normalized vocabulary,
// not the host's `MessageType`/`MessageStatus`/`MessageLifecycle`.

MessageDirection _smsDirection(SmsMessageType? type) {
  switch (type) {
    case SmsMessageType.inbox:
    // `all` is a query-filter sentinel, not a real row value; if it leaks
    // into a row, treat as inbound (matches the host's prior behaviour).
    case SmsMessageType.all:
      return MessageDirection.inbound;
    case SmsMessageType.sent:
    case SmsMessageType.draft:
    case SmsMessageType.outbox:
    case SmsMessageType.queued:
    case SmsMessageType.failed:
      return MessageDirection.outbound;
    case null:
      return MessageDirection.unknown;
  }
}

MessageDeliveryState _smsDeliveryState(SmsMessageType? type) {
  switch (type) {
    case SmsMessageType.draft:
      return MessageDeliveryState.draft;
    case SmsMessageType.outbox:
    case SmsMessageType.queued:
      return MessageDeliveryState.sending;
    case SmsMessageType.sent:
      // Carrier-acked send; a DLR may promote sent → delivered later. This
      // layer can't tell, so it stays `sent` (not `delivered`).
      return MessageDeliveryState.sent;
    case SmsMessageType.failed:
      return MessageDeliveryState.failed;
    case SmsMessageType.inbox:
    case SmsMessageType.all:
      return MessageDeliveryState.delivered;
    case null:
      return MessageDeliveryState.unknown;
  }
}

MessageDirection _mmsDirection(MmsMessageType? type) {
  switch (type) {
    // Outbound (we originated the PDU).
    case MmsMessageType.sendRequest:
    case MmsMessageType.notifyRespInd:
    case MmsMessageType.acknowledgeInd:
    case MmsMessageType.readOriginatedInd:
    case MmsMessageType.forwardRequestInd:
      return MessageDirection.outbound;
    // Inbound (we received the PDU).
    case MmsMessageType.notificationInd:
    case MmsMessageType.retrieveConfirmationInd:
    case MmsMessageType.deliveryInd:
    case MmsMessageType.readReceivedInd:
      return MessageDirection.inbound;
    // Carrier-side confirmations, not directional in our DB.
    case MmsMessageType.sendConf:
    case MmsMessageType.forwardConf:
    case null:
      return MessageDirection.unknown;
  }
}

MessageDeliveryState _mmsDeliveryState(MmsMessageType? type, MessageBox? box) {
  // The PDU `m_type` is the immutable header (`SEND_REQ` for every outbound
  // MMS) and can't represent a send failure — that only shows in `msg_box`.
  // So a failed box overrides the type-derived state (UNFY-178).
  if (box == MessageBox.failed) return MessageDeliveryState.failed;
  switch (type) {
    case MmsMessageType.sendRequest:
    case MmsMessageType.notificationInd:
    case MmsMessageType.forwardRequestInd:
      return MessageDeliveryState.sending;
    case MmsMessageType.sendConf:
      return MessageDeliveryState.sent;
    case MmsMessageType.notifyRespInd:
    case MmsMessageType.retrieveConfirmationInd:
    case MmsMessageType.acknowledgeInd:
    case MmsMessageType.deliveryInd:
    case MmsMessageType.forwardConf:
    case MmsMessageType.readReceivedInd:
    case MmsMessageType.readOriginatedInd:
      return MessageDeliveryState.delivered;
    case null:
      return MessageDeliveryState.unknown;
  }
}

// ── Body extraction ─────────────────────────────────────────────────────
//
// Ported verbatim from the host's `message_type_converter.dart`
// (_extractTextBody / _extractTextFromSmil). `Mms.body` is the plugin's
// concatenated text/plain rollup but comes back empty on attachment-only
// MMS and intermittently when the body lives only in the SMIL XML; scan
// parts as a secondary source. Returns null when no real text exists —
// callers must not synthesize a placeholder.

// Hoisted to file scope so each pattern compiles once, not on every MMS
// normalized (the inline form recompiled per `_extractTextFromSmil` call).
final RegExp _smilInlineTextRegex =
    RegExp(r'<text[^>]*>([^<]+)</text>', caseSensitive: false);
final RegExp _smilCidRegex = RegExp(
  r'''<text[^>]*\bsrc\s*=\s*["']cid:([^"']+)["']''',
  caseSensitive: false,
);

String? _extractTextBody(List<MmsPart> parts) {
  if (parts.isEmpty) return null;
  for (final part in parts) {
    if (part.isText && (part.text?.isNotEmpty ?? false)) {
      return part.text;
    }
  }
  return _extractTextFromSmil(parts);
}

String? _extractTextFromSmil(List<MmsPart> parts) {
  String? xml;
  for (final p in parts) {
    if (p.isSmil && (p.text?.isNotEmpty ?? false)) {
      xml = p.text;
      break;
    }
  }
  if (xml == null || xml.isEmpty) return null;

  final inlineMatch = _smilInlineTextRegex.firstMatch(xml);
  final inline = inlineMatch?.group(1)?.trim();
  if (inline != null && inline.isNotEmpty) return inline;

  final cidMatch = _smilCidRegex.firstMatch(xml);
  final cid = cidMatch?.group(1);
  if (cid == null) return null;

  for (final p in parts) {
    if (!p.isText) continue;
    final matches =
        p.contentId == cid || p.fileName == cid || p.name == cid;
    if (matches && (p.text?.isNotEmpty ?? false)) {
      return p.text;
    }
  }
  return null;
}
