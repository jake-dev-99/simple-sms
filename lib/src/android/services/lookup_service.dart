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
const String _smsUri = 'content://sms';
const String _mmsUri = 'content://mms';
const String _mmsPartUri = 'content://mms/part';
const String _mmsSmsConversationsUri =
    'content://mms-sms/conversations?simple=true';

class LookupService {
  /// Looks up a contact by their database ID.
  ///
  /// Returns null if the contact is not found or if the query fails.
  Future<AndroidContact?> lookupContactById(int contactId) async {
    try {
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
    } catch (e, s) {
      debugPrint('simple_sms: Failed to lookup contact $contactId: $e');
      debugPrint(s.toString());
      return null;
    }
  }

  /// Looks up a contactable (lightweight contact info) by phone number or email.
  ///
  /// Uses the Android contacts filter URI to match the address against
  /// phone numbers or email addresses in the contacts database.
  Future<Contactable?> lookupContactableByAddress(String address) async {
    try {
      final isEmail = address.contains('@');
      final uri =
          isEmail
              ? 'content://com.android.contacts/data/emails/filter/$address'
              : 'content://com.android.contacts/data/phones/filter/$address';
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
    } catch (e, s) {
      debugPrint('simple_sms: Failed to lookup contactable for $address: $e');
      debugPrint(s.toString());
      return null;
    }
  }

