import 'package:flutter/foundation.dart';
import 'package:simple_query/simple_query.dart';

import '../models/filters/mms_filter.dart';
import '../models/filters/sms_filter.dart';
import '../models/filters/sort_direction.dart';
import '../models/messages/mms.dart';
import '../models/messages/sms.dart';
import '../models/people/mms_participant.dart';

/// SMS + MMS row queries against the Android Telephony provider.
///
/// **Tier 0f extraction (audit Step 0f).** Originally lived inside
/// [LookupService]; split out so per-domain modules localise change-
/// blast radius. LookupService keeps its public API for backward-
/// compat — every method here is reached via LookupService delegation.
///
/// Future work — `SimpleQuery` injection: deferred until a plugin-
/// wide DI pass.

// File-private content URIs.
const String _smsUri = 'content://sms';
const String _mmsUri = 'content://mms';

/// Mask an address for diagnostic logging — full MSISDNs / emails
/// don't land in logcat unmasked. Duplicated from lookup_service.dart
/// (where the original lived) because file-private. Future cleanup
/// could promote this to a shared masking util once a third caller
/// shows up.
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

class MessageLookup {
  MessageLookup._();

  static final MessageLookup instance = MessageLookup._();

  // ---- MMS by id / addresses / thread id resolution -------------

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
    return Mms.fromRaw(Map<String, dynamic>.from(response.records.first));
  }

  /// Lists every address row (sender + recipients) for a single MMS
  /// message. Queries `content://mms/{mmsId}/addr`.
  Future<List<MmsParticipant>> listMmsAddressesByMessage(int mmsId) async {
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        platformData: {'contentUri': 'content://mms/$mmsId/addr'},
      ),
    );
    return response.records
        .map((row) => MmsParticipant.fromRaw(Map<String, dynamic>.from(row)))
        .toList(growable: false);
  }

  /// Resolves (or lazily creates) the thread id for a given recipient
  /// set. Mirrors `Telephony.Threads.getOrCreateThreadId`. Returns
  /// null when [addresses] is empty or no row comes back.
  Future<int?> resolveThreadIdByAddresses(Iterable<String> addresses) async {
    // Dedupe in addition to trim/empty-filter — Telephony.Threads treats
    // a recipient set as a *set*, so passing the same address twice would
    // either no-op or (on some OEMs) cause a duplicate-recipient row to
    // be allocated, splitting threads that should converge.
    final cleaned = <String>{
      for (final raw in addresses)
        if (raw.trim().isNotEmpty) raw.trim(),
    }.toList(growable: false);
    if (cleaned.isEmpty) return null;
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
  /// Android stores conversation recipients as numeric IDs that map
  /// to canonical addresses via
  /// `content://mms-sms/canonical-address/`.
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
      final row = response.records.first;
      final address = (row['address'] ?? row['_id'])?.toString();
      debugPrint(
        '[diag][simple-sms] resolveCanonicalAddress recipientId=$recipientId '
        'records=${response.records.length} address=${_maskAddress(address)} '
        'cols=${row.keys.toList()}',
      );
      return (address != null && address.isNotEmpty) ? address : null;
    } catch (e, s) {
      // Per the file-level contract on lookup_service.dart: query
      // methods do NOT swallow exceptions. The previous
      // `catch + return null` here recreated the exact bug class
      // PR-C was opened to eliminate — silent empty across thousands
      // of full-sync calls, only surfacing when realtime force-
      // unwrapped a `!`. Log diagnostics for triage, then rethrow.
      debugPrint(
        'simple_sms: Failed to resolve canonical address $recipientId: $e',
      );
      debugPrint(s.toString());
      rethrow;
    }
  }

  // ---- SMS queries ---------------------------------------------

  /// Lists SMS messages matching the given [filter], ordered by
  /// [sort], paged by [limit] / [offset].
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
        sort: _buildSmsSort(sort ?? SmsSort.newestFirst), // ignore: silent_default

        page: (limit != null || offset != null)
            ? QueryPage(limit: limit, offset: offset)
            : null,
      ),
    );
    return response.records
        .map((row) => Sms.fromRaw(Map<String, dynamic>.from(row)))
        .toList(growable: false);
  }

  /// Fetches a single SMS by its database id. Returns null if not
  /// found.
  Future<Sms?> getSmsById(int id) async {
    final results = await listSms(filter: SmsFilter(ids: [id]));
    return results.isEmpty ? null : results.first;
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

  // ---- MMS queries ---------------------------------------------

  /// Lists MMS messages matching the given [filter], ordered by
  /// [sort], paged by [limit] / [offset]. Returned [Mms] instances
  /// have empty `recipients` and `parts` — fetch them via
  /// `LookupService.listMmsParts` / `listMmsAddressesByMessage`.
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
        sort: _buildMmsSort(sort ?? MmsSort.newestFirst), // ignore: silent_default

        page: (limit != null || offset != null)
            ? QueryPage(limit: limit, offset: offset)
            : null,
      ),
    );
    return response.records
        .map((row) => Mms.fromRaw(Map<String, dynamic>.from(row)))
        .toList(growable: false);
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

  // ---- Filter / sort translation -------------------------------

  /// Translate an [SmsFilter] to [QueryFilterCondition]s.
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
  /// Note: MMS stores `date` in **seconds** since epoch, not
  /// milliseconds, so the [DateTime] values are divided by 1000
  /// before being compared.
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
}
