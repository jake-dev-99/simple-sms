import 'package:flutter/foundation.dart';
import 'package:simple_query/simple_query.dart';

import '../models/conversations/mms_sms_simple_conversations.dart';
import '../models/filters/conversation_filter.dart';
import '../models/filters/mms_filter.dart';
import '../models/filters/sms_filter.dart';
import '../models/filters/sort_direction.dart';
import '../models/messages/mms.dart';
import '../models/messages/sms.dart';
import '../models/people/contactables.dart';
import 'attachment_extractor.dart';
import 'contact_lookup.dart';
import 'message_lookup.dart';

/// Conversation (MMS-SMS thread) row queries against the Android
/// Telephony provider.
///
/// **Tier 0f extraction (audit Step 0f).** Originally lived inside
/// [LookupService]; split out so per-domain modules localise change-
/// blast radius. LookupService keeps its public API for backward-
/// compat — every method here is reached via LookupService delegation.
///
/// Composes [ContactLookup] + [MessageLookup] for participant +
/// latest-message enrichment.
const String _mmsSmsConversationsUri =
    'content://mms-sms/conversations?simple=true';

/// Masks an address for [diag] logging so full MSISDNs / emails don't
/// land in logcat. Duplicated from lookup_service.dart (where the
/// original lived) because file-private. Future cleanup could promote
/// this to a shared masking util once a third caller shows up.
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
///  * Google RCS: `…@rcs.google.com` — domain-specific RCS bot address.
///  * Samsung RCS: `d4vtcnrrgy2damjzgu4tghztircdsrrsgrcc…` — long
///    alphanumeric string with no spaces, no punctuation, no `+` prefix,
///    and no `@`. Mirrors the heuristic in `_sanitizeTitle` on the host
///    side (`android_converters.dart`).
///
/// Phone numbers always start with `+` or a digit and are at most ~15
/// chars (E.164 max). Shortcodes are ≤6 digits. Anything that's 20+
/// chars, all `[a-zA-Z0-9_-]`, no whitespace/punctuation is not a phone
/// number.
///
/// Note: conventional email addresses (user@gmail.com) are NOT treated
/// as tokens — only RCS-specific domains. The canonical-address provider
/// returns phone numbers or RCS identifiers, never emails.
bool _looksLikeOpaqueToken(String value) {
  final trimmed = value.trim();
  if (trimmed.endsWith('@rcs.google.com') ||
      trimmed.endsWith('@rcs.android.com')) {
    return true;
  }
  return trimmed.length >= 20 &&
      !trimmed.contains(RegExp(r'[\s.,+()@]')) &&
      RegExp(r'^[a-zA-Z0-9_-]+$').hasMatch(trimmed);
}

class ConversationLookup {
  ConversationLookup._();

  static final ConversationLookup instance = ConversationLookup._();

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
  Set<String> _selfNumbers = const <String>{};

