import 'sort_direction.dart';

/// Criteria for narrowing a conversation listing query against
/// `content://mms-sms/conversations`.
///
/// All fields are optional; omitted fields contribute no filter. Fields are
/// combined with AND. Note that many of the columns on the Android
/// conversations view are integer flags (0/1), which are encoded here as
/// `bool` for readability.
class ConversationFilter {
  const ConversationFilter({
    this.ids,
    this.isArchived,
    this.hasUnread,
    this.dateFrom,
    this.dateTo,
    this.hasAttachment,
    this.idAfter,
  });

  /// Match any of these thread ids (`_id IN (...)`).
  final List<int>? ids;

  /// Match only archived (`true`) or un-archived (`false`) threads.
  final bool? isArchived;

  /// Match only threads with unread messages (`read = 0`).
  final bool? hasUnread;

  /// Match only threads last updated on/after this timestamp.
  final DateTime? dateFrom;

  /// Match only threads last updated on/before this timestamp.
  final DateTime? dateTo;

  /// Match only threads whose most recent message has an attachment.
  final bool? hasAttachment;

  /// Match only rows where `_id > idAfter` — cursor-based pagination
  /// anchor. See [SmsFilter.idAfter] for rationale. Pair with
  /// [ConversationSortField.id] so "after this id" is well-defined.
  final int? idAfter;

  ConversationFilter copyWith({
    List<int>? ids,
    bool? isArchived,
    bool? hasUnread,
    DateTime? dateFrom,
    DateTime? dateTo,
    bool? hasAttachment,
    int? idAfter,
  }) => ConversationFilter(
    ids: ids ?? this.ids,
    isArchived: isArchived ?? this.isArchived,
    hasUnread: hasUnread ?? this.hasUnread,
    dateFrom: dateFrom ?? this.dateFrom,
    dateTo: dateTo ?? this.dateTo,
    hasAttachment: hasAttachment ?? this.hasAttachment,
    idAfter: idAfter ?? this.idAfter,
  );

  @override
  String toString() =>
      'ConversationFilter('
      'ids: $ids, isArchived: $isArchived, hasUnread: $hasUnread, '
      'dateFrom: $dateFrom, dateTo: $dateTo, hasAttachment: $hasAttachment, '
      'idAfter: $idAfter)';
}

/// Sort column for conversation listings.
enum ConversationSortField { date, id }

/// Ordering rule for a conversation listing.
class ConversationSort {
  const ConversationSort({
    this.field = ConversationSortField.date,
    this.direction = SortDirection.descending,
  });

  final ConversationSortField field;
  final SortDirection direction;

  /// Most-recently-updated thread first — the common inbox default.
  static const ConversationSort mostRecent = ConversationSort(
    field: ConversationSortField.date,
    direction: SortDirection.descending,
  );

  @override
  String toString() => 'ConversationSort(field: $field, direction: $direction)';
}
