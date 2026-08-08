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

  /// PDU types whose rows represent user-visible conversation messages.
  static const List<MmsMessageType> userVisibleValues = <MmsMessageType>[
    MmsMessageType.sendRequest,
    MmsMessageType.retrieveConfirmationInd,
  ];

  /// Whether this PDU type represents a row that should be persisted
  /// as a user-visible conversation message. Only outbound `sendRequest`
  /// (m_type=0x80) and inbound `retrieveConfirmationInd` (m_type=0x84)
  /// carry body + parts + addresses; the rest are transport-only
  /// notifications, acknowledgements, or status reports that pollute
  /// conversation views with empty rows.
  bool get isUserVisible => userVisibleValues.contains(this);
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

/// Top-level MIME category parsed from the "type/" prefix.
///
/// Drives renderer selection for both known and unknown MIME types.
/// An unknown `image/x-whatever` → [MimeCategory.image] → render as
/// image widget. An unknown `application/vnd.gsma.botmessage` →
/// [MimeCategory.application] → skip or render as generic file.
enum MimeCategory {
  text,
  image,
  video,
  audio,
  application,
}

/// MIME content type for MMS parts.
///
/// Known MIME types have curated entries with exact extensions (e.g.
/// `image/jpeg` → `jpg`). Unknown MIME types — carrier extensions,
/// OEM-specific formats, RCS bot messages — produce ad-hoc instances
/// with:
///   - [value]: the raw MIME string, preserved verbatim
///   - [category]: parsed from the `type/` prefix (structurally correct)
///   - [extension]: best-effort derivation from the subtype
///
/// This is **not** a return to the old silent-fallback anti-pattern:
///   - Known MIMEs still get exact matches with curated extensions
///   - Unknown MIMEs are logged (visible in diagnostics / Crashlytics)
///   - The raw MIME string is preserved — no mis-categorisation
///   - The category is parsed from the MIME itself, never guessed
///
/// When adding new known entries:
///   - [value] is the MIME string as the provider stores it in the
///     `ct` column. Match the canonical lower-case form.
///   - [extension] is the file extension (no leading dot) used by
///     `mmsPartToAttachment` when materialising the part to disk.
final class ContentType {
  final String value;
  final String extension;
  final MimeCategory category;

  const ContentType({
    required this.value,
    required this.extension,
    required this.category,
  });

  // ─── Text ──────────────────────────────────────────────────────────
  static const textPlain = ContentType(
    value: 'text/plain', extension: 'txt', category: MimeCategory.text,
  );
  static const textHtml = ContentType(
    value: 'text/html', extension: 'html', category: MimeCategory.text,
  );
  static const textXVCard = ContentType(
    value: 'text/x-vCard', extension: 'vcf', category: MimeCategory.text,
  );
  static const textVCard = ContentType(
    value: 'text/vcard', extension: 'vcf', category: MimeCategory.text,
  );
  static const textXVCalendar = ContentType(
    value: 'text/x-vCalendar', extension: 'vcs', category: MimeCategory.text,
  );
  static const textCalendar = ContentType(
    value: 'text/calendar', extension: 'ics', category: MimeCategory.text,
  );

