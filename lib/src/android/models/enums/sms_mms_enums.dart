// ignore_for_file: constant_identifier_names

/// Defines the application using mode, which can affect UI layout and message density.
enum UsingMode {
  /// Normal mode: default UI and behavior.
  normal(value: 0),

  /// Compact mode: more content displayed with reduced spacing.
  compact(value: 1),

  /// Expanded mode: more space for each item, larger UI elements.
  expanded(value: 2);

  const UsingMode({required this.value});
  final int value;
}

/// Represents the type of reply action performed on a message.
enum ReplyType {
  /// No reply action.
  none(value: 0x0),

  /// Direct reply to sender only.
  reply(value: 0x1),

  /// Reply all recipients of the message.
  replyAll(value: 0x2),

  /// Forward the message to a new recipient.
  forward(value: 0x3);

  const ReplyType({required this.value});
  final int value;
}

/// Specifies the type of binary information or feature included with a message.
enum BinaryInfo {
  /// No binary content.
  none(value: 0x0),

  /// Binary content without reply actions.
  binary(value: 0x1),

  /// Binary content with "reply" option.
  binaryWithReply(value: 0x2),

  /// Binary content with "reply all" option.
  binaryWithReplyAll(value: 0x3),

  /// Binary content with "forward" option.
  binaryWithForward(value: 0x4);

  const BinaryInfo({required this.value});
  final int value;
}

/// Indicates whether a message is an SMS or MMS.
enum SmsMmsType {
  /// SMS message (text only).
  sms,

  /// MMS message (multimedia content).
  mms,
}

/// Represents the delivery or retrieval status for MMS messages (OMA MMS 7.3.39).
enum AndroidMessageStatus {
  /// The message expired before being delivered.
  expired(value: 0x80),

  /// The message was successfully retrieved by the recipient.
  retrieved(value: 0x81),

  /// Delivery was rejected.
  rejected(value: 0x82),

  /// Delivery was deferred (delayed).
  deferred(value: 0x83),

  /// Status is unrecognized (not supported or non-standard).
  unrecognized(value: 0x84),

  /// Status cannot be determined.
  indeterminate(value: 0x85),

  /// The message was forwarded from MMS relay/server.
  forwarded(value: 0x86),

  /// Recipient or service is unreachable.
  unreachable(value: 0x87);

  const AndroidMessageStatus({required this.value});
  final int value;
}

/// Indicates the priority assigned to an MMS or SMS message (OMA MMS 7.3.36).
enum MessagePriority {
  /// Low priority message.
  low(value: 0x80),

  /// Normal/default priority message.
  normal(value: 0x81),

  /// High priority message.
  high(value: 0x82);

  const MessagePriority({required this.value});
  final int value;
}

/// MMS message class (OMA MMS 7.3.35), defines intended audience/purpose.
enum MessageClass {
  /// Personal correspondence.
  personal(value: 0x80),

  /// Advertisement message.
  advertisement(value: 0x81),

  /// Informational (e.g., news or update).
  informational(value: 0x82),

  /// Automatic message/notification.
  auto(value: 0x83),

  /// Unknown or unspecified message class.
  unknown(value: 0x00);

  const MessageClass({required this.value});
  final int value;
}

/// MMS Delivery Report request flag (d_rpt: whether a report was requested), OMA MMS 7.3.17.
enum DeliveryReport {
  /// Delivery report not requested by sender.
  notRequested(0x80),

  /// Delivery report was requested by sender.
  requested(0x81),

  /// Unknown or default/no value.
  unknown(0x00);

  final int value;
  const DeliveryReport(this.value);
}

/// Describes the actual result of a delivery report for an MMS message.
enum DeliveryReportStatus {
  /// Delivery report has not yet been returned (pending).
  pending(value: 0x00),

  /// Message has been delivered to recipient.
  delivered(value: 0x01),

  /// Delivery was rejected.
  rejected(value: 0x02),

  /// Delivery expired before completion.
  expired(value: 0x03),

