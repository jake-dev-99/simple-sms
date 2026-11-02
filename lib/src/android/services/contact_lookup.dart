import 'dart:developer' as developer;

import 'package:simple_query/simple_query.dart';

import '../models/filters/contact_filter.dart';
import '../models/filters/sort_direction.dart';
import '../models/model_helpers.dart';
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
/// **Testing.** Through the `SimpleQueryPlatform` seam documented at
/// [AttachmentExtractor]; no `SimpleQuery` injection needed.

// File-private content URIs. Single-site source of truth so future
// AOSP provider URI changes are one edit.
const String _contactsUri = 'content://com.android.contacts/contacts';
const String _contactsDataUri = 'content://com.android.contacts/data';

/// The `dart:developer` log level for a warning (`package:logging`'s WARNING).
const int _warningLevel = 900;
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
  ///
  /// The address is percent-encoded as a single path segment, as AOSP does
  /// with `Uri.encode`, so `+`, `#`, `/`, `%`, or spaces cannot split or
  /// truncate the URI; the provider reads it back decoded. Surrounding
  /// whitespace is trimmed first, so it never becomes part of the match. A
  /// blank
  /// (empty or whitespace-only) address identifies no contact, so it is
  /// answered with null without a query; an empty one would also drop the
  /// filter segment from the URI.
  Future<Contactable?> lookupContactableByAddress(String address) async {
    final trimmed = address.trim();
    if (trimmed.isEmpty) return null;
    final isEmail = trimmed.contains('@');
    final segment = Uri.encodeComponent(trimmed);
    final uri = isEmail
        ? '$_contactsEmailFilterUri/$segment'
        : '$_contactsPhoneFilterUri/$segment';
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

  /// Purpose: resolve the structured name (given / family / prefix / suffix /
  /// phonetic variants) that Android uses for a contact's display name.
  ///
  /// A merged contact has one raw contact per account, and their name rows
  /// can differ. Android documents `Contacts.NAME_RAW_CONTACT_ID` as the raw
  /// contact that contributes the display name, so this reads only that raw
  /// contact's row (UNFY-394). Android can re-merge contacts after the caller
  /// read [nameRawContactId]; when that pair matches no row, this re-reads the
  /// contact's current `name_raw_contact_id` once and, if it changed, retries.
  ///
  /// When the raw contact has several name rows, the single row marked
  /// `is_primary` (documented: "if set, non-0 means true") wins; with none or
  /// several marked it logs a warning and returns null rather than guess.
  ///
  /// @param contactId the aggregate contact's `_id`.
  /// @param nameRawContactId the contact's `name_raw_contact_id`.
  /// @returns the display-name raw contact's structured name, or null when
  ///   it has no name row or its rows are ambiguous.
  /// @throws whatever [SimpleQuery.query] throws on a provider failure.
  Future<AndroidContactName?> getStructuredName({
    required int contactId,
    required int nameRawContactId,
  }) async {
    var rawContactId = nameRawContactId;
    var rows = await _nameRows(contactId, rawContactId);
    if (rows.isEmpty) {
      final current = (await lookupContactById(contactId))?.nameRawContactId;
      if (current == null || current == nameRawContactId) return null;
      rawContactId = current;
      rows = await _nameRows(contactId, rawContactId);
      if (rows.isEmpty) return null;
    }
    if (rows.length == 1) {
      return AndroidContactName.fromRaw(Map<String, dynamic>.from(rows.single));
    }
    final primary = rows
        .where((row) => (FieldHelper.asInt(row['is_primary']) ?? 0) != 0)
        .toList(growable: false);
    if (primary.length == 1) {
      return AndroidContactName.fromRaw(
        Map<String, dynamic>.from(primary.single),
      );
    }
    developer.log(
      'ambiguous structured name; returning none '
      'contact_id=$contactId raw_contact_id=$rawContactId '
      'name_rows=${rows.length} primary_rows=${primary.length} issue=UNFY-394',
      name: 'simple_sms.contact_lookup',
      level: _warningLevel,
    );
    return null;
  }

  /// Purpose: read the StructuredName rows of one raw contact.
  ///
  /// @param contactId the aggregate contact's `_id`.
  /// @param rawContactId the raw contact whose name rows to read.
  /// @returns the matching `vnd.android.cursor.item/name` data rows.
  /// @throws whatever [SimpleQuery.query] throws on a provider failure.
  Future<List<QueryRecord>> _nameRows(int contactId, int rawContactId) async {
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        filters: [
          QueryFilterCondition(
            field: 'contact_id',
            operator: QueryFilterOperator.equals,
            value: contactId.toString(),
          ),
          QueryFilterCondition(
            field: 'raw_contact_id',
            operator: QueryFilterOperator.equals,
            value: rawContactId.toString(),
          ),
          QueryFilterCondition(
            field: 'mimetype',
            operator: QueryFilterOperator.equals,
            value: 'vnd.android.cursor.item/name',
          ),
        ],
        platformData: const {'contentUri': _contactsDataUri},
      ),
    );
    return response.records;
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
        sort: _buildContactSort(sort ?? ContactSort.alphabetical), // ignore: silent_default

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