  // ─── Image ─────────────────────────────────────────────────────────
  static const imageJpeg = ContentType(
    value: 'image/jpeg', extension: 'jpg', category: MimeCategory.image,
  );
  /// Some carriers use `image/jpg` instead of `image/jpeg`.
  static const imageJpg = ContentType(
    value: 'image/jpg', extension: 'jpg', category: MimeCategory.image,
  );
  static const imagePng = ContentType(
    value: 'image/png', extension: 'png', category: MimeCategory.image,
  );
  static const imageGif = ContentType(
    value: 'image/gif', extension: 'gif', category: MimeCategory.image,
  );
  static const imageBmp = ContentType(
    value: 'image/bmp', extension: 'bmp', category: MimeCategory.image,
  );
  static const imageWebp = ContentType(
    value: 'image/webp', extension: 'webp', category: MimeCategory.image,
  );
  static const imageHeic = ContentType(
    value: 'image/heic', extension: 'heic', category: MimeCategory.image,
  );
  static const imageHeif = ContentType(
    value: 'image/heif', extension: 'heif', category: MimeCategory.image,
  );
  static const imageAvif = ContentType(
    value: 'image/avif', extension: 'avif', category: MimeCategory.image,
  );
  static const imageTiff = ContentType(
    value: 'image/tiff', extension: 'tiff', category: MimeCategory.image,
  );
  static const imageSvg = ContentType(
    value: 'image/svg+xml', extension: 'svg', category: MimeCategory.image,
  );
  /// Adobe Digital Negative — RAW camera format some Samsung devices send
  /// when sharing from Pro / Expert RAW mode in the camera app.
  static const imageDng = ContentType(
    value: 'image/x-adobe-dng', extension: 'dng', category: MimeCategory.image,
  );

  // ─── Video ─────────────────────────────────────────────────────────
  static const videoMp4 = ContentType(
    value: 'video/mp4', extension: 'mp4', category: MimeCategory.video,
  );
  static const videoQuicktime = ContentType(
    value: 'video/quicktime', extension: 'mov', category: MimeCategory.video,
  );
  static const video3gpp = ContentType(
    value: 'video/3gpp', extension: '3gp', category: MimeCategory.video,
  );
  static const video3gpp2 = ContentType(
    value: 'video/3gpp2', extension: '3g2', category: MimeCategory.video,
  );
  static const videoWebm = ContentType(
    value: 'video/webm', extension: 'webm', category: MimeCategory.video,
  );
  static const videoAvi = ContentType(
    value: 'video/x-msvideo', extension: 'avi', category: MimeCategory.video,
  );
  static const videoMatroska = ContentType(
    value: 'video/x-matroska', extension: 'mkv', category: MimeCategory.video,
  );

  // ─── Audio ─────────────────────────────────────────────────────────
  static const audioAmr = ContentType(
    value: 'audio/amr', extension: 'amr', category: MimeCategory.audio,
  );
  static const audioMpeg = ContentType(
    value: 'audio/mpeg', extension: 'mp3', category: MimeCategory.audio,
  );
  static const audioMp4 = ContentType(
    value: 'audio/mp4', extension: 'm4a', category: MimeCategory.audio,
  );
  static const audioMp3 = ContentType(
    value: 'audio/mp3', extension: 'mp3', category: MimeCategory.audio,
  );
  static const audioOgg = ContentType(
    value: 'audio/ogg', extension: 'ogg', category: MimeCategory.audio,
  );
  static const audioWav = ContentType(
    value: 'audio/wav', extension: 'wav', category: MimeCategory.audio,
  );
  static const audioXWav = ContentType(
    value: 'audio/x-wav', extension: 'wav', category: MimeCategory.audio,
  );
  static const audioAac = ContentType(
    value: 'audio/aac', extension: 'aac', category: MimeCategory.audio,
  );
  static const audioFlac = ContentType(
    value: 'audio/flac', extension: 'flac', category: MimeCategory.audio,
  );
  static const audio3gpp = ContentType(
    value: 'audio/3gpp', extension: '3gp', category: MimeCategory.audio,
  );

  // ─── Application ───────────────────────────────────────────────────
  static const applicationSmil = ContentType(
    value: 'application/smil', extension: 'smil', category: MimeCategory.application,
  );
  static const applicationPdf = ContentType(
    value: 'application/pdf', extension: 'pdf', category: MimeCategory.application,
  );
  static const applicationOctetStream = ContentType(
    value: 'application/octet-stream', extension: 'bin', category: MimeCategory.application,
  );
  static const applicationZip = ContentType(
    value: 'application/zip', extension: 'zip', category: MimeCategory.application,
  );