  /// Message has been retrieved by recipient's device.
  retrieved(value: 0x04),

  /// Status is indeterminate or unclear.
  indeterminate(value: 0x05),

  /// Message was forwarded instead of delivered directly.
  forwarded(value: 0x06),

  /// Recipient is unreachable.
  unreachable(value: 0x07),

  /// Unknown or unhandled delivery report value.
  unknown(value: 0xFF);

  const DeliveryReportStatus({required this.value});
  final int value;
}

/// Status values for a read-report (OMA MMS 7.3.47: Read-Status).
enum ReadStatus {
  /// Recipient has read the message.
  read(value: 0x80),

  /// Message was deleted without being read.
  deletedWithoutBeingRead(value: 0x81),

  /// Unknown or not set.
  unknown(value: 0x00);

  const ReadStatus({required this.value});
  final int value;
}

/// Android SMS/MMS box location (sent, inbox, drafts, etc.).
enum MessageBox {
  /// Message is in the inbox.
  inbox(value: 0x01),

  /// Message has been sent.
  sent(value: 0x02),

  /// Message is a draft.
  draft(value: 0x03),

  /// Message is currently being sent (outbox).
  outbox(value: 0x04),

  /// Message failed to send.
  failed(value: 0x05),

  /// Message is queued for sending.
  queued(value: 0x06);

  const MessageBox({required this.value});
  final int value;
}

/// MMS PDU message type, as written to the `m_type` column of the
/// Telephony MMS provider.
///
/// Values come straight from AOSP `com.google.android.mms.pdu.PduHeaders`
/// (see also WAP-MMS Encapsulation v1.2, section 7.2 "X-Mms-Message-Type"):
///
/// ```
/// MESSAGE_TYPE_SEND_REQ            = 0x80
/// MESSAGE_TYPE_SEND_CONF           = 0x81
/// MESSAGE_TYPE_NOTIFICATION_IND    = 0x82
/// MESSAGE_TYPE_NOTIFYRESP_IND      = 0x83
/// MESSAGE_TYPE_RETRIEVE_CONF       = 0x84
/// MESSAGE_TYPE_ACKNOWLEDGE_IND     = 0x85
/// MESSAGE_TYPE_DELIVERY_IND        = 0x86
/// MESSAGE_TYPE_READ_REC_IND        = 0x87
/// MESSAGE_TYPE_READ_ORIG_IND       = 0x88
/// MESSAGE_TYPE_FORWARD_REQ         = 0x89
/// MESSAGE_TYPE_FORWARD_CONF        = 0x8A
/// ```
///
/// Pre-fix this enum had the labels and values misaligned (notificationInd
/// pointed at 0x80 / SEND_REQ, deliveryInd at 0x81 / SEND_CONF,
/// acknowledgeInd at 0x82 / NOTIFICATION_IND, etc.). Every consumer that
/// matched on the wrong label silently produced incorrect classifications
/// — most visibly, real inbound NotificationInd placeholder rows
/// (`m_type = 0x82`) being labeled `acknowledgeInd` and routed as if they
/// were "outbound delivered" messages.
///
/// The enum is keyed by IANA-aligned PDU-spec naming. Apps that need a
/// "user-visible" predicate should prefer `[sendRequest]` (outbound) +
/// `[retrieveConfirmationInd]` (inbound downloaded); everything else is
/// transport-only metadata that should not be persisted as a UI message.
enum MmsMessageType {
  /// `M-send.req` — outbound MMS authored locally, to be sent to the
  /// MMSC. User-visible (the row carries body, subject, parts).
  sendRequest(value: 0x80),

  /// `M-send.conf` — MMSC's reply to a SEND_REQ. Transport-only.
  sendConf(value: 0x81),

  /// `M-notification.ind` — carrier's pre-download notification that an
  /// inbound MMS is waiting on the MMSC. The placeholder row gets
  /// inserted by the system before the actual download happens; once
  /// the RetrieveConf is fetched, a separate row with `RETRIEVE_CONF`
  /// (0x84) is written. NOT user-visible — these placeholder rows
  /// contain no body, parts, or addresses and should be filtered out
  /// of conversation views.
  notificationInd(value: 0x82),

