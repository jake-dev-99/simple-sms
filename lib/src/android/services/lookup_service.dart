// `dart:io` also exports a `ContentType` type — hide it so our local
// MIME enum (declared in `enums/sms_mms_enums.dart`) resolves
// unambiguously inside this file.
import 'dart:io' hide ContentType;

import '../models/conversations/mms_sms_simple_conversations.dart';
import '../models/filters/contact_filter.dart';
import '../models/filters/conversation_filter.dart';
import '../models/filters/mms_filter.dart';
import '../models/filters/sms_filter.dart';
import '../models/messages/mms.dart';
import '../models/messages/mms_part.dart';
import '../models/messages/sms.dart';
import '../models/people/contact.dart';
import '../models/people/contact_name.dart';
import '../models/people/contactables.dart';
import '../models/people/mms_participant.dart';
import 'attachment_extractor.dart';
import 'contact_lookup.dart';
import 'conversation_lookup.dart';
import 'message_lookup.dart';

/// Centralized service for resolving contacts, messages, and addresses
/// from the Android ContentProvider database.
///
/// All database queries go through this service rather than being embedded
/// in model property getters. This makes the query cost explicit and
/// avoids surprising side effects when accessing model properties.
///
/// ## Error semantics
///
/// Query methods do **not** swallow exceptions. If the underlying
/// ContentProvider call fails (SQL error, permission revoked, provider
/// crash), the exception propagates to the caller with its original
/// stack trace. A blanket `catch + return null/[]` here previously hid
/// a `thread_id` SQL bug across thousands of full-sync runs — the
/// failure only surfaced when a downstream `!` upgraded the silent empty
/// to an NPE. Callers that iterate over many rows (sync loops) should
/// wrap individual row conversions in their own try/catch so a single
/// bad row doesn't kill the whole pass; bulk-fetch failures kill the
/// pass on purpose, since every iteration would fail anyway.
///
/// ## Tier 0f decomposition
///
/// This class is now a **thin façade** over per-domain lookup modules:
///
/// * [ContactLookup] — contacts + contactables + structured names.
/// * [MessageLookup] — SMS/MMS rows, MMS addresses, canonical addresses,
///   thread-id resolution.
/// * [AttachmentExtractor] — MMS part rows + binary extraction.
/// * [ConversationLookup] — MMS-SMS thread rows + per-thread enrichment.
///
/// New callers should generally prefer the per-domain modules directly.
/// LookupService stays in place for backward-compat with existing
/// callsites and to keep the public API discoverable from a single
/// entrypoint.
///
/// ```dart
/// final service = LookupService();
/// final contact = await service.lookupContactById(42);
/// final messages = await service.getSmsByThread(7);
/// ```

class LookupService {
  /// Replaces the self-MSISDN set used by MMS-addr filtering. Call
  /// once at app bootstrap with the result of an E.164 normalization
  /// pass over `SimpleTelephonyNative.listSimCards()` `number` fields,
  /// dropping empty values. Plugin keeps its dep-graph self-contained
  /// by accepting these values rather than reaching into
  /// `simple_telephony`.
  /// Façade — delegates to [ConversationLookup] (Tier 0f extraction).
  static void setSelfNumbers(Set<String> numbers) =>
      ConversationLookup.instance.setSelfNumbers(numbers);

  /// Looks up a contact by their database ID.
  ///
  /// Returns null if the contact is not found or if the query fails.
  /// Façade — delegates to [ContactLookup] (Tier 0f extraction).
  Future<AndroidContact?> lookupContactById(int contactId) =>
      ContactLookup.instance.lookupContactById(contactId);

  /// Looks up a contactable (lightweight contact info) by phone number or email.
  ///
  /// Uses the Android contacts filter URI to match the address against
  /// phone numbers or email addresses in the contacts database.
  /// Façade — delegates to [ContactLookup] (Tier 0f extraction).
  Future<Contactable?> lookupContactableByAddress(String address) =>
      ContactLookup.instance.lookupContactableByAddress(address);

  /// Looks up an MMS message by its database ID.
  /// Façade — delegates to [MessageLookup] (Tier 0f extraction).
  Future<Mms?> lookupMmsById(int messageId) =>
      MessageLookup.instance.lookupMmsById(messageId);

  /// Lists every address row (sender + recipients) for a single MMS message.
  ///
  /// Queries `content://mms/{mmsId}/addr` — the per-message addresses table,
  /// which is keyed on `msg_id`. Each row maps to an [MmsParticipant] and
  /// carries a `type` column (`0x89` = sender, `0x97` = to-recipient,
  /// `0x82` = cc-recipient, `0x81` = bcc-recipient per the WAP-MMS spec).
  ///
  /// Use this for per-message attribution in group MMS. `listMms` returns
  /// MMS rows with empty `recipients` lists by design (they're not in the
  /// `mms` table); enrich with this call when the caller needs to know
  /// which participant in a multi-party thread sent an individual message.
  /// Façade — delegates to [MessageLookup] (Tier 0f extraction).
  Future<List<MmsParticipant>> listMmsAddressesByMessage(int mmsId) =>
      MessageLookup.instance.listMmsAddressesByMessage(mmsId);

  /// Resolves (or lazily creates) the thread id for a given recipient set.
  ///
  /// Queries `content://mms-sms/threadID` with one `recipient=<addr>` query
  /// parameter per address. Android's Telephony provider will return the
  /// existing thread id that matches the recipient set, or allocate a new
  /// thread id and return it — the same contract
  /// `Telephony.Threads.getOrCreateThreadId(Context, Set<String>)` gives on
  /// the Java side.
  ///
  /// Returns null when [addresses] is empty or no row comes back.
  /// Façade — delegates to [MessageLookup] (Tier 0f extraction).
  Future<int?> resolveThreadIdByAddresses(Iterable<String> addresses) =>
      MessageLookup.instance.resolveThreadIdByAddresses(addresses);

