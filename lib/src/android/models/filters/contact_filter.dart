import 'sort_direction.dart';

/// Criteria for narrowing a contact listing query.
///
/// All fields are optional; omitted fields contribute no filter. Fields are
/// combined with AND. String matches use a case-insensitive substring match
/// against the underlying Android columns.
class ContactFilter {
  const ContactFilter({
    this.ids,
    this.displayNameContains,
    this.hasPhoneNumber,
    this.hasEmail,
    this.inVisibleGroup,
    this.idAfter,
  });

  /// Match any of these database ids (`_id IN (...)`).
  final List<int>? ids;

  /// Substring match against `display_name`.
  final String? displayNameContains;

  /// Match only contacts with at least one phone number (`has_phone_number = 1`).
  final bool? hasPhoneNumber;

  /// Match only contacts with at least one email (`has_email = 1`).
  final bool? hasEmail;

  /// Match only contacts in the visible-contacts group (`in_visible_group = 1`).
  final bool? inVisibleGroup;

  /// Match only rows where `_id > idAfter` — cursor-based pagination
  /// anchor. See [SmsFilter.idAfter] for rationale. Pair with
  /// [ContactSortField.id] so "after this id" is well-defined.
  final int? idAfter;

  ContactFilter copyWith({
    List<int>? ids,
    String? displayNameContains,
    bool? hasPhoneNumber,
    bool? hasEmail,
    bool? inVisibleGroup,
    int? idAfter,
  }) => ContactFilter(
    ids: ids ?? this.ids,
    displayNameContains: displayNameContains ?? this.displayNameContains,
    hasPhoneNumber: hasPhoneNumber ?? this.hasPhoneNumber,
    hasEmail: hasEmail ?? this.hasEmail,
    inVisibleGroup: inVisibleGroup ?? this.inVisibleGroup,
    idAfter: idAfter ?? this.idAfter,
  );

  @override
  String toString() =>
      'ContactFilter('
      'ids: $ids, displayNameContains: $displayNameContains, '
      'hasPhoneNumber: $hasPhoneNumber, hasEmail: $hasEmail, '
      'inVisibleGroup: $inVisibleGroup, idAfter: $idAfter)';
}

/// Sort column for contact listings.
enum ContactSortField { displayName, id, lastTimeContacted }

/// Ordering rule for a contact listing.
class ContactSort {
  const ContactSort({
    this.field = ContactSortField.displayName,
    this.direction = SortDirection.ascending,
  });

  final ContactSortField field;
  final SortDirection direction;

  /// Alphabetical (A → Z) by display name — the common address-book default.
  static const ContactSort alphabetical = ContactSort(
    field: ContactSortField.displayName,
    direction: SortDirection.ascending,
  );

  /// Most-recently contacted first.
  static const ContactSort recentlyContacted = ContactSort(
    field: ContactSortField.lastTimeContacted,
    direction: SortDirection.descending,
  );

  @override
  String toString() => 'ContactSort(field: $field, direction: $direction)';
}