  /// `M-notifyresp.ind` — our app's response to the NOTIFICATION_IND.
  /// Transport-only.
  notifyRespInd(value: 0x83),

  /// `M-retrieve.conf` — inbound MMS as actually downloaded from the
  /// MMSC. User-visible.
  retrieveConfirmationInd(value: 0x84),

  /// `M-acknowledge.ind` — our app's ack after retrieving the MMS.
  /// Transport-only.
  acknowledgeInd(value: 0x85),

  /// `M-delivery.ind` — delivery report from the recipient's carrier
  /// for an outbound MMS. Transport-only (status update, not a
  /// conversation message).
  deliveryInd(value: 0x86),

  /// `M-read-rec.ind` — read report received for our outbound MMS.
  /// Transport-only.
  readReceivedInd(value: 0x87),

  /// `M-read-orig.ind` — read report we originated for an inbound MMS.
  /// Transport-only.
  readOriginatedInd(value: 0x88),

  /// `M-forward.req` — outbound forward request. Transport-only.
  forwardRequestInd(value: 0x89),

  /// `M-forward.conf` — MMSC's ack to a forward request. Transport-only.
  forwardConf(value: 0x8A);

  const MmsMessageType({required this.value});
  final int value;

  /// Whether this PDU type represents a row that should be persisted
  /// as a user-visible conversation message. Only outbound `sendRequest`
  /// (m_type=0x80) and inbound `retrieveConfirmationInd` (m_type=0x84)
  /// carry body + parts + addresses; the rest are transport-only
  /// notifications, acknowledgements, or status reports that pollute
  /// conversation views with empty rows.
  bool get isUserVisible =>
      this == MmsMessageType.sendRequest ||
      this == MmsMessageType.retrieveConfirmationInd;
}

/// General SMS/MMS message type as used in Android's message table.
enum SmsMessageType {
  /// All message types (for queries).
  all(value: 0x00),

  /// Inbox message.
  inbox(value: 0x01),

  /// Sent message.
  sent(value: 0x02),

  /// Draft message.
  draft(value: 0x03),

  /// Outbox message (ready to send).
  outbox(value: 0x04),

  /// Failed message.
  failed(value: 0x05),

  /// Message queued for sending.
  queued(value: 0x06);

  const SmsMessageType({required this.value});
  final int value;
}

/// MIME types we recognise on MMS parts.
///
/// **There is no "other" fallback.** [MmsPart.fromRaw] / [MmsPart.fromJson]
/// `throw` on unknown MIME strings — silent fall-through to
/// `text/plain` was the source of the "image-only MMS persisted EMPTY"
/// class of bugs (an `image/heic` part with no `text/plain` enum match
/// got mis-categorised as text, the host-app converter treated it as a
/// non-attachment text body with empty payload, and the message
/// rendered blank).
///
/// If a real MMS arrives with a MIME this enum doesn't enumerate, the
/// receive path throws loudly and the failure surfaces in crashlytics
/// with the unknown MIME string in the message — that is the signal to
/// add a new entry below.
///
/// When adding new entries:
///   - `value` is the MIME string as the provider stores it in the
///     `ct` column. Match the canonical lower-case form.
///   - `extension` is the file extension (no leading dot) used by
///     `mmsPartToAttachment` when materialising the part to disk.
enum ContentType {
  // ─── Text ────────────────────────────────────────────────────────
  textPlain(value: 'text/plain', extension: 'txt'),
  textHtml(value: 'text/html', extension: 'html'),
  textXVCard(value: 'text/x-vCard', extension: 'vcf'),
  textVCard(value: 'text/vcard', extension: 'vcf'),
  textXVCalendar(value: 'text/x-vCalendar', extension: 'vcs'),
  textCalendar(value: 'text/calendar', extension: 'ics'),

