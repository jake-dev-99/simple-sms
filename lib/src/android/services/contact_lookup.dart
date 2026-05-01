import 'package:simple_query/simple_query.dart';

import '../models/filters/contact_filter.dart';
import '../models/filters/sort_direction.dart';
import '../models/people/contact.dart';
import '../models/people/contact_name.dart';
import '../models/people/contactables.dart';

/// Contacts-side queries against the Android contacts provider.
///
/// **Tier 0f extraction (audit Step 0f).** Originally lived inside
/// [LookupService] alongside the message / conversation / attachment
/// query methods. Split out so per-domain modules localise change-
/// blast radius.
///
/// LookupService keeps its public API for backward-compat — every
/// method on this class is reached via [LookupService] delegation.
///
/// **Future work — `SimpleQuery` injection.** Documented at
/// [AttachmentExtractor]: deferred until a plugin-wide DI pass
/// flips every `SimpleQuery.instance` callsite at once.

// File-private content URIs. Single-site source of truth so future
// AOSP provider URI changes are one edit.
const String _contactsUri = 'content://com.android.contacts/contacts';
const String _contactsDataUri = 'content://com.android.contacts/data';
const String _contactsPhoneFilterUri =
    'content://com.android.contacts/data/phones/filter';
const String _contactsEmailFilterUri =
    'content://com.android.contacts/data/emails/filter';

class ContactLookup {
  ContactLookup._();

  static final ContactLookup instance = ContactLookup._();

  /// Looks up a contact by their database ID.
  ///
  /// Returns null if the contact is not found or the row is empty.
  /// Uses `platformSpecific` so simple_query doesn't canonicalize
  /// the row into its domain schema (id/displayName/…) — we need
  /// raw Android columns (_id, display_name, …) to feed
  /// [AndroidContact.fromRaw].
  Future<AndroidContact?> lookupContactById(int contactId) async {
    final response = await SimpleQuery.instance.query(
      QueryRequest(
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

  /// Looks up a contactable (lightweight contact info) by phone number
  /// or email. Uses the Android contacts filter URI to match the
  /// address against phone numbers or email addresses.
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

  /// Lists every `data` row belonging to a single contact — phone
  /// numbers, emails, and any other MIME-typed data entries. Queries
  /// `content://com.android.contacts/data` filtered by `contact_id`.
  ///
  /// Callers typically filter the returned list on
  /// `contactable.mimetype` (e.g.
  /// `vnd.android.cursor.item/phone_v2`) to pick the entries they
  /// care about.
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

  /// Resolves a contact's structured name (given / family / prefix /
  /// suffix / phonetic variants) from the contacts data provider.
  ///
  /// Queries `content://com.android.contacts/data` filtered by
  /// `contact_id`, `mimetype = vnd.android.cursor.item/name`, and
  /// optionally `account_type`. Returns null when the contact has no
  /// structured-name row.
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

  /// Lists contacts matching the given [filter], ordered by [sort],
  /// paged by [limit] / [offset].
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
        // ignore: silent_default
        sort: _buildContactSort(sort ?? ContactSort.alphabetical),
        page: (limit != null || offset != null)
            ? QueryPage(limit: limit, offset: offset)
            : null,
      ),
    );
    return response.records
        .map((row) => AndroidContact.fromRaw(Map<String, dynamic>.from(row)))
        .toList(growable: false);
  }

  // --- Private filter / sort translation -------------------------------

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

  List<QuerySort> _buildContactSort(ContactSort sort) {
    final column = switch (sort.field) {
      ContactSortField.displayName => 'display_name',
      ContactSortField.id => '_id',
      ContactSortField.lastTimeContacted => 'last_time_contacted',
    };
    return [
      QuerySort(
        field: column,
        direction: sort.direction == SortDirection.ascending
            ? QuerySortDirection.ascending
            : QuerySortDirection.descending,
      ),
    ];
  }
}