  /// All known content types.
  static const List<ContentType> values = [
    textPlain, textHtml, textXVCard, textVCard, textXVCalendar, textCalendar,
    imageJpeg, imageJpg, imagePng, imageGif, imageBmp, imageWebp,
    imageHeic, imageHeif, imageAvif, imageTiff, imageSvg, imageDng,
    videoMp4, videoQuicktime, video3gpp, video3gpp2, videoWebm, videoAvi,
    videoMatroska,
    audioAmr, audioMpeg, audioMp4, audioMp3, audioOgg, audioWav, audioXWav,
    audioAac, audioFlac, audio3gpp,
    applicationSmil, applicationPdf, applicationOctetStream, applicationZip,
  ];

  /// Resolve a MIME string to a [ContentType].
  ///
  /// Known MIMEs return a curated entry with an exact extension.
  /// Unknown MIMEs produce an ad-hoc instance with [category] parsed
  /// from the `type/` prefix and [extension] derived from the subtype.
  ///
  /// Parameters after the first `;` are stripped before matching, so
  /// `image/jpeg; name=photo.jpg` and `text/plain; charset=utf-8`
  /// resolve to the same curated entries as their bare forms. The raw
  /// (sans-parameter) MIME string is preserved on the returned instance.
  ///
  /// Throws [StateError] when [mime] is empty or contains only
  /// parameters (no bare type/subtype). An MMS part row with an absent
  /// `ct` column is malformed — silent fallback to `textPlain` here is
  /// the exact pattern that produced the HEIC "persisted EMPTY" bug
  /// class. For genuinely optional fields (e.g. [Sms.reContentType]),
  /// use [fromMimeOrNull] instead.
  static ContentType fromMime(String mime) {
    // Strip parameters: "image/jpeg; name=photo.jpg" → "image/jpeg".
    final semi = mime.indexOf(';');
    final bare = (semi >= 0 ? mime.substring(0, semi) : mime).trim();

    if (bare.isEmpty) {
      throw StateError(
        'ContentType.fromMime called with empty MIME (raw input: "$mime"). '
        'An MMS part with an absent `ct` column is malformed — silent '
        'fallback to text/plain here recreates the HEIC "persisted EMPTY" '
        'bug. For optional fields, use fromMimeOrNull instead.',
      );
    }

    final lower = bare.toLowerCase();
    for (final ct in values) {
      if (ct.value.toLowerCase() == lower) return ct;
    }

    // Unknown MIME — build an ad-hoc instance with parameters stripped.
    return ContentType(
      value: bare,
      extension: _deriveExtension(lower),
      category: _parseCategory(lower),
    );
  }

  /// Nullable variant for optional fields (e.g. [Sms.reContentType]).
  ///
  /// Returns `null` when [mime] is null, empty, or parameter-only —
  /// the field is genuinely absent. Non-empty strings resolve via
  /// [fromMime] (which strips parameters before matching).
  static ContentType? fromMimeOrNull(String? mime) {
    if (mime == null) return null;
    final semi = mime.indexOf(';');
    final bare = (semi >= 0 ? mime.substring(0, semi) : mime).trim();
    if (bare.isEmpty) return null;
    return fromMime(mime);
  }

  /// Whether this is an unknown (ad-hoc) content type not in [values].
  bool get isKnown => values.any((ct) => ct.value == value);

