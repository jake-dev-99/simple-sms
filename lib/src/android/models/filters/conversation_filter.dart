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
    this.threadIds,
    this.isArchived,
    this.hasUnread,
    this.dateFrom,
    this.dateTo,
    this.hasAttachment,
    this.threadIdAfter,
  });

  /// Match any of these thread ids (`thread_id IN (...)`).
  ///
  /// On Samsung's `content://mms-sms/conversations?simple=true` view the
  /// row primary key `_id` is the **latest-message id** in the thread,
  /// not the thread id — so filtering on `_id` matches message ids and
  /// is almost always wrong. Filter on `thread_id` instead; that's the
  /// stable join key used everywhere else ([SmsFilter.threadId],
  /// [MmsFilter.threadId], `AndroidSimpleConversation.threadId`).
  final List<int>? threadIds;

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

  /// Match only rows where `thread_id > threadIdAfter` — cursor-based
  /// pagination anchor. See [SmsFilter.idAfter] for rationale. Pair with
  /// [ConversationSortField.id] so "after this thread id" is
  /// well-defined.
  final int? threadIdAfter;

  ConversationFilter copyWith({
    List<int>? threadIds,
    bool? isArchived,
    bool? hasUnread,
    DateTime? dateFrom,
    DateTime? dateTo,
    bool? hasAttachment,
    int? threadIdAfter,
  }) => ConversationFilter(
    threadIds: threadIds ?? this.threadIds,
    isArchived: isArchived ?? this.isArchived,
    hasUnread: hasUnread ?? this.hasUnread,
    dateFrom: dateFrom ?? this.dateFrom,
    dateTo: dateTo ?? this.dateTo,
    hasAttachment: hasAttachment ?? this.hasAttachment,
    threadIdAfter: threadIdAfter ?? this.threadIdAfter,
  );

  @override
  String toString() =>
      'ConversationFilter('
      'threadIds: $threadIds, isArchived: $isArchived, hasUnread: $hasUnread, '
      'dateFrom: $dateFrom, dateTo: $dateTo, hasAttachment: $hasAttachment, '
      'threadIdAfter: $threadIdAfter)';
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
