// ignore_for_file: constant_identifier_names, unused_element, unused_field

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

enum MmsMessageType {
  // MMS message types (mms.m_type column)
  notificationInd(value: 0x80), // 128
  deliveryInd(value: 0x81), // 129
  acknowledgeInd(value: 0x82), // 130
  deliveryReportInd(value: 0x86), // 134 (rare)
  retrieveConfirmationInd(value: 0x84), // 132
  readReceivedInd(value: 0x87), // 135
  readOriginatedInd(value: 0x88), // 136
  forwardRequestInd(value: 0x8A), // 138
  readReportInd(value: 0x8C); // 140 (rare)

  const MmsMessageType({required this.value});
  final int value;
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

/// Represents accepted content MIME types for MMS attachments.
enum ContentType {
  /// Plain text file.
  textPlain(value: 'text/plain', extension: 'txt'),

  /// HTML file.
  textHtml(value: 'text/html', extension: 'html'),

  /// JPEG image.
  imageJpeg(value: 'image/jpeg', extension: 'jpg'),

  /// PNG image.
  imagePng(value: 'image/png', extension: 'png'),

  /// GIF image.
  imageGif(value: 'image/gif', extension: 'gif'),

  /// MP4 video.
  videoMp4(value: 'video/mp4', extension: 'mp4'),

  /// AMR audio.
  audioAmr(value: 'audio/amr', extension: 'amr'),

  /// SMIL presentation.
  applicationSmil(value: 'application/smil', extension: 'smil'),

  /// vCard contact file.
  applicationVCard(value: 'text/x-vCard', extension: 'vcf'),

  /// Other or unspecified type.
  other(value: '', extension: 'dat');

  const ContentType({required this.value, required this.extension});
  final String value;
  final String extension;
}

/// App-specific spam report flag for marking messages as spam or not spam.
enum SpamReport {
  /// Message is not spam.
  notSpam(value: 0x0),

  /// Message is considered spam.
  spam(value: 0x1);

  const SpamReport({required this.value});
  final int value;
}

/// App-specific secret mode flag for message privacy.
enum SecretMode {
  /// Message is not secret.
  notSecret(value: 0x0),

  /// Message is marked as secret.
  secret(value: 0x1);

  const SecretMode({required this.value});
  final int value;
}

/// Read/unread status for SMS/MMS messages. Mirrors Android's read/unread.
enum MessageReadStatus {
  /// Message has not been read.
  unread(value: 0x0),

  /// Message has been read.
  read(value: 0x1);

  const MessageReadStatus({required this.value});
  final int value;
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