  /// Looks up an MMS message by its database ID.
  Future<Mms?> lookupMmsById(int messageId) async {
    try {
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
      return await Mms.fromRaw(
        Map<String, dynamic>.from(response.records.first),
      );
    } catch (e, s) {
      debugPrint('simple_sms: Failed to lookup MMS $messageId: $e');
      debugPrint(s.toString());
      return null;
    }
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
  ///
  /// Returns an empty list on any query failure — errors are logged but
  /// not thrown so a single bad lookup doesn't abort a page sync.
  Future<List<MmsParticipant>> listMmsAddressesByMessage(int mmsId) async {
    try {
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
    } catch (e, s) {
      debugPrint(
        'simple_sms: Failed to list MMS addresses for $mmsId: $e',
      );
      debugPrint(s.toString());
      return const [];
    }
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
  /// Returns null on any query failure or empty addresses input.
  Future<int?> resolveThreadIdByAddresses(Iterable<String> addresses) async {
    final cleaned =
        addresses
            .map((a) => a.trim())
            .where((a) => a.isNotEmpty)
            .toList(growable: false);
    if (cleaned.isEmpty) return null;
    try {
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
    } catch (e, s) {
      // Don't log the addresses themselves — phone numbers + emails are
      // PII and debugPrint ends up in Crashlytics + device logs on
      // consumer builds. Count is enough to triage without leaking
      // values. Mirrors `resolveCanonicalAddress` above, which logs
      // only the opaque recipientId.
      debugPrint(
        'simple_sms: Failed to resolve threadId for ${cleaned.length} '
        'address(es): $e',
      );
      debugPrint(s.toString());
      return null;
    }
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
      if (response.records.isEmpty) return null;
      final address = response.records.first['address']?.toString();
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
    try {
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
    } catch (e, s) {
      debugPrint('simple_sms: Failed to get SMS for thread $threadId: $e');
      debugPrint(s.toString());
      return [];
    }
  }

  /// Lists SMS messages matching the given [filter], ordered by [sort], paged
  /// by [limit] / [offset].
  ///
  /// Returns an empty list on query failure.
  Future<List<Sms>> listSms({
    SmsFilter? filter,
    SmsSort? sort,
    int? limit,
    int? offset,
  }) async {
    try {
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
    } catch (e, s) {
      debugPrint('simple_sms: Failed to list SMS ($filter): $e');
      debugPrint(s.toString());
      return const [];
    }
  }

  /// Fetches a single SMS by its database id. Returns null if not found.
  Future<Sms?> getSmsById(int id) async {
    final results = await listSms(filter: SmsFilter(ids: [id]));
    return results.isEmpty ? null : results.first;
  }

  /// Gets all MMS messages in a conversation thread.
  Future<List<Mms>> getMmsByThread(int threadId) async {
    try {
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
      final results = <Mms>[];
      for (final row in response.records) {
        results.add(await Mms.fromRaw(Map<String, dynamic>.from(row)));
      }
      return results;
    } catch (e, s) {
      debugPrint('simple_sms: Failed to get MMS for thread $threadId: $e');
      debugPrint(s.toString());
      return [];
    }
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
    try {
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
      final results = <Mms>[];
      for (final row in response.records) {
        results.add(await Mms.fromRaw(Map<String, dynamic>.from(row)));
      }
      return results;
    } catch (e, s) {
      debugPrint('simple_sms: Failed to list MMS ($filter): $e');
      debugPrint(s.toString());
      return const [];
    }
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
    try {
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
    } catch (e, s) {
      debugPrint('simple_sms: Failed to list MMS parts for $mmsId: $e');
      debugPrint(s.toString());
      return const [];
    }
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
      final bare = response.records
          .map(
            (row) => AndroidSimpleConversation.fromRaw(
              Map<String, dynamic>.from(row),
            ),
          )
          .toList(growable: false);
      if (!enrich) return bare;

      return Future.wait(bare.map(_enrichConversation));
    } catch (e, s) {
      debugPrint('simple_sms: Failed to list conversations ($filter): $e');
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
    return results.isEmpty ? null : results.first;
  }

  /// Resolves participants + latestSms / latestMms for a bare conversation.
  Future<AndroidSimpleConversation> _enrichConversation(
    AndroidSimpleConversation base,
  ) async {
    // Resolve each recipientId → Contactable.
    final participants = <Contactable>[];
    for (final rid in base.recipientIds) {
      if (rid.isEmpty) continue;
      final address = await resolveCanonicalAddress(rid);
      if (address == null) continue;
      final contactable = await lookupContactableByAddress(address);
      if (contactable != null) {
        participants.add(contactable);
      } else {
        // Fall back to a minimal Contactable keyed by the address only, so the
        // caller always has something to show.
        participants.add(Contactable(id: -1, value: address));
      }
    }

    // Latest SMS + MMS for the thread (each capped at 1 row).
    final latestSmsList = await listSms(
      filter: SmsFilter(threadId: base.threadId),
      sort: SmsSort.newestFirst,
      limit: 1,
    );
    final latestMmsList = await listMms(
      filter: MmsFilter(threadId: base.threadId),
      sort: MmsSort.newestFirst,
      limit: 1,
    );

    return base.enrich(
      participants: participants,
      latestSms: latestSmsList.isEmpty ? null : latestSmsList.first,
      latestMms: latestMmsList.isEmpty ? null : latestMmsList.first,
    );
  }

  /// Lists every `data` row belonging to a single contact — phone numbers,
  /// emails, and any other MIME-typed data entries. Queries
  /// `content://com.android.contacts/data` filtered by `contact_id`.
  ///
  /// Callers typically filter the returned list on `contactable.mimetype`
  /// (e.g. `vnd.android.cursor.item/phone_v2`,
  /// `vnd.android.cursor.item/email_v2`) to pick the entries they care about.
  Future<List<Contactable>> listContactablesForContact(int contactId) async {
    try {
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
            'contentUri': 'content://com.android.contacts/data',
          },
        ),
      );
      return response.records
          .map((row) => Contactable.fromRaw(Map<String, dynamic>.from(row)))
          .toList(growable: false);
    } catch (e, s) {
      debugPrint(
        'simple_sms: Failed to list contactables for contact $contactId: $e',
      );
      debugPrint(s.toString());
      return const [];
    }
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
    try {
      final filters = <QueryFilterCondition>[
        QueryFilterCondition(
          field: 'contact_id',
          operator: QueryFilterOperator.equals,
          value: contactId.toString(),
        ),
        const QueryFilterCondition(
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
            'contentUri': 'content://com.android.contacts/data',
          },
        ),
      );
      if (response.records.isEmpty) return null;
      return AndroidContactName.fromRaw(
        Map<String, dynamic>.from(response.records.first),
      );
    } catch (e, s) {
      debugPrint(
        'simple_sms: Failed to get structured name for $contactId: $e',
      );
      debugPrint(s.toString());
      return null;
    }
  }

  /// Lists contacts matching the given [filter], ordered by [sort], paged by
  /// [limit] / [offset].
  Future<List<AndroidContact>> listContacts({
    ContactFilter? filter,
    ContactSort? sort,
    int? limit,
    int? offset,
  }) async {
    try {
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
    } catch (e, s) {
      debugPrint('simple_sms: Failed to list contacts ($filter): $e');
      debugPrint(s.toString());
      return const [];
    }
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
  /// The `content://mms-sms/conversations?simple=true` view exposes `_id`
  /// as the **latest-message id** in the thread (NOT the thread id, on
  /// Samsung Android 16 and AOSP's Mms-Sms provider), `thread_id` as the
  /// stable thread primary key, `date` as the last-activity timestamp,
  /// `read` as an int flag, and `has_attachment` as an int flag. Thread
  /// filtering goes through `thread_id` so joins with `SmsFilter.threadId`
  /// / `MmsFilter.threadId` stay consistent.
  List<QueryFilterCondition> _buildConversationFilters(
    ConversationFilter? filter,
  ) {
    if (filter == null) return const [];
    final conditions = <QueryFilterCondition>[];

    final threadIds = filter.threadIds;
    if (threadIds != null && threadIds.isNotEmpty) {
      conditions.add(
        QueryFilterCondition(
          field: 'thread_id',
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
          field: 'thread_id',
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
