/// Source-neutral role of a participant within a message.
///
/// Unifies the two ways the underlying providers express "who is this party
/// to the message": SMS encodes it implicitly in the row direction
/// (`Sms.type`), while MMS carries an explicit WAP-PDU address-type byte
/// (`AndroidParticipantType`). Both map onto this one vocabulary so the host
/// reads a message's participants without knowing which provider they came
/// from.
enum ParticipantRole {
  /// The party who sent the message.
  sender,

  /// A primary addressee (PDU `TO`).
  to,

  /// A carbon-copy addressee (PDU `CC`).
  cc,

  /// A blind-carbon-copy addressee (PDU `BCC`).
  bcc,

  /// Role could not be determined from the source row.
  unknown,
}

/// A single participant on a message, uniform across SMS and MMS.
///
/// Both SMS (via `Sms.sender` / `Sms.recipient`) and MMS (via
/// `MmsParticipant`) expose this shape, so the host reads one
/// `sender` / `recipient` contract regardless of whether the record
/// originated from `content://sms` (a flat `address` + direction) or
/// `content://mms/<id>/addr` (a typed participant table). The
/// sender/recipient split is resolved **at the plugin boundary** — the host
/// no longer infers it from `address` + direction.
///
/// Name resolution is intentionally **not** done here. [displayName] is
/// populated only when an enrichment path (e.g. `ContactLookup`) supplied a
/// resolved name; on the raw provider-parse path it is `null`, and the host
/// resolves the name from [address] / [contactId] against its own contact
/// graph. This keeps the contract cheap for SMS, which carries no contact
/// join on the row.
abstract interface class MessageParticipant {
  /// The raw addressable identifier as the provider stored it — an MSISDN,
  /// email, shortcode, or RCS token. `null` only for a structurally-absent
  /// party (e.g. the local device on an SMS row, which the provider does
  /// not record).
  String? get address;

  /// This participant's role in its message.
  ParticipantRole get role;

  /// The Android Contacts `contact_id`, when the source row supplied one.
  /// MMS `addr` rows carry it; SMS rows do not, so it is `null` for SMS.
  int? get contactId;

  /// A resolved human-readable name, populated only by an enrichment path;
  /// `null` on the raw provider-parse path (this layer does not resolve
  /// names — see the class doc).
  String? get displayName;
}

/// The [MessageParticipant] form of an SMS party.
///
/// SMS has no per-message participant table: a `content://sms` row carries a
/// single `address` plus a direction (`Sms.type`). `Sms.sender` and
/// `Sms.recipient` derive one of these at the plugin boundary so the host
/// never re-implements the "which column is the counterparty for this
/// direction" logic — the exact inference that produced the inverted-sender
/// ("Unknown Sender") bug downstream.
class SmsParticipant implements MessageParticipant {
  const SmsParticipant({required this.address, required this.role});

  @override
  final String? address;

  @override
  final ParticipantRole role;

  /// Always `null` for SMS — a `content://sms` row carries no `contact_id`
  /// (only a legacy `person` string, which is not a Contacts contact id).
  @override
  int? get contactId => null;

  /// Always `null` for SMS on the raw path — name resolution is the host's
  /// job (see [MessageParticipant.displayName]).
  @override
  String? get displayName => null;
}