  // ─── Image ───────────────────────────────────────────────────────
  imageJpeg(value: 'image/jpeg', extension: 'jpg'),
  imageJpg(value: 'image/jpg', extension: 'jpg'), // some carriers use this
  imagePng(value: 'image/png', extension: 'png'),
  imageGif(value: 'image/gif', extension: 'gif'),
  imageBmp(value: 'image/bmp', extension: 'bmp'),
  imageWebp(value: 'image/webp', extension: 'webp'),
  imageHeic(value: 'image/heic', extension: 'heic'),
  imageHeif(value: 'image/heif', extension: 'heif'),
  imageAvif(value: 'image/avif', extension: 'avif'),
  imageTiff(value: 'image/tiff', extension: 'tiff'),
  imageSvg(value: 'image/svg+xml', extension: 'svg'),

  // ─── Video ───────────────────────────────────────────────────────
  videoMp4(value: 'video/mp4', extension: 'mp4'),
  videoQuicktime(value: 'video/quicktime', extension: 'mov'),
  video3gpp(value: 'video/3gpp', extension: '3gp'),
  video3gpp2(value: 'video/3gpp2', extension: '3g2'),
  videoWebm(value: 'video/webm', extension: 'webm'),
  videoAvi(value: 'video/x-msvideo', extension: 'avi'),
  videoMatroska(value: 'video/x-matroska', extension: 'mkv'),

  // ─── Audio ───────────────────────────────────────────────────────
  audioAmr(value: 'audio/amr', extension: 'amr'),
  audioMpeg(value: 'audio/mpeg', extension: 'mp3'),
  audioMp4(value: 'audio/mp4', extension: 'm4a'),
  audioMp3(value: 'audio/mp3', extension: 'mp3'),
  audioOgg(value: 'audio/ogg', extension: 'ogg'),
  audioWav(value: 'audio/wav', extension: 'wav'),
  audioXWav(value: 'audio/x-wav', extension: 'wav'),
  audioAac(value: 'audio/aac', extension: 'aac'),
  audioFlac(value: 'audio/flac', extension: 'flac'),
  audio3gpp(value: 'audio/3gpp', extension: '3gp'),

  // ─── Application ─────────────────────────────────────────────────
  applicationSmil(value: 'application/smil', extension: 'smil'),
  applicationPdf(value: 'application/pdf', extension: 'pdf'),
  applicationOctetStream(value: 'application/octet-stream', extension: 'bin'),
  applicationZip(value: 'application/zip', extension: 'zip');

  const ContentType({required this.value, required this.extension});
  final String value;
  final String extension;

  /// Resolve a MIME string from the provider's `ct` column to a
  /// [ContentType] entry. Throws [StateError] when the MIME isn't in
  /// the enum.
  ///
  /// We do **not** silently fall back to `textPlain` or `other` —
  /// silent fallbacks here cascade into the converter mis-classifying
  /// attachments as text, leading to "persisted EMPTY" warnings and
  /// dropped messages. A loud throw forces the missing MIME into a
  /// crashlytics report with the actual string, which is the right
  /// signal to add a new enum entry.
  static ContentType fromMime(String mime) {
    final lower = mime.toLowerCase();
    for (final ct in ContentType.values) {
      if (ct.value.toLowerCase() == lower) return ct;
    }
    throw StateError(
      'Unknown MIME type "$mime" in MMS part. '
      'Add a `ContentType` enum entry for this MIME (see '
      'lib/src/android/models/enums/sms_mms_enums.dart) and ship a fix. '
      'Silent fallbacks here mis-categorise attachments as text and '
      'cause messages to render blank.',
    );
  }
}

/// Character set used for MMS message encoding (common charsets only).
enum CharSet {
  /// US-ASCII (ID: 3)
  usAscii(3),

  /// ISO-8859-1 (Latin-1, ID: 4)
  iso88591(4),

  /// UTF-8 (ID: 106)
  utf8(106),

  /// UTF-16 (ID: 1015)
  utf16(1015),

  /// Unknown charset or non-standard.
  unknown(-1);

  final int value;
  const CharSet(this.value);
}
