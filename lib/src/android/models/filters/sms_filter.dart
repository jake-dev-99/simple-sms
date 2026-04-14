import '../enums/sms_mms_enums.dart';
import 'sort_direction.dart';

/// Criteria for narrowing an SMS listing query.
///
/// All fields are optional; omitted fields contribute no filter. Fields are
/// combined with AND. `ids` supplies an `_id IN (...)` match; `types` supplies
/// a `type IN (...)` match against [SmsMessageType.value].
///
/// ```dart
/// final filter = SmsFilter(
///   threadId: 42,
///   isRead: false,
///   dateFrom: DateTime.now().subtract(const Duration(days: 7)),
/// );
/// final unread = await LookupService().listSms(filter: filter);
/// ```
class SmsFilter {
  const SmsFilter({
    this.ids,
    this.threadId,
    this.isRead,
    this.types,
    this.dateFrom,
    this.dateTo,
    this.addressContains,
    this.subscriptionId,
  });

  /// Match any of these database ids (`_id IN (...)`).
  final List<int>? ids;

  /// Match only the given conversation thread (`thread_id = ?`).
  final int? threadId;

  /// Match only read (`true`) or unread (`false`) messages.
  final bool? isRead;

  /// Match any of these message types (`type IN (...)`).
  final List<SmsMessageType>? types;

  /// Match only messages with `date >= dateFrom.millisecondsSinceEpoch`.
  final DateTime? dateFrom;

  /// Match only messages with `date <= dateTo.millisecondsSinceEpoch`.
  final DateTime? dateTo;

  /// Substring match against the `address` column (phone number / email).
  final String? addressContains;

  /// Match only messages on a specific SIM subscription (`sub_id = ?`).
  final int? subscriptionId;

  SmsFilter copyWith({
    List<int>? ids,
    int? threadId,
    bool? isRead,
    List<SmsMessageType>? types,
    DateTime? dateFrom,
    DateTime? dateTo,
    String? addressContains,
    int? subscriptionId,
  }) =>
      SmsFilter(
        ids: ids ?? this.ids,
        threadId: threadId ?? this.threadId,
        isRead: isRead ?? this.isRead,
        types: types ?? this.types,
        dateFrom: dateFrom ?? this.dateFrom,
        dateTo: dateTo ?? this.dateTo,
        addressContains: addressContains ?? this.addressContains,
        subscriptionId: subscriptionId ?? this.subscriptionId,
      );

  @override
  String toString() => 'SmsFilter('
      'ids: $ids, threadId: $threadId, isRead: $isRead, types: $types, '
      'dateFrom: $dateFrom, dateTo: $dateTo, '
      'addressContains: $addressContains, subscriptionId: $subscriptionId)';
}

/// Sort column for SMS listings.
enum SmsSortField { id, date, threadId }

/// Ordering rule for an SMS listing.
class SmsSort {
  const SmsSort({
    this.field = SmsSortField.id,
    this.direction = SortDirection.descending,
  });

  final SmsSortField field;
  final SortDirection direction;

  /// Newest-first ordering by `date` (the common UI default).
  static const SmsSort newestFirst =
      SmsSort(field: SmsSortField.date, direction: SortDirection.descending);

  /// Oldest-first ordering by `date`.
  static const SmsSort oldestFirst =
      SmsSort(field: SmsSortField.date, direction: SortDirection.ascending);

  @override
  String toString() => 'SmsSort(field: $field, direction: $direction)';
}
