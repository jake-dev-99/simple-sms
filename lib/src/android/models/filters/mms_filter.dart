import '../enums/sms_mms_enums.dart';
import 'sort_direction.dart';

/// Criteria for narrowing an MMS listing query.
///
/// All fields are optional; omitted fields contribute no filter. Fields are
/// combined with AND. The MMS table's type column is `m_type` on Android;
/// values come from [MmsMessageType.value].
class MmsFilter {
  const MmsFilter({
    this.ids,
    this.threadId,
    this.isRead,
    this.types,
    this.dateFrom,
    this.dateTo,
    this.subscriptionId,
    this.idAfter,
  });

  /// Match any of these database ids (`_id IN (...)`).
  final List<int>? ids;

  /// Match only the given conversation thread (`thread_id = ?`).
  final int? threadId;

  /// Match only read (`true`) or unread (`false`) messages.
  final bool? isRead;

  /// Match any of these message types (`m_type IN (...)`).
  final List<MmsMessageType>? types;

  /// Match only messages with `date >= dateFrom.millisecondsSinceEpoch / 1000`
  /// (MMS stores `date` in seconds, not milliseconds).
  final DateTime? dateFrom;

  /// Match only messages with `date <= dateTo.millisecondsSinceEpoch / 1000`.
  final DateTime? dateTo;

  /// Match only messages on a specific SIM subscription (`sub_id = ?`).
  final int? subscriptionId;

  /// Match only rows where `_id > idAfter` — cursor-based pagination
  /// anchor. See [SmsFilter.idAfter] for rationale. Pair with
  /// [MmsSortField.id] ordering so "after this id" is well-defined.
  final int? idAfter;

  MmsFilter copyWith({
    List<int>? ids,
    int? threadId,
    bool? isRead,
    List<MmsMessageType>? types,
    DateTime? dateFrom,
    DateTime? dateTo,
    int? subscriptionId,
    int? idAfter,
  }) => MmsFilter(
    ids: ids ?? this.ids,
    threadId: threadId ?? this.threadId,
    isRead: isRead ?? this.isRead,
    types: types ?? this.types,
    dateFrom: dateFrom ?? this.dateFrom,
    dateTo: dateTo ?? this.dateTo,
    subscriptionId: subscriptionId ?? this.subscriptionId,
    idAfter: idAfter ?? this.idAfter,
  );

  @override
  String toString() =>
      'MmsFilter('
      'ids: $ids, threadId: $threadId, isRead: $isRead, types: $types, '
      'dateFrom: $dateFrom, dateTo: $dateTo, subscriptionId: $subscriptionId, '
      'idAfter: $idAfter)';
}

/// Sort column for MMS listings.
enum MmsSortField { id, date, threadId }

/// Ordering rule for an MMS listing.
class MmsSort {
  const MmsSort({
    this.field = MmsSortField.id,
    this.direction = SortDirection.descending,
  });

  final MmsSortField field;
  final SortDirection direction;

  /// Newest-first ordering by `date`.
  static const MmsSort newestFirst = MmsSort(
    field: MmsSortField.date,
    direction: SortDirection.descending,
  );

  /// Oldest-first ordering by `date`.
  static const MmsSort oldestFirst = MmsSort(
    field: MmsSortField.date,
    direction: SortDirection.ascending,
  );

  @override
  String toString() => 'MmsSort(field: $field, direction: $direction)';
}

/// Filter for listing parts (attachments + body) of an MMS message.
class MmsPartFilter {
  const MmsPartFilter({this.contentTypeContains});

  /// Match only parts whose `ct` column contains this substring
  /// (e.g. `image/` for all image attachments).
  ///
  /// The underlying query operator is `CONTAINS`, not `LIKE 'x%'` — Samsung
  /// ContentProvider MIME values are well-formed (`image/jpeg`,
  /// `application/smil`, `text/plain`, …) so in practice the two coincide,
  /// but callers passing a substring that could appear mid-value (e.g.
  /// `'text'` will match both `text/plain` AND `text/x-vCard`) should know
  /// the match is anchored anywhere in the string.
  final String? contentTypeContains;

  MmsPartFilter copyWith({String? contentTypeContains}) => MmsPartFilter(
    contentTypeContains: contentTypeContains ?? this.contentTypeContains,
  );

  @override
  String toString() =>
      'MmsPartFilter(contentTypeContains: $contentTypeContains)';
}
