// `dart:io` also exports a `ContentType` type — hide it so our local
// MIME enum (declared in `enums/sms_mms_enums.dart`) resolves
// unambiguously inside this file.
import 'dart:io' hide ContentType;

import 'package:flutter/foundation.dart';
import 'package:simple_query/simple_query.dart';

import '../models/conversations/mms_sms_simple_conversations.dart';
import '../models/enums/sms_mms_enums.dart';
import '../models/filters/contact_filter.dart';
import '../models/filters/conversation_filter.dart';
import '../models/filters/mms_filter.dart';
import '../models/filters/sms_filter.dart';
import '../models/filters/sort_direction.dart';
import '../models/messages/mms.dart';
import '../models/messages/mms_part.dart';
import '../models/messages/sms.dart';
import '../models/people/contact.dart';
import '../models/people/contact_name.dart';
import '../models/people/contactables.dart';
import '../models/people/mms_participant.dart';

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
/// ```dart
/// final service = LookupService();
/// final contact = await service.lookupContactById(42);
/// final messages = await service.getSmsByThread(7);
/// ```
// Content URIs for the Android provider tables this service reads.
// Kept as constants so the (platformSpecific) domain switch below is
// easy to audit and stay consistent with `Query.kt` / `ContentQuery`
// on the Kotlin side.
const String _contactsUri = 'content://com.android.contacts/contacts';
const String _contactsDataUri = 'content://com.android.contacts/data';
const String _contactsPhoneFilterUri =
    'content://com.android.contacts/data/phones/filter';
const String _contactsEmailFilterUri =
    'content://com.android.contacts/data/emails/filter';
const String _smsUri = 'content://sms';
const String _mmsUri = 'content://mms';
const String _mmsPartUri = 'content://mms/part';
const String _mmsSmsConversationsUri =
    'content://mms-sms/conversations?simple=true';

/// Masks an address for [diag] logging so full MSISDNs / emails don't
/// land in logcat (where third-party crash reporters and `adb logcat -d`
/// can scrape them). Keeps enough surrounding context to triage:
///
/// * `+16165551234` → `+161****1234`
/// * `email@example.com` → `e***@example.com`
/// * `…@rcs.google.com` → `<rcsToken@rcs.google.com>`
/// * shortcodes (≤6 digits) and empties pass through unchanged so the
///   `192`-style sender numbers remain readable in logs.
String _maskAddress(String? raw) {
  if (raw == null) return 'null';
  final s = raw.trim();
  if (s.isEmpty) return '(blank)';
  if (s.endsWith('@rcs.google.com')) return '<rcsToken@rcs.google.com>';
  final at = s.indexOf('@');
  if (at > 0) {
    return '${s.substring(0, 1)}***${s.substring(at)}';
  }
  if (s.length <= 6) return s;
  return '${s.substring(0, 4)}****${s.substring(s.length - 4)}';
}

String _maskAddresses(Iterable<String?> raws, {String sep = '|'}) =>
    raws.map(_maskAddress).join(sep);

/// Returns true when [value] looks like an opaque RCS / chat-session
/// token rather than a real phone number, email, or shortcode.
///
/// Two shapes seen in the wild:
///  * Google RCS: `…@rcs.google.com` (contains `@`)
///  * Samsung RCS: `d4vtcnrrgy2damjzgu4tghztircdsrrsgrcc…` — long
///    alphanumeric string with no spaces, no punctuation, no `+` prefix,
///    and no `@`. Mirrors the heuristic in `_sanitizeTitle` on the host
///    side (`android_converters.dart`).
///
/// Phone numbers always start with `+` or a digit and are at most ~15
/// chars (E.164 max). Shortcodes are ≤6 digits. Anything that's 20+
/// chars, all `[a-zA-Z0-9_-]`, no whitespace/punctuation is not a phone
/// number.
bool _looksLikeOpaqueToken(String value) {
  if (value.contains('@')) return true;
  final trimmed = value.trim();
  return trimmed.length >= 20 &&
      !trimmed.contains(RegExp(r'[\s.,+()]')) &&
      RegExp(r'^[a-zA-Z0-9_-]+$').hasMatch(trimmed);
}

class LookupService {
  /// Set of E.164-normalized MSISDNs that belong to the device user
  /// itself — populated by the consumer (e.g. simple-messages) at
  /// bootstrap from `SimpleTelephonyNative.listSimCards()` and used by
  /// MMS-addr-derived participant resolution to drop self entries from
  /// thread participant sets.
  ///
  /// Default empty: when not configured, group-MMS threads (and the
  /// RCS-token fallback path that recovers participants from
  /// `content://mms/{id}/addr`) will include the user's own number as
  /// a participant. Tiles render slightly noisier but routing stays
  /// correct.
  ///
  /// Reset with `setSelfNumbers(const {})` if e.g. the active SIM
  /// changes mid-session.
  static Set<String> _selfNumbers = const <String>{};