  /// Replaces the self-MSISDN set used by MMS-addr filtering. Call
  /// once at app bootstrap with the result of an E.164 normalization
  /// pass over `SimpleTelephonyNative.listSimCards()` `number` fields,
  /// dropping empty values. Plugin keeps its dep-graph self-contained
  /// by accepting these values rather than reaching into
  /// `simple_telephony`.
  void setSelfNumbers(Set<String> numbers) {
    _selfNumbers = Set<String>.unmodifiable(numbers);
    debugPrint(
      '[diag][simple-sms] ConversationLookup.setSelfNumbers '
      'count=${numbers.length} values=${_maskAddresses(numbers)}',
    );
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
          sort: _buildConversationSort(sort ?? ConversationSort.mostRecent), // ignore: silent_default
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

      final enriched = await Future.wait(bare.map(_enrichConversation));
      // Hydrate every thread's latest-MMS parts in ONE batched query rather
      // than one `listMmsParts` per thread (UNFY-250 perf) so the MMS body
      // resolves for the conversation-list preview at parity with SMS.
      await _hydrateLatestMmsParts(enriched);
      return enriched;
    } catch (e, s) {
      // Per the file-level contract on lookup_service.dart: query
      // methods do NOT swallow exceptions. The previous
      // `catch + return const []` here was the regression that
      // recreated the exact thread_id SQL bug class — silent empty
      // list across thousands of full-sync calls, only surfacing when
      // realtime force-unwrapped a `!`. Log diagnostics for triage,
      // then rethrow so callers can decide.
      debugPrint(
        'simple_sms: listConversations failed (filter=$filter): $e',
      );
      debugPrint(s.toString());
      rethrow;
    }
  }

  /// Attaches each conversation's latest-MMS parts, fetched in a single batched
  /// query ([AttachmentExtractor.listMmsPartsForMessages]). The bare
  /// `content://mms` list query doesn't join the part table, so without this
  /// the MMS body is empty for the conversation-list preview. Best-effort: a
  /// batch failure leaves parts null (empty preview) rather than failing the
  /// whole list (UNFY-250).
  Future<void> _hydrateLatestMmsParts(
    List<AndroidSimpleConversation> conversations,
  ) async {
    final latestMmsIds = <int>[
      for (final c in conversations)
        if (c.latestMms != null) c.latestMms!.id,
    ];
    if (latestMmsIds.isEmpty) return;
    try {
      final partsByMid = await AttachmentExtractor.instance
          .listMmsPartsForMessages(latestMmsIds);
      for (final c in conversations) {
        final latestMms = c.latestMms;
        if (latestMms != null) {
          latestMms.parts = partsByMid[latestMms.id];
        }
      }
    } catch (e) {
      debugPrint(
        '[diag][simple-sms] batch latest-MMS parts hydration failed '
        '(${latestMmsIds.length} ids): $e',
      );
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
    final latestSmsFuture = MessageLookup.instance.listSms(
      filter: SmsFilter(threadId: base.threadId),
      sort: SmsSort.newestFirst,
      limit: 1,
    );
    final latestMmsFuture = MessageLookup.instance.listMms(
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
              (await ContactLookup.instance.lookupContactableByAddress(addr)) ??
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
  /// * MSISDNs in [_selfNumbers] — the device user's own numbers
  ///   (populated by the consumer at bootstrap via [setSelfNumbers]).
  /// * Any value containing `@` — defensive guard against further RCS
  ///   tokens that may bleed into the addr rows alongside real phone
  ///   numbers on some OEMs.
  ///
  /// Returns an empty list when the thread has zero MMS messages
  /// (e.g. pure-RCS chat-bot threads with no underlying MMS) — the
  /// caller is expected to fall back to keeping the token participant.
  Future<List<String>> _addressesFromMmsForThread(int threadId) async {
    final mmsList = await MessageLookup.instance.getMmsByThread(threadId);
    if (mmsList.isEmpty) return const [];
    final addresses = <String>{};
    for (final mms in mmsList) {
      final addr =
          await MessageLookup.instance.listMmsAddressesByMessage(mms.id);
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
    final address =
        await MessageLookup.instance.resolveCanonicalAddress(recipientId);
    if (address == null) {
      debugPrint(
        '[diag][simple-sms] _resolveParticipant recipientId=$recipientId '
        'address=null contactable=null',
      );
      return null;
    }
    final contactable =
        await ContactLookup.instance.lookupContactableByAddress(address);
    debugPrint(
      '[diag][simple-sms] _resolveParticipant recipientId=$recipientId '
      'address=${_maskAddress(address)} '
      'contactable.value=${_maskAddress(contactable?.value)} '
      'hasDisplayName=${contactable?.displayName != null}',
    );
    return contactable ?? Contactable(id: -1, value: address);
  }

  // --- Private filter / sort translation -----------------------------------

  QuerySortDirection _dir(SortDirection d) =>
      d == SortDirection.ascending
          ? QuerySortDirection.ascending
          : QuerySortDirection.descending;

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
}
