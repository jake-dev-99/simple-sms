/// What a message-source provider supports, as a flat set of capability
/// flags the host can render against generically.
///
/// The provider contract (ADR-0014) is uniform across SMS/MMS, Telegram,
/// future native chat, etc. — but each source supports a different subset
/// of features. A capability set lets the host's UI ask "does this channel
/// support reactions / edits / typing?" rather than hardcode
/// `if (channel == sms)`. The same renderer drives every channel; only the
/// flags change.
///
/// Flags describe the **protocol/native store**, not a single message:
/// they answer "can this provider, in principle, deliver X" rather than
/// "did this specific row carry X". (A per-message capability — e.g.
/// "this specific MMS can be deleted" — would live on the message, not
/// here.)
///
/// Capabilities are intended to be **constants** per provider, not
/// runtime-queried — there is no native API that asks Android "do you
/// support reactions on SMS?". The provider declares what it can do; the
/// host trusts the declaration.
class ChannelCapabilities {
  const ChannelCapabilities({
    required this.canSend,
    required this.canReceive,
    required this.canDelete,
    required this.canMarkRead,
    required this.supportsAttachments,
    required this.supportsGroupConversations,
    required this.supportsDeliveryReceipts,
    required this.supportsReadReceipts,
    required this.supportsReactions,
    required this.supportsEdits,
    required this.supportsTypingIndicators,
  });

  /// The provider can send outbound messages on this channel.
  final bool canSend;

  /// The provider receives inbound messages on this channel.
  final bool canReceive;

  /// The provider can remove messages from the native store (the
  /// host-facing "delete" action, beyond Unify's own soft-delete overlay).
  final bool canDelete;

  /// The provider can write the native store's read flag (writing the host
  /// overlay separately is always possible — this is the *native* flag).
  final bool canMarkRead;

  /// Messages can carry binary attachments (images / video / audio / files).
  final bool supportsAttachments;

  /// The protocol carries multi-recipient threads natively (recipients are
  /// addressable in a single conversation, not just N parallel 1:1s).
  final bool supportsGroupConversations;

  /// The protocol carries a "this message was delivered" signal back to
  /// the sender (not just the carrier's send-ack).
  final bool supportsDeliveryReceipts;

  /// The protocol carries a "the recipient read this" signal back to the
  /// sender. Distinct from delivery: delivered ≠ read.
  final bool supportsReadReceipts;

  /// Recipients can attach emoji/text reactions to a message that the
  /// sender's UI surfaces (and that the provider exposes back through its
  /// contract — RCS-style tapbacks etc.).
  final bool supportsReactions;

  /// A previously-sent message can be edited in place after delivery.
  final bool supportsEdits;

  /// The protocol exposes a real-time "the other party is typing" signal
  /// (not just a derived heuristic).
  final bool supportsTypingIndicators;
}

/// The capabilities of the Android SMS/MMS provider (`simple-sms`).
///
/// Why each flag is set the way it is — these are protocol facts about
/// classic SMS/MMS as exposed through Android's Telephony providers. RCS
/// (rich communication services) would relax several of the `false`s here,
/// but RCS support is a separate provider concern.
///
/// * `canSend` / `canReceive` — the messaging method channels
///   (`OutboundMessagingHandler`) and the inbound broadcast receiver
///   (`InboundMessaging`) both exist on every supported Android version.
/// * `canDelete` — `AndroidDestructiveAction.deleteMessage`/`deleteThread`
///   write the native store (default-SMS-app role required at runtime;
///   the capability itself is supported).
/// * `canMarkRead` — `AndroidAction.markMessageAsRead`/
///   `markConversationAsRead` write the native `read` flag.
/// * `supportsAttachments` — MMS carries binary parts; SMS does not. The
///   composite "this provider" supports it because the provider auto-
///   promotes outbound to MMS when an attachment is present.
/// * `supportsGroupConversations` — same auto-promote applies: a multi-
///   recipient thread is a group MMS.
/// * `supportsDeliveryReceipts` — MMS carries `M-delivery.ind` (PDU 0x86);
///   SMS carries a per-message `status` column populated by the carrier.
/// * `supportsReadReceipts` — MMS carries `M-read-rec.ind` (PDU 0x87).
///   SMS has no native read-receipt PDU; the composite is `true` because
///   the provider can deliver an MMS read receipt back to an outbound MMS.
/// * `supportsReactions` — classic SMS/MMS has no native reaction concept.
///   Apple-style tapbacks rendered into the body as text aren't a protocol
///   capability; they're a foreign-format leak. (RCS would change this.)
/// * `supportsEdits` — once an SMS/MMS PDU is dispatched it cannot be
///   recalled or rewritten over the wire. (RCS would change this.)
/// * `supportsTypingIndicators` — classic SMS/MMS has no typing-status
///   signal. (RCS would change this.)
const ChannelCapabilities androidSmsMmsCapabilities = ChannelCapabilities(
  canSend: true,
  canReceive: true,
  canDelete: true,
  canMarkRead: true,
  supportsAttachments: true,
  supportsGroupConversations: true,
  supportsDeliveryReceipts: true,
  supportsReadReceipts: true,
  supportsReactions: false,
  supportsEdits: false,
  supportsTypingIndicators: false,
);