  /// Resolves a canonical address (phone number) from a recipient ID.
  ///
  /// Android stores conversation recipients as numeric IDs that map to
  /// canonical addresses via the `content://mms-sms/canonical-address/` URI.
  /// Façade — delegates to [MessageLookup] (Tier 0f extraction).
  Future<String?> resolveCanonicalAddress(String recipientId) =>
      MessageLookup.instance.resolveCanonicalAddress(recipientId);

  /// Gets all SMS messages in a conversation thread.
  /// Façade — delegates to [MessageLookup] (Tier 0f extraction).
  Future<List<Sms>> getSmsByThread(int threadId) =>
      MessageLookup.instance.getSmsByThread(threadId);

  /// Façade — delegates to [MessageLookup] (Tier 0f extraction).
  Future<List<Sms>> listSms({
    SmsFilter? filter,
    SmsSort? sort,
    int? limit,
    int? offset,
  }) =>
      MessageLookup.instance.listSms(
        filter: filter,
        sort: sort,
        limit: limit,
        offset: offset,
      );

  /// Façade — delegates to [MessageLookup] (Tier 0f extraction).
  Future<Sms?> getSmsById(int id) => MessageLookup.instance.getSmsById(id);

  /// Façade — delegates to [MessageLookup] (Tier 0f extraction).
  Future<List<Mms>> getMmsByThread(int threadId) =>
      MessageLookup.instance.getMmsByThread(threadId);

  /// Façade — delegates to [MessageLookup] (Tier 0f extraction).
  ///
  /// Returned [Mms] instances have empty `recipients` and `parts` —
  /// fetch them separately via [listMmsParts] /
  /// [listMmsAddressesByMessage], or re-materialize via
  /// [lookupMmsById].
  Future<List<Mms>> listMms({
    MmsFilter? filter,
    MmsSort? sort,
    int? limit,
    int? offset,
  }) =>
      MessageLookup.instance.listMms(
        filter: filter,
        sort: sort,
        limit: limit,
        offset: offset,
      );

  /// Lists the parts (text body + attachments) that belong to an MMS message.
  /// Façade — delegates to [AttachmentExtractor] (Tier 0f extraction).
  Future<List<MmsPart>> listMmsParts({
    required int mmsId,
    MmsPartFilter? filter,
  }) =>
      AttachmentExtractor.instance.listMmsParts(mmsId: mmsId, filter: filter);

  /// Extracts the binary content of a single MMS part to [outputDirectory],
  /// returning the resulting [File]. The file is named [filename] if given,
  /// otherwise a name is derived from the part id + mime type.
  /// Façade — delegates to [AttachmentExtractor] (Tier 0f extraction).
  Future<File> extractMmsPart({
    required int partId,
    required String outputDirectory,
    String? filename,
  }) =>
      AttachmentExtractor.instance.extractMmsPart(
        partId: partId,
        outputDirectory: outputDirectory,
        filename: filename,
      );

  /// Lists conversations (MMS-SMS threads) matching the given [filter].
  ///
  /// If [enrich] is `true` (default), each returned [AndroidSimpleConversation]
  /// has its `participants`, `latestSms`, and `latestMms` fields populated via
  /// follow-up lookups. Set to `false` to avoid the extra round-trips when
  /// only the flat conversation row is needed.
  /// Façade — delegates to [ConversationLookup] (Tier 0f extraction).
  Future<List<AndroidSimpleConversation>> listConversations({
    ConversationFilter? filter,
    ConversationSort? sort,
    int? limit,
    int? offset,
    bool enrich = true,
  }) =>
      ConversationLookup.instance.listConversations(
        filter: filter,
        sort: sort,
        limit: limit,
        offset: offset,
        enrich: enrich,
      );

  /// Fetches a single conversation by thread id. Returns null if not found.
  ///
  /// Enriched by default; see [listConversations] for the `enrich` parameter.
  /// Façade — delegates to [ConversationLookup] (Tier 0f extraction).
  Future<AndroidSimpleConversation?> getConversationByThread(
    int threadId, {
    bool enrich = true,
  }) =>
      ConversationLookup.instance
          .getConversationByThread(threadId, enrich: enrich);

  /// Lists every `data` row belonging to a single contact — phone numbers,
  /// emails, and any other MIME-typed data entries.
  /// Façade — delegates to [ContactLookup] (Tier 0f extraction).
  Future<List<Contactable>> listContactablesForContact(int contactId) =>
      ContactLookup.instance.listContactablesForContact(contactId);

  /// Resolves a contact's structured name (given / family / prefix / suffix /
  /// phonetic variants) from the contacts data provider.
  /// Façade — delegates to [ContactLookup] (Tier 0f extraction).
  Future<AndroidContactName?> getStructuredName({
    required int contactId,
    String? accountType,
  }) =>
      ContactLookup.instance
          .getStructuredName(contactId: contactId, accountType: accountType);

  /// Lists contacts matching the given [filter], ordered by [sort], paged by
  /// [limit] / [offset].
  /// Façade — delegates to [ContactLookup] (Tier 0f extraction).
  Future<List<AndroidContact>> listContacts({
    ContactFilter? filter,
    ContactSort? sort,
    int? limit,
    int? offset,
  }) =>
      ContactLookup.instance.listContacts(
        filter: filter,
        sort: sort,
        limit: limit,
        offset: offset,
      );
}