  /// Replaces the self-MSISDN set used by MMS-addr filtering. Call
  /// once at app bootstrap with the result of an E.164 normalization
  /// pass over `SimpleTelephonyNative.listSimCards()` `number` fields,
  /// dropping empty values. Plugin keeps its dep-graph self-contained
  /// by accepting these values rather than reaching into
  /// `simple_telephony`.
  static void setSelfNumbers(Set<String> numbers) {
    _selfNumbers = Set<String>.unmodifiable(numbers);
    debugPrint(
      '[diag][simple-sms] LookupService.setSelfNumbers '
      'count=${numbers.length} values=${_maskAddresses(numbers)}',
    );
  }

  /// Looks up a contact by their database ID.
  ///
  /// Returns null if the contact is not found or if the query fails.
  Future<AndroidContact?> lookupContactById(int contactId) async {
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        // Use platformSpecific so simple_query doesn't canonicalize
        // the row into its domain schema (id/displayName/…) — we
        // need raw Android columns (_id, display_name, …) to feed
        // AndroidContact.fromRaw.
        domain: QueryDomain.platformSpecific,
        platformData: {'contentUri': _contactsUri},
        filters: [
          QueryFilterCondition(
            field: '_id',
            operator: QueryFilterOperator.equals,
            value: contactId.toString(),
          ),
        ],
      ),
    );
    if (response.records.isEmpty) return null;
    return AndroidContact.fromRaw(
      Map<String, dynamic>.from(response.records.first),
    );
  }

  /// Looks up a contactable (lightweight contact info) by phone number or email.
  ///
  /// Uses the Android contacts filter URI to match the address against
  /// phone numbers or email addresses in the contacts database.
  Future<Contactable?> lookupContactableByAddress(String address) async {
    final isEmail = address.contains('@');
    final uri = isEmail
        ? '$_contactsEmailFilterUri/$address'
        : '$_contactsPhoneFilterUri/$address';
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        platformData: {'contentUri': uri},
      ),
    );
    if (response.records.isEmpty) return null;
    return Contactable.fromRaw(
      Map<String, dynamic>.from(response.records.first),
    );
  }

  /// Looks up an MMS message by its database ID.
  Future<Mms?> lookupMmsById(int messageId) async {
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        platformData: {'contentUri': _mmsUri},
        filters: [
          QueryFilterCondition(
            field: '_id',
            operator: QueryFilterOperator.equals,
            value: messageId.toString(),
          ),
        ],
      ),
    );
    if (response.records.isEmpty) return null;
    return Mms.fromRaw(
      Map<String, dynamic>.from(response.records.first),
    );
  }

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
  Future<List<MmsParticipant>> listMmsAddressesByMessage(int mmsId) async {
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        platformData: {'contentUri': 'content://mms/$mmsId/addr'},
      ),
    );
    return response.records
        .map(
          (row) => MmsParticipant.fromRaw(Map<String, dynamic>.from(row)),
        )
        .toList(growable: false);
  }

  /// Resolves (or lazily creates) the thread id for a given recipient set.
  ///
  /// Queries `content://mms-sms/threadID` with one `recipient=<addr>` query
  /// parameter per address. Android's Telephony provider will return the
  /// existing thread id that matches the recipient set, or allocate a new
  /// thread id and return it — the same contract
  /// `Telephony.Threads.getOrCreateThreadId(Context, Set<String>)` gives on
  /// the Java side.
  ///
  /// Needed when composing a brand-new conversation from the app side:
  /// before inserting into `content://sms` you must know the thread id so
  /// the Messages app + provider UI group the new message correctly.
  /// Without this, first-message-to-new-recipient flows break thread
  /// grouping.
  ///
  /// Returns null when [addresses] is empty or no row comes back.
  Future<int?> resolveThreadIdByAddresses(Iterable<String> addresses) async {
    final cleaned =
        addresses
            .map((a) => a.trim())
            .where((a) => a.isNotEmpty)
            .toList(growable: false);
    if (cleaned.isEmpty) return null;
    // Android's Telephony.Threads builds this URI by appending one
    // `recipient` query param per address. The provider joins them
    // internally to look up or create a single thread. We URL-encode
    // each address (phone numbers with `+` need it) so pluses don't
    // become spaces on the native side.
    final params = cleaned
        .map((a) => 'recipient=${Uri.encodeQueryComponent(a)}')
        .join('&');
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        platformData: {'contentUri': 'content://mms-sms/threadID?$params'},
      ),
    );
    if (response.records.isEmpty) return null;
    final row = response.records.first;
    final rawId = row['_id'] ?? row['id'];
    if (rawId is int) return rawId;
    if (rawId is String) return int.tryParse(rawId.trim());
    return null;
  }

  /// Resolves a canonical address (phone number) from a recipient ID.
  ///
  /// Android stores conversation recipients as numeric IDs that map to
  /// canonical addresses via the `content://mms-sms/canonical-address/` URI.
  Future<String?> resolveCanonicalAddress(String recipientId) async {
    try {
      final response = await SimpleQuery.instance.query(
        QueryRequest(
          domain: QueryDomain.platformSpecific,
          platformData: {
            'contentUri': 'content://mms-sms/canonical-address/$recipientId',
          },
        ),
      );
      if (response.records.isEmpty) {
        debugPrint(
          '[diag][simple-sms] resolveCanonicalAddress recipientId=$recipientId '
          'records=0 address=null',
        );
        return null;
      }
      final raw = response.records.first;
      final address = raw['address']?.toString();
      debugPrint(
        '[diag][simple-sms] resolveCanonicalAddress recipientId=$recipientId '
        'records=${response.records.length} address=${_maskAddress(address)} '
        'rawKeys=${raw.keys.toList()}',
      );
      return (address != null && address.isNotEmpty) ? address : null;
    } catch (e, s) {
      debugPrint(
        'simple_sms: Failed to resolve canonical address $recipientId: $e',
      );
      debugPrint(s.toString());
      return null;
    }
  }

  /// Gets all SMS messages in a conversation thread.
  Future<List<Sms>> getSmsByThread(int threadId) async {
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        platformData: {'contentUri': _smsUri},
        filters: [
          QueryFilterCondition(
            field: 'thread_id',
            operator: QueryFilterOperator.equals,
            value: threadId.toString(),
          ),
        ],
      ),
    );
    return response.records
        .map((row) => Sms.fromRaw(Map<String, dynamic>.from(row)))
        .toList();
  }

  /// Lists SMS messages matching the given [filter], ordered by [sort], paged
  /// by [limit] / [offset].
  Future<List<Sms>> listSms({
    SmsFilter? filter,
    SmsSort? sort,
    int? limit,
    int? offset,
  }) async {
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        platformData: {'contentUri': _smsUri},
        filters: _buildSmsFilters(filter),
        sort: _buildSmsSort(sort ?? SmsSort.newestFirst),
        page:
            (limit != null || offset != null)
                ? QueryPage(limit: limit, offset: offset)
                : null,
      ),
    );
    return response.records
        .map((row) => Sms.fromRaw(Map<String, dynamic>.from(row)))
        .toList(growable: false);
  }

  /// Fetches a single SMS by its database id. Returns null if not found.
  Future<Sms?> getSmsById(int id) async {
    final results = await listSms(filter: SmsFilter(ids: [id]));
    return results.isEmpty ? null : results.first;
  }

  /// Gets all MMS messages in a conversation thread.
  Future<List<Mms>> getMmsByThread(int threadId) async {
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        platformData: {'contentUri': _mmsUri},
        filters: [
          QueryFilterCondition(
            field: 'thread_id',
            operator: QueryFilterOperator.equals,
            value: threadId.toString(),
          ),
        ],
      ),
    );
    return response.records
        .map((row) => Mms.fromRaw(Map<String, dynamic>.from(row)))
        .toList(growable: false);
  }

  /// Lists MMS messages matching the given [filter], ordered by [sort], paged
  /// by [limit] / [offset].
  ///
  /// Returned [Mms] instances have empty `recipients` and `parts` — fetch them
  /// separately with:
  ///   - [listMmsParts] for body + attachment parts,
  ///   - [listMmsAddressesByMessage] for the per-message sender/recipient set
  ///     (the right call for group-MMS attribution; don't conflate with the
  ///     thread-level [resolveCanonicalAddress]),
  ///   - or re-materialize everything in one shot via [lookupMmsById].
  ///
  /// Splitting the enrichment keeps a bulk `listMms` call cheap on large
  /// histories; per-row attachment + address fetches are O(N) queries
  /// otherwise.
  Future<List<Mms>> listMms({
    MmsFilter? filter,
    MmsSort? sort,
    int? limit,
    int? offset,
  }) async {
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        platformData: {'contentUri': _mmsUri},
        filters: _buildMmsFilters(filter),
        sort: _buildMmsSort(sort ?? MmsSort.newestFirst),
        page:
            (limit != null || offset != null)
                ? QueryPage(limit: limit, offset: offset)
                : null,
      ),
    );
    return response.records
        .map((row) => Mms.fromRaw(Map<String, dynamic>.from(row)))
        .toList(growable: false);
  }

  /// Lists the parts (text body + attachments) that belong to an MMS message.
  ///
  /// Queries `content://mms/part` filtered by `mid = mmsId`. If
  /// [MmsPartFilter.contentTypeContains] is set, only parts whose `ct`
  /// column contains that substring are returned (e.g. `image/` for all
  /// image attachments).
  Future<List<MmsPart>> listMmsParts({
    required int mmsId,
    MmsPartFilter? filter,
  }) async {
    final conditions = <QueryFilterCondition>[
      QueryFilterCondition(
        field: 'mid',
        operator: QueryFilterOperator.equals,
        value: mmsId.toString(),
      ),
    ];
    final contentType = filter?.contentTypeContains;
    if (contentType != null && contentType.isNotEmpty) {
      conditions.add(
        QueryFilterCondition(
          field: 'ct',
          operator: QueryFilterOperator.contains,
          value: contentType,
        ),
      );
    }
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        filters: conditions,
        platformData: {'contentUri': _mmsPartUri},
      ),
    );
    return response.records
        .map((row) => MmsPart.fromRaw(Map<String, dynamic>.from(row)))
        .toList(growable: false);
  }

  /// Extracts the binary content of a single MMS part to [outputDirectory],
  /// returning the resulting [File]. The file is named [filename] if given,
  /// otherwise a name is derived from the part id + mime type.
  ///
  /// Internally this opens a binary handle via `simple_query`, copies the
  /// temporary content to the caller-specified location, and closes the
  /// handle. Throws if the part cannot be opened or copied.
  Future<File> extractMmsPart({
    required int partId,
    required String outputDirectory,
    String? filename,
  }) async {
    final handle = await SimpleQuery.instance.openBinary(
      BinaryRequest(
        domain: QueryDomain.messages,
        entityType: 'mmsPart',
        recordId: partId.toString(),
      ),
    );
    try {
      final dir = Directory(outputDirectory);
      if (!await dir.exists()) {
        await dir.create(recursive: true);
      }
      final targetName = filename ?? _deriveMmsPartFilename(partId, handle);
      final dest = File('${dir.path}/$targetName');
      await File(handle.localPath).copy(dest.path);
      return dest;
    } finally {
      // closeBinary is a best-effort cleanup; surfacing its failure here
      // would shadow the actual return value (or a real copy/IO error
      // raised in the try block). Keep it logged but non-fatal.
      try {
        await SimpleQuery.instance.closeBinary(handle.handleId);
      } catch (e) {
        debugPrint('simple_sms: closeBinary failed for $partId: $e');
      }
    }
  }

  /// Lists conversations (MMS-SMS threads) matching the given [filter].
  ///
  /// If [enrich] is `true` (default), each returned [AndroidSimpleConversation]
  /// has its `participants`, `latestSms`, and `latestMms` fields populated via
  /// follow-up lookups. Set to `false` to avoid the extra round-trips when
  /// only the flat conversation row is needed.
  Future<List<AndroidSimpleConversation>> listConversations({
    ConversationFilter? filter,
    ConversationSort? sort,
    int? limit,
    int? offset,
    bool enrich = true,
  }) async {
    try {
      final response = await SimpleQuery.instance.query(
        QueryRequest(
          domain: QueryDomain.platformSpecific,
          filters: _buildConversationFilters(filter),
          sort: _buildConversationSort(sort ?? ConversationSort.mostRecent),
          page:
              (limit != null || offset != null)
                  ? QueryPage(limit: limit, offset: offset)
                  : null,
          platformData: {'contentUri': _mmsSmsConversationsUri},
        ),
      );
      final allBare = response.records
          .map(
            (row) => AndroidSimpleConversation.fromRaw(
              Map<String, dynamic>.from(row),
            ),
          )
          .toList(growable: false);

      // Drop phantom orphan rows: empty recipient_ids AND zero
      // messages. AOSP/Samsung leave these behind when a draft is
      // composed-then-discarded or an RCS notification thread is
      // purged after delivery; they have no addressable participant
      // and nothing for the user to read, so they render as a "ghost"
      // tile labelled "Unknown" with an empty thread screen.
      //
      // Rows with empty recipient_ids but message_count > 0 are NOT
      // filtered — better to surface a stripped-down "Unknown" tile
      // than silently hide user-readable history.
      final bare = allBare.where((c) {
        final isPhantom = c.recipientIds.isEmpty &&
            (c.messageCount == null || c.messageCount == 0);
        if (isPhantom) {
          debugPrint(
            '[diag][simple-sms] listConversations dropped phantom thread '
            'simpleId=${c.id} threadId=${c.threadId} date=${c.date} '
            'messageCount=${c.messageCount}',
          );
        }
        return !isPhantom;
      }).toList(growable: false);

      if (!enrich) return bare;

      return Future.wait(bare.map(_enrichConversation));
    } catch (e, s) {
      debugPrint(
        'simple_sms: listConversations failed (filter=$filter): $e',
      );
      debugPrint(s.toString());
      return const [];
    }
  }

  /// Fetches a single conversation by thread id. Returns null if not found.
  ///
  /// Enriched by default; see [listConversations] for the `enrich` parameter.
  Future<AndroidSimpleConversation?> getConversationByThread(
    int threadId, {
    bool enrich = true,
  }) async {
    final results = await listConversations(
      filter: ConversationFilter(threadIds: [threadId]),
      enrich: enrich,
      limit: 1,
    );
    if (results.isEmpty) {
      debugPrint(
        '[diag][simple-sms] getConversationByThread asked=$threadId '
        'returned=NONE',
      );
      return null;
    }
    final r = results.first;
    debugPrint(
      '[diag][simple-sms] getConversationByThread asked=$threadId '
      'returned.simpleId=${r.id} returned.threadId=${r.threadId} '
      'returned.recipientIds=${r.recipientIds.join("|")} '
      'returned.participants=${r.participants?.map((p) => p.value).join("|")}',
    );
    return r;
  }

  /// Resolves participants + latestSms / latestMms for a bare conversation.
  ///
  /// Runs recipient resolution and latest-message lookups in parallel — each
  /// query is an independent content-provider roundtrip, and on a populous
  /// inbox (200 threads × 3 participants) the old serial version paid ~800
  /// sequential provider calls. `Future.wait` drops that to three
  /// concurrent batches per thread.
  Future<AndroidSimpleConversation> _enrichConversation(
    AndroidSimpleConversation base,
  ) async {
    // Resolve every recipientId → Contactable concurrently.
    final participantsFuture = Future.wait(
      base.recipientIds.where((rid) => rid.isNotEmpty).map(_resolveParticipant),
    );

    // Latest SMS + MMS for the thread (each capped at 1 row) in parallel.
    final latestSmsFuture = listSms(
      filter: SmsFilter(threadId: base.threadId),
      sort: SmsSort.newestFirst,
      limit: 1,
    );
    final latestMmsFuture = listMms(
      filter: MmsFilter(threadId: base.threadId),
      sort: MmsSort.newestFirst,
      limit: 1,
    );

    final results = await Future.wait<Object>([
      participantsFuture,
      latestSmsFuture,
      latestMmsFuture,
    ]);
    final participants =
        (results[0] as List<Contactable?>).whereType<Contactable>().toList(
              growable: false,
            );
    final latestSmsList = results[1] as List<Sms>;
    final latestMmsList = results[2] as List<Mms>;

    // RCS-token fallback. When canonical-addresses returns an opaque
    // RCS chat-bot identifier (anything containing `@`, e.g.
    // `…@rcs.google.com`), the contacts provider has nothing to match
    // it against — `lookupContactableByAddress` returns null and the
    // conversation tile renders the literal token. Recover the
    // underlying phone numbers from the per-message MMS addr table
    // (`content://mms/{id}/addr`), which on this device carries real
    // E.164 values alongside the RCS-token canonical entry.
    final tokenIndices = <int>{
      for (var i = 0; i < participants.length; i++)
        if (_looksLikeOpaqueToken(participants[i].value)) i,
    };
    List<Contactable> finalParticipants = participants;
    if (tokenIndices.isNotEmpty) {
      final fallback = await _addressesFromMmsForThread(base.threadId);
      debugPrint(
        '[diag][simple-sms] _enrichConversation rcsTokenFallback '
        'threadId=${base.threadId} '
        'tokens=${_maskAddresses(tokenIndices.map((i) => participants[i].value))} '
        'fallbackAddresses=${_maskAddresses(fallback)}',
      );
      if (fallback.isNotEmpty) {
        // Replace token-shaped participants with MMS-derived ones.
        // Pure-RCS bots with no MMS messages keep the token (fallback
        // empty) so the tile at least renders something stable.
        final keepers = <Contactable>[
          for (var i = 0; i < participants.length; i++)
            if (!tokenIndices.contains(i)) participants[i],
        ];
        final recovered = await Future.wait(
          fallback.map((addr) async =>
              (await lookupContactableByAddress(addr)) ??
              Contactable(id: -1, value: addr)),
        );
        // Dedup keepers + recovered. A keeper participant may share
        // a contact id (or normalized address) with a recovered MMS
        // participant — e.g. a 1-1 RCS thread where one canonical-id
        // resolves cleanly and another is the opaque token, but both
        // refer to the same person. Without dedup the tile would
        // double-render that person.
        final seen = <String>{};
        String key(Contactable c) => (c.id != -1)
            ? 'id:${c.id}'
            : 'addr:${c.value.trim().toLowerCase()}';
        finalParticipants = <Contactable>[
          for (final c in [...keepers, ...recovered])
            if (seen.add(key(c))) c,
        ];
      }
    }

    debugPrint(
      '[diag][simple-sms] _enrichConversation threadId=${base.threadId} '
      'simpleId=${base.id} recipientIds=${base.recipientIds.join("|")} '
      'participants=${_maskAddresses(finalParticipants.map((p) => p.value))} '
      'latestSmsId=${latestSmsList.isEmpty ? "-" : latestSmsList.first.id} '
      'latestMmsId=${latestMmsList.isEmpty ? "-" : latestMmsList.first.id}',
    );
    return base.enrich(
      participants: finalParticipants,
      latestSms: latestSmsList.isEmpty ? null : latestSmsList.first,
      latestMms: latestMmsList.isEmpty ? null : latestMmsList.first,
    );
  }

  /// Returns distinct, non-self, phone-number-shaped addresses for an
  /// MMS thread, sourced from per-message
  /// [`content://mms/{id}/addr`](https://developer.android.com/reference/android/provider/Telephony.Mms.Addr)
  /// rows.
  ///
  /// Used by [_enrichConversation] as a fall-back when a thread's
  /// canonical-addresses entries are opaque RCS tokens. Skips:
  ///
  /// * `insert-address-token` — Telephony's "self" placeholder for
  ///   outbound MMS rows.
  /// * MSISDNs in `LookupService._selfNumbers` — the device user's own
  ///   numbers (populated by the consumer at bootstrap via
  ///   [setSelfNumbers]).
  /// * Any value containing `@` — defensive guard against further RCS
  ///   tokens that may bleed into the addr rows alongside real phone
  ///   numbers on some OEMs.
  ///
  /// Returns an empty list when the thread has zero MMS messages
  /// (e.g. pure-RCS chat-bot threads with no underlying MMS) — the
  /// caller is expected to fall back to keeping the token participant.
  Future<List<String>> _addressesFromMmsForThread(int threadId) async {
    final mmsList = await getMmsByThread(threadId);
    if (mmsList.isEmpty) return const [];
    final addresses = <String>{};
    for (final mms in mmsList) {
      final addr = await listMmsAddressesByMessage(mms.id);
      for (final a in addr) {
        final v = a.address?.trim();
        if (v == null || v.isEmpty) continue;
        if (v == 'insert-address-token') continue;
        if (_selfNumbers.contains(v)) continue;
        if (_looksLikeOpaqueToken(v)) continue;
        addresses.add(v);
      }
    }
    return addresses.toList(growable: false);
  }

  /// Resolves a single recipient id to a [Contactable]: canonical-address
  /// lookup → contactable-by-address lookup, with a minimal fallback so
  /// the UI always has *something* to render. Returns null only when the
  /// recipient id doesn't resolve to any canonical address.
  Future<Contactable?> _resolveParticipant(String recipientId) async {
    final address = await resolveCanonicalAddress(recipientId);
    if (address == null) {
      debugPrint(
        '[diag][simple-sms] _resolveParticipant recipientId=$recipientId '
        'address=null contactable=null',
      );
      return null;
    }
    final contactable = await lookupContactableByAddress(address);
    debugPrint(
      '[diag][simple-sms] _resolveParticipant recipientId=$recipientId '
      'address=${_maskAddress(address)} '
      'contactable.value=${_maskAddress(contactable?.value)} '
      'hasDisplayName=${contactable?.displayName != null}',
    );
    return contactable ?? Contactable(id: -1, value: address);
  }

  /// Lists every `data` row belonging to a single contact — phone numbers,
  /// emails, and any other MIME-typed data entries. Queries
  /// `content://com.android.contacts/data` filtered by `contact_id`.
  ///
  /// Callers typically filter the returned list on `contactable.mimetype`
  /// (e.g. `vnd.android.cursor.item/phone_v2`,
  /// `vnd.android.cursor.item/email_v2`) to pick the entries they care about.
  Future<List<Contactable>> listContactablesForContact(int contactId) async {
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        filters: [
          QueryFilterCondition(
            field: 'contact_id',
            operator: QueryFilterOperator.equals,
            value: contactId.toString(),
          ),
        ],
        platformData: const {
          'contentUri': _contactsDataUri,
        },
      ),
    );
    return response.records
        .map((row) => Contactable.fromRaw(Map<String, dynamic>.from(row)))
        .toList(growable: false);
  }

  /// Resolves a contact's structured name (given / family / prefix / suffix /
  /// phonetic variants) from the contacts data provider.
  ///
  /// Queries `content://com.android.contacts/data` filtered by `contact_id`,
  /// `mimetype = vnd.android.cursor.item/name`, and optionally `account_type`.
  /// Returns null when the contact has no structured-name row.
  Future<AndroidContactName?> getStructuredName({
    required int contactId,
    String? accountType,
  }) async {
    final filters = <QueryFilterCondition>[
      QueryFilterCondition(
        field: 'contact_id',
        operator: QueryFilterOperator.equals,
        value: contactId.toString(),
      ),
      QueryFilterCondition(
        field: 'mimetype',
        operator: QueryFilterOperator.equals,
        value: 'vnd.android.cursor.item/name',
      ),
      if (accountType != null)
        QueryFilterCondition(
          field: 'account_type',
          operator: QueryFilterOperator.equals,
          value: accountType,
        ),
    ];
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        filters: filters,
        platformData: const {
          'contentUri': _contactsDataUri,
        },
      ),
    );
    if (response.records.isEmpty) return null;
    return AndroidContactName.fromRaw(
      Map<String, dynamic>.from(response.records.first),
    );
  }

  /// Lists contacts matching the given [filter], ordered by [sort], paged by
  /// [limit] / [offset].
  Future<List<AndroidContact>> listContacts({
    ContactFilter? filter,
    ContactSort? sort,
    int? limit,
    int? offset,
  }) async {
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        platformData: {'contentUri': _contactsUri},
        filters: _buildContactFilters(filter),
        sort: _buildContactSort(sort ?? ContactSort.alphabetical),
        page:
            (limit != null || offset != null)
                ? QueryPage(limit: limit, offset: offset)
                : null,
      ),
    );
    return response.records
        .map((row) => AndroidContact.fromRaw(Map<String, dynamic>.from(row)))
        .toList(growable: false);
  }

  String _deriveMmsPartFilename(int partId, BinaryContentHandle handle) {
    return 'mms_part_$partId${_extensionForMime(handle.mimeType)}';
  }

  /// Maps a MIME string to a dotted extension (e.g. `image/jpeg` → `.jpg`).
  /// Returns `''` when the MIME is null or unrecognised — callers attach
  /// the result directly to the filename stem and expect no suffix for
  /// unknown types. Delegates to the canonical [ContentType] table rather
  /// than maintaining a parallel switch.
  static String _extensionForMime(String? mime) {
    if (mime == null) return '';
    for (final ct in ContentType.values) {
      if (ct.value.isNotEmpty && ct.value == mime) return '.${ct.extension}';
    }
    return '';
  }

  // --- Private filter / sort translation -----------------------------------

  /// Translate an [SmsFilter] to the generic [QueryFilterCondition] list the
  /// underlying `simple_query` transport expects.
  List<QueryFilterCondition> _buildSmsFilters(SmsFilter? filter) {
    if (filter == null) return const [];
    final conditions = <QueryFilterCondition>[];

    final ids = filter.ids;
    if (ids != null && ids.isNotEmpty) {
      conditions.add(
        QueryFilterCondition(
          field: '_id',
          operator: QueryFilterOperator.inList,
          value: ids.map((id) => id.toString()).toList(),
        ),
      );
    }
    if (filter.threadId != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'thread_id',
          operator: QueryFilterOperator.equals,
          value: filter.threadId.toString(),
        ),
      );
    }
    if (filter.isRead != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'read',
          operator: QueryFilterOperator.equals,
          value: filter.isRead! ? '1' : '0',
        ),
      );
    }
    final types = filter.types;
    if (types != null && types.isNotEmpty) {
      conditions.add(
        QueryFilterCondition(
          field: 'type',
          operator: QueryFilterOperator.inList,
          value: types.map((t) => t.value.toString()).toList(),
        ),
      );
    }
    if (filter.dateFrom != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'date',
          operator: QueryFilterOperator.greaterThanOrEqual,
          value: filter.dateFrom!.millisecondsSinceEpoch.toString(),
        ),
      );
    }
    if (filter.dateTo != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'date',
          operator: QueryFilterOperator.lessThanOrEqual,
          value: filter.dateTo!.millisecondsSinceEpoch.toString(),
        ),
      );
    }
    if (filter.addressContains != null && filter.addressContains!.isNotEmpty) {
      conditions.add(
        QueryFilterCondition(
          field: 'address',
          operator: QueryFilterOperator.contains,
          value: filter.addressContains,
        ),
      );
    }
    if (filter.subscriptionId != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'sub_id',
          operator: QueryFilterOperator.equals,
          value: filter.subscriptionId.toString(),
        ),
      );
    }
    if (filter.idAfter != null) {
      conditions.add(
        QueryFilterCondition(
          field: '_id',
          operator: QueryFilterOperator.greaterThan,
          value: filter.idAfter!.toString(),
        ),
      );
    }
    return conditions;
  }

  List<QuerySort> _buildSmsSort(SmsSort sort) {
    final column = switch (sort.field) {
      SmsSortField.id => '_id',
      SmsSortField.date => 'date',
      SmsSortField.threadId => 'thread_id',
    };
    return [QuerySort(field: column, direction: _dir(sort.direction))];
  }

  /// Translate an [MmsFilter] to [QueryFilterCondition]s.
  ///
  /// Note: MMS stores `date` in **seconds** since epoch, not milliseconds, so
  /// the [DateTime] values are divided by 1000 before being compared.
  List<QueryFilterCondition> _buildMmsFilters(MmsFilter? filter) {
    if (filter == null) return const [];
    final conditions = <QueryFilterCondition>[];

    final ids = filter.ids;
    if (ids != null && ids.isNotEmpty) {
      conditions.add(
        QueryFilterCondition(
          field: '_id',
          operator: QueryFilterOperator.inList,
          value: ids.map((id) => id.toString()).toList(),
        ),
      );
    }
    if (filter.threadId != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'thread_id',
          operator: QueryFilterOperator.equals,
          value: filter.threadId.toString(),
        ),
      );
    }
    if (filter.isRead != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'read',
          operator: QueryFilterOperator.equals,
          value: filter.isRead! ? '1' : '0',
        ),
      );
    }
    final types = filter.types;
    if (types != null && types.isNotEmpty) {
      conditions.add(
        QueryFilterCondition(
          field: 'm_type',
          operator: QueryFilterOperator.inList,
          value: types.map((t) => t.value.toString()).toList(),
        ),
      );
    }
    if (filter.dateFrom != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'date',
          operator: QueryFilterOperator.greaterThanOrEqual,
          value: (filter.dateFrom!.millisecondsSinceEpoch ~/ 1000).toString(),
        ),
      );
    }
    if (filter.dateTo != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'date',
          operator: QueryFilterOperator.lessThanOrEqual,
          value: (filter.dateTo!.millisecondsSinceEpoch ~/ 1000).toString(),
        ),
      );
    }
    if (filter.subscriptionId != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'sub_id',
          operator: QueryFilterOperator.equals,
          value: filter.subscriptionId.toString(),
        ),
      );
    }
    if (filter.idAfter != null) {
      conditions.add(
        QueryFilterCondition(
          field: '_id',
          operator: QueryFilterOperator.greaterThan,
          value: filter.idAfter!.toString(),
        ),
      );
    }
    return conditions;
  }

  List<QuerySort> _buildMmsSort(MmsSort sort) {
    final column = switch (sort.field) {
      MmsSortField.id => '_id',
      MmsSortField.date => 'date',
      MmsSortField.threadId => 'thread_id',
    };
    return [QuerySort(field: column, direction: _dir(sort.direction))];
  }

  QuerySortDirection _dir(SortDirection d) =>
      d == SortDirection.ascending
          ? QuerySortDirection.ascending
          : QuerySortDirection.descending;

  /// Translate a [ContactFilter] to [QueryFilterCondition]s.
  List<QueryFilterCondition> _buildContactFilters(ContactFilter? filter) {
    if (filter == null) return const [];
    final conditions = <QueryFilterCondition>[];

    final ids = filter.ids;
    if (ids != null && ids.isNotEmpty) {
      conditions.add(
        QueryFilterCondition(
          field: '_id',
          operator: QueryFilterOperator.inList,
          value: ids.map((id) => id.toString()).toList(),
        ),
      );
    }
    if (filter.displayNameContains != null &&
        filter.displayNameContains!.isNotEmpty) {
      conditions.add(
        QueryFilterCondition(
          field: 'display_name',
          operator: QueryFilterOperator.contains,
          value: filter.displayNameContains,
        ),
      );
    }
    if (filter.hasPhoneNumber != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'has_phone_number',
          operator: QueryFilterOperator.equals,
          value: filter.hasPhoneNumber! ? '1' : '0',
        ),
      );
    }
    if (filter.hasEmail != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'has_email',
          operator: QueryFilterOperator.equals,
          value: filter.hasEmail! ? '1' : '0',
        ),
      );
    }
    if (filter.inVisibleGroup != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'in_visible_group',
          operator: QueryFilterOperator.equals,
          value: filter.inVisibleGroup! ? '1' : '0',
        ),
      );
    }
    if (filter.idAfter != null) {
      conditions.add(
        QueryFilterCondition(
          field: '_id',
          operator: QueryFilterOperator.greaterThan,
          value: filter.idAfter!.toString(),
        ),
      );
    }
    return conditions;
  }

  /// Translate a [ConversationFilter] to [QueryFilterCondition]s.
  ///
  /// The `content://mms-sms/conversations?simple=true` view exposes the
  /// thread id as `_id` — there is **no separate `thread_id` column**.
  /// AOSP's `MmsSmsProvider` resolves the URI to a `SELECT * FROM threads`
  /// (or, on Samsung, `view_threads`); both expose the thread PK as
  /// `_id`. Filtering on `thread_id` SQLites out with
  ///   `SQLiteException: no such column: thread_id`
  /// (verified on Samsung Android 16; same on stock AOSP).
  ///
  /// A previous comment here claimed `_id` was the latest-message id and
  /// `thread_id` the stable PK — that was wrong. The simple-conversations
  /// view's `_id` IS the thread id.
  ///
  /// Other columns: `date` (last-activity timestamp), `read` (int flag),
  /// `has_attachment` (int flag), `archived` (int flag).
  List<QueryFilterCondition> _buildConversationFilters(
    ConversationFilter? filter,
  ) {
    if (filter == null) return const [];
    final conditions = <QueryFilterCondition>[];

    final threadIds = filter.threadIds;
    if (threadIds != null && threadIds.isNotEmpty) {
      conditions.add(
        QueryFilterCondition(
          field: '_id',
          operator: QueryFilterOperator.inList,
          value: threadIds.map((id) => id.toString()).toList(),
        ),
      );
    }
    if (filter.isArchived != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'archived',
          operator: QueryFilterOperator.equals,
          value: filter.isArchived! ? '1' : '0',
        ),
      );
    }
    if (filter.hasUnread != null) {
      // `read = 0` on the conversations view means the thread has unread.
      conditions.add(
        QueryFilterCondition(
          field: 'read',
          operator: QueryFilterOperator.equals,
          value: filter.hasUnread! ? '0' : '1',
        ),
      );
    }
    // The `mms-sms/conversations?simple=true` view surfaces `date` in
    // **seconds** on Samsung Android 16 (Prospector dump confirms:
    // 1776546259, not 1776546259000). Matches the MMS `date` column the
    // row originates from — the simple conversations view is effectively
    // the newest SMS/MMS row per thread, and MMS stores `date` in
    // seconds per OMA MMS. SMS stores `date` in millis. So this builder
    // mirrors `_buildMmsFilters` (÷1000), not `_buildSmsFilters`.
    if (filter.dateFrom != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'date',
          operator: QueryFilterOperator.greaterThanOrEqual,
          value: (filter.dateFrom!.millisecondsSinceEpoch ~/ 1000).toString(),
        ),
      );
    }
    if (filter.dateTo != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'date',
          operator: QueryFilterOperator.lessThanOrEqual,
          value: (filter.dateTo!.millisecondsSinceEpoch ~/ 1000).toString(),
        ),
      );
    }
    if (filter.hasAttachment != null) {
      conditions.add(
        QueryFilterCondition(
          field: 'has_attachment',
          operator: QueryFilterOperator.equals,
          value: filter.hasAttachment! ? '1' : '0',
        ),
      );
    }
    if (filter.threadIdAfter != null) {
      conditions.add(
        QueryFilterCondition(
          field: '_id',
          operator: QueryFilterOperator.greaterThan,
          value: filter.threadIdAfter!.toString(),
        ),
      );
    }
    return conditions;
  }

  List<QuerySort> _buildConversationSort(ConversationSort sort) {
    final column = switch (sort.field) {
      ConversationSortField.date => 'date',
      ConversationSortField.id => '_id',
    };
    return [QuerySort(field: column, direction: _dir(sort.direction))];
  }

  List<QuerySort> _buildContactSort(ContactSort sort) {
    final column = switch (sort.field) {
      ContactSortField.displayName => 'display_name',
      ContactSortField.id => '_id',
      ContactSortField.lastTimeContacted => 'last_time_contacted',
    };
    return [QuerySort(field: column, direction: _dir(sort.direction))];
  }
}
