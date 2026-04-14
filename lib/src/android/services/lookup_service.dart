import 'package:flutter/foundation.dart';
import 'package:simple_query/simple_query.dart';

import '../models/messages/mms.dart';
import '../models/messages/sms.dart';
import '../models/people/contact.dart';
import '../models/people/contactables.dart';

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
class LookupService {
  /// Looks up a contact by their database ID.
  ///
  /// Returns null if the contact is not found or if the query fails.
  Future<AndroidContact?> lookupContactById(int contactId) async {
    try {
      final response = await SimpleQuery.instance.query(
        QueryRequest(
          domain: QueryDomain.contacts,
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
      final uri = isEmail
          ? 'content://com.android.contacts/data/emails/filter/$address'
          : 'content://com.android.contacts/data/phones/filter/$address';
      final response = await SimpleQuery.instance.query(
        QueryRequest(
          domain: QueryDomain.contacts,
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
          domain: QueryDomain.messages,
          entityType: 'mms',
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

  /// Resolves a canonical address (phone number) from a recipient ID.
  ///
  /// Android stores conversation recipients as numeric IDs that map to
  /// canonical addresses via the `content://mms-sms/canonical-address/` URI.
  Future<String?> resolveCanonicalAddress(String recipientId) async {
    try {
      final response = await SimpleQuery.instance.query(
        QueryRequest(
          domain: QueryDomain.messages,
          platformData: {
            'contentUri':
                'content://mms-sms/canonical-address/$recipientId',
          },
        ),
      );
      if (response.records.isEmpty) return null;
      final address = response.records.first['address']?.toString();
      return (address != null && address.isNotEmpty) ? address : null;
    } catch (e, s) {
      debugPrint(
          'simple_sms: Failed to resolve canonical address $recipientId: $e');
      debugPrint(s.toString());
      return null;
    }
  }

  /// Gets all SMS messages in a conversation thread.
  Future<List<Sms>> getSmsByThread(int threadId) async {
    try {
      final response = await SimpleQuery.instance.query(
        QueryRequest(
          domain: QueryDomain.messages,
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

  /// Gets all MMS messages in a conversation thread.
  Future<List<Mms>> getMmsByThread(int threadId) async {
    try {
      final response = await SimpleQuery.instance.query(
        QueryRequest(
          domain: QueryDomain.messages,
          entityType: 'mms',
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
}