  /// Derive a file extension from an unknown MIME subtype.
  ///
  /// Rules (applied in order):
  ///   1. If `+suffix` present, use it (`…+json` → `json`)
  ///   2. For `vnd.*` without `+suffix`, fall back to `bin`
  ///   3. Strip `x-` prefix (`audio/x-m4a` → `m4a`)
  ///   4. Take the last dot-segment (`x-adobe.dng` → `dng`)
  ///   5. Fall back to `bin` if subtype is empty
  static String _deriveExtension(String lowerMime) {
    final slashIdx = lowerMime.indexOf('/');
    if (slashIdx < 0 || slashIdx == lowerMime.length - 1) return 'bin';
    var subtype = lowerMime.substring(slashIdx + 1);

    // Rule 2: structured suffix (e.g. +json, +xml)
    final plusIdx = subtype.lastIndexOf('+');
    if (plusIdx >= 0 && plusIdx < subtype.length - 1) {
      return subtype.substring(plusIdx + 1);
    }

    // Rule 3: vnd.* without +suffix → opaque vendor type
    if (subtype.startsWith('vnd.')) return 'bin';

    // Rule 1: strip x- prefix
    if (subtype.startsWith('x-')) {
      subtype = subtype.substring(2);
    }

    // Rule 4: take last dot-segment
    final lastDot = subtype.lastIndexOf('.');
    if (lastDot >= 0 && lastDot < subtype.length - 1) {
      subtype = subtype.substring(lastDot + 1);
    }

    // Rule 5: strip version-like trailing segments (e.g. "m4a" stays,
    // "v1" or "1.0" get caught by this but they'd be unlikely subtypes)
    if (subtype.isEmpty) return 'bin';
    return subtype;
  }

  /// Parse [MimeCategory] from the `type/` prefix of a MIME string.
  static MimeCategory _parseCategory(String lowerMime) {
    if (lowerMime.startsWith('image/')) return MimeCategory.image;
    if (lowerMime.startsWith('video/')) return MimeCategory.video;
    if (lowerMime.startsWith('audio/')) return MimeCategory.audio;
    if (lowerMime.startsWith('text/')) return MimeCategory.text;
    return MimeCategory.application;
  }

  @override
  bool operator ==(Object other) =>
      other is ContentType && other.value == value;

  @override
  int get hashCode => value.hashCode;

  @override
  String toString() => 'ContentType($value)';
}

/// Character set used for MMS message encoding.
///
/// IDs are IANA MIBenum values (assigned by the IANA Character Set
/// Registry, see https://www.iana.org/assignments/character-sets/).
/// The Android Telephony provider, the WAP-MMS spec, and the AOSP
/// `PduPart.charset` field all use these MIBenum integers.
///
/// Coverage spans the four ASCII/Latin variants the original enum
/// shipped with plus the four East-Asian and Western-European
/// charsets most commonly seen on real-world inbound MMS:
///   * GB2312 — mainland Chinese carriers (China Mobile, China Unicom).
///   * Shift_JIS — Japanese carriers (NTT DoCoMo, KDDI au).
///   * Big5 — Taiwanese carriers (Chunghwa, Taiwan Mobile) + Hong Kong.
///   * Windows-1252 — legacy Windows-defaulted senders, common when
///     a Windows-side composer encodes non-ASCII Latin characters
///     (curly quotes, em-dashes, accented Latin) in this charset
///     instead of UTF-8.
///
/// Adding these prevents the host-side Dart layer's
/// `decodeWithCharset` (in `MmsPart`) from falling back to UTF-8
/// when an MMS arrives with one of these declared encodings — UTF-8
/// fallback over a real GB2312/Shift_JIS/Big5 byte stream produces
/// mojibake or empty body.
enum CharSet {
  /// US-ASCII (ID: 3)
  usAscii(3),

  /// ISO-8859-1 (Latin-1, ID: 4)
  iso88591(4),

  /// Shift_JIS — Japanese (ID: 17)
  shiftJis(17),

  /// GB2312 — Simplified Chinese (ID: 2025)
  gb2312(2025),

  /// Big5 — Traditional Chinese (ID: 2026)
  big5(2026),

  /// Windows-1252 — Western European (ID: 2252)
  windows1252(2252),

  /// UTF-8 (ID: 106)
  utf8(106),

  /// UTF-16 (ID: 1015)
  utf16(1015),

  /// Unknown charset or non-standard.
  unknown(-1);

  final int value;
  const CharSet(this.value);
}
