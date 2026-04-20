// Golden tests for `fromRaw` parsers, driven by representative rows
// captured from a real Samsung Android 16 device via the internal
// `prospector` tool (see `~/Downloads/prospector_export_2026_4_19_*`).
//
// The purpose of these tests is to lock the parser behaviour against
// the shape the device actually returns — including OEM-specific
// columns, sentinel values, and stringified numerics — so that future
// Android-version bumps or refactors produce reviewable diffs rather
// than silent regressions.
//
// Each fixture is a single row copied verbatim from the corresponding
// prospector export JSON. Multiple rows per table aren't necessary —
// a single representative row exercises the same column set.

import 'package:flutter_test/flutter_test.dart';

import 'package:simple_sms_native/src/android/models/messages/mms.dart';
import 'package:simple_sms_native/src/android/models/messages/mms_part.dart';
import 'package:simple_sms_native/src/android/models/messages/sms.dart';
import 'package:simple_sms_native/src/android/models/model_helpers.dart';
import 'package:simple_sms_native/src/android/models/people/mms_participant.dart';

void main() {
  group('Mms.fromRaw golden (Samsung Android 16)', () {
    // Lifted verbatim from prospector_export_2026_4_19_20_20_17/mms.json
    // (first row). Notable traits of this fixture that broke older
    // parser paths:
    //   * `d_tm`, `st`, `retr_st`, `retr_txt` are empty strings "" on
    //     Samsung when unset, not null or 0 — asInt must parse "" → null
    //     (not throw); asDateTime must parse "" → null (not throw).
    //   * `exp: 604800` is a relative expiry-in-seconds (7 days), not
    //     an absolute timestamp. asDateTime will happily parse it as
    //     seconds-since-epoch ≈ 1977; not meaningful but shouldn't
    //     crash.
    //   * Samsung-extension columns present: `block_filtered_status`,
    //     `predefined_id`, `spam_type`, `sub_cs`, `device_name`,
    //     `re_count_info_custom_reaction`.
    //   * `m_cls: "personal"` — Samsung sends the enum NAME as a
    //     String where WAP-MMS spec defines it as a byte code. Parser
    //     should tolerate (currently maps to null; documented follow-up).
    final samsungOutboundRow = <String, dynamic>{
      'date': 1776546259,
      'spam_report': 0,
      'predefined_id': -1,
      'ct_t': 'application/vnd.wap.multipart.related',
      'msg_box': 2,
      'thread_id': 3,
      'sub_cs': '',
      're_type': 0,
      'retr_st': '',
      're_original_body': '',
      'd_tm': '',
      'exp': 604800,
      'locked': 0,
      'msg_id': 0,
      'app_id': 0,
      'from_address': '',
      'm_id': 'MxpE2SVJ2RRSuo4qMnKYaPpA',
      'spam_type': 0,
      'retr_txt': '',
      'date_sent': 0,
      'read': 1,
      'rpt_a': '',
      'ct_cls': 135,
      'bin_info': 0,
      'pri': 129,
      'sub_id': 2,
      're_content_type': '',
      'object_id': '',
      'resp_txt': '',
      're_content_uri': '',
      'ct_l': '',
      're_original_key': '',
      'd_rpt': 129,
      'reserved': 0,
      'using_mode': 0,
      '_id': 6244,
      'rr_st': 0,
      'm_type': 128,
      'favorite': 0,
      'rr': 129,
      'sub': '',
      'hidden': 0,
      'deletable': 0,
      'read_status': '',
      'd_rpt_st': 0,
      'callback_set': 0,
      're_count_info_custom_reaction': '',
      'seen': 1,
      're_recipient_address': '',
      'device_name': '',
      'cmc_prop': '',
      'resp_st': '',
      'text_only': 1,
      'sim_slot': 1,
      'st': '',
      'retr_txt_cs': '',
      'creator': 'com.google.android.apps.messaging',
      'm_size': 4,
      'sim_imsi': '',
      'block_filtered_status': '',
      'correlation_tag': '',
      're_body': '',
      'safe_message': 0,
      'tr_id': 'proto:sample',
      'm_cls': 'personal',
      'v': 18,
      'secret_mode': 0,
      're_file_name': '',
      're_count_info': '',
    };

    test('parses core fields without throwing on Samsung OEM shape',
        () async {
      final mms = await Mms.fromRaw(samsungOutboundRow);

      expect(mms.id, 6244);
      expect(mms.threadId, 3);
      expect(mms.subscriptionId, 2);
      expect(mms.simSlot, 1);
      expect(mms.read, isTrue);
      expect(mms.seen, isTrue);
      expect(mms.textOnly, isTrue);
      expect(mms.creator, 'com.google.android.apps.messaging');
      expect(mms.messageSize, 4);
      expect(mms.version, 18);
    });

    test('empty-string sentinels coerce to null, not throw', () async {
      final mms = await Mms.fromRaw(samsungOutboundRow);
      // `d_tm`, `retr_st`, `retr_txt_cs`, `st`, `read_status`, `sub_cs`
      // all arrive as "" on Samsung. Previously these either threw or
      // silently became a malformed value; now they should read as null.
      expect(mms.deliveryDate, isNull);
      expect(mms.retrieveStatus, isNull);
      expect(mms.retrievedTextCharset, isNull);
      expect(mms.status, isNull);
      expect(mms.readStatus, isNull);
      expect(mms.subCs, isNull);
    });

    test('Samsung pass-through fields land in their nullable slots',
        () async {
      final mms = await Mms.fromRaw(samsungOutboundRow);
      expect(mms.predefinedId, -1);
      expect(mms.spamType, 0);
      expect(mms.blockFilteredStatus, isNull); // "" → null
      expect(mms.reCountInfoCustomReaction, '');
      expect(mms.deviceName, '');
    });
  });

  group('MmsPart.fromRaw golden', () {
    // Lifted from the user's mms_part.csv export (text part for the
    // message "On my way home babe ").
    final textPartRow = <String, dynamic>{
      '_data': '',
      '_id': 1139,
      'cd': '',
      'chset': 106,
      'cid': '<text000001>',
      'cl': 'text000001.txt',
      'coupon_data': '',
      'coupon_status': 0,
      'ct': 'text/plain',
      'ctt_s': '',
      'ctt_t': '',
      'decorate_bubble_value': '',
      'fn': '',
      'mid': 764,
      'name': '',
      'sef_type': 0,
      'seq': 0,
      'smart_suggestion_classification': 0,
      'sub_id': -1,
      // Synthetic body; original fixture carried a real user message.
      // Keep the trailing space to exercise the "body with trailing
      // whitespace" path of the text-part parser.
      'text': 'Synthetic golden body — do not redact further. ',
    };

    test('reads _id (not raw["id"]) and _data (not raw["dataLocation"])',
        () {
      final part = MmsPart.fromRaw(textPartRow);
      expect(part.id, 1139);
      // `_data` is empty for this text part; that's expected. The
      // important invariant is that the parser read from the right
      // column name and didn't crash on an empty string.
      expect(part.dataLocation, isNull);
      expect(part.text, 'Synthetic golden body — do not redact further. ');
    });

    test('messageId, sequence, sefType coerce via FieldHelper.asInt', () {
      final part = MmsPart.fromRaw(textPartRow);
      expect(part.messageId, 764);
      expect(part.sequence, 0);
      expect(part.sefType, 0);
    });

    test('parentId mirrors `mid` so downstream consumers can link to MMS',
        () {
      // `mid` on content://mms/part rows is the owning MMS `_id`.
      // `parentId` is the generic String foreign-key the cross-model
      // shape exposes (Contactable / Conversation use the same field).
      // simple-messages' android_converters reads `parentId` on parts
      // during the MMS → Unified attachments flow; an empty parentId
      // meant attachments detached from their MMS in cached syncs.
      final part = MmsPart.fromRaw(textPartRow);
      expect(part.parentId, '764');
      // Round-trip: parentId → toRaw["mid"] → fromRaw parses back.
      final roundTripped = MmsPart.fromRaw(part.toRaw());
      expect(roundTripped.parentId, '764');
      expect(roundTripped.messageId, 764);
    });

    test('Samsung pass-through fields populate', () {
      final part = MmsPart.fromRaw(textPartRow);
      expect(part.subId, -1);
      expect(part.couponStatus, 0);
      expect(part.smartSuggestionClassification, 0);
    });
  });

  group('Sms.fromRaw golden (Samsung Android 16)', () {
    // Lifted from sms.json (first row — a bank balance notification).
    final bankSmsRow = <String, dynamic>{
      'date': 1776603767108,
      'teleservice_id': 0,
      'spam_report': 0,
      'subject': 'proto:CjoKImNvbS5nb29nbGUuYW5kcm9pZC5hcHBzLm1lc3NhZ2luZy4',
      'predefined_id': -1,
      'svc_cmd_content': '',
      'reply_path_present': 0,
      'group_type': '',
      'type': 1,
      // Synthetic body; original fixture carried real financial SMS
      // content. Preserves the parser-relevant shape: long text with an
      // escaped dollar sign, digits with thousands separators, and a
      // single-period end-of-sentence so we still exercise body length
      // + escape-sequence handling.
      'body':
          'Synthetic balance notice for account x0000-S00 FAKE ACCOUNT '
          'today is \$12,345.67. Thank you for banking with us.',
      'thread_id': 173,
      'protocol': 0,
      're_type': 0,
      're_original_body': '',
      'link_url': '',
      'locked': 0,
      'msg_id': 0,
      'app_id': 0,
      'roam_pending': 0,
      'from_address': '',
      'spam_type': 0,
      'date_sent': 1776603764000,
      'read': 0,
      'bin_info': 0,
      'group_cotag': '',
      'sub_id': 2,
      'pri': 0,
      're_content_type': '',
      'object_id': '',
      're_content_uri': '',
      'delivery_date': '',
      'svc_cmd': 0,
      're_original_key': '',
      'reserved': 0,
      'person': '',
      'using_mode': 0,
      'error_code': -1,
      'd_rpt_cnt': 0,
      '_id': 1215,
      'favorite': 0,
      'status': -1,
      'hidden': 0,
      'deletable': 0,
      'announcements_scenario_id': '',
      're_count_info_custom_reaction': '',
      'seen': 1,
      'announcements_subtype': 0,
      're_recipient_address': '',
      'device_name': '',
      'cmc_prop': '',
      'decorate_bubble_value': '',
      'callback_number': '',
      'is_satellite': 0,
      'sim_slot': 1,
      'creator': 'com.google.android.apps.messaging',
      'address': '695628',
      'sim_imsi': '',
      'block_filtered_status': '',
    };

    test('parses id, threadId, subscriptionId, simSlot as ints', () {
      final sms = Sms.fromRaw(bankSmsRow);
      expect(sms.id, 1215);
      expect(sms.threadId, 173);
      expect(sms.subscriptionId, 2);
      expect(sms.simSlot, 1);
    });

    test('date is a real DateTime even with big millis value', () {
      final sms = Sms.fromRaw(bankSmsRow);
      expect(sms.date.millisecondsSinceEpoch, 1776603767108);
    });

    test('Samsung pass-through fields populate nullable slots', () {
      final sms = Sms.fromRaw(bankSmsRow);
      expect(sms.isSatellite, false); // 0 → false
      expect(sms.blockFilteredStatus, isNull); // "" → null
      expect(sms.spamType, 0);
    });
  });

  group('MmsParticipant.fromRaw golden', () {
    // Shape lifted from mms_addr.json — a sender row (type 137 / 0x89).
    // The address is a fabricated number from the 555-prefix reserved
    // range (NANP assigns 555-0100..555-0199 for fictional use); the
    // `+` prefix is preserved so we still exercise the E.164 parsing
    // path without committing a real phone number to the repo.
    final senderRow = <String, dynamic>{
      'charset': 106,
      'address': '+15550100123',
      'sub_id': -1,
      '_id': 5,
      'msg_id': 3,
      'contact_id': '',
      'type': 137,
    };

    test('id + msgId + address all coerce cleanly', () {
      final p = MmsParticipant.fromRaw(senderRow);
      expect(p.id, 5);
      expect(p.msgId, 3);
      expect(p.address, '+15550100123');
      expect(p.subId, -1);
      expect(p.contactId, isNull); // "" → null via asInt
    });
  });

  group('FieldHelper.asDateTime sentinel guard', () {
    test('Long.MAX_VALUE in an int field returns null (not throw)', () {
      // 9223372036854775807 is the Samsung "unset" sentinel for `exp`
      // and `d_tm` on some builds. Prior to this guard, the parser
      // would throw RangeError (millisecondsSinceEpoch) here.
      expect(FieldHelper.asDateTime(9223372036854775807), isNull);
    });

    test('normal epoch millis still parse correctly', () {
      final d = FieldHelper.asDateTime(1776603767108);
      expect(d, isNotNull);
      expect(d!.millisecondsSinceEpoch, 1776603767108);
    });

    test('epoch seconds are promoted to millis', () {
      // Values below _secondsVsMillisThreshold are treated as seconds.
      final d = FieldHelper.asDateTime(1776546259);
      expect(d, isNotNull);
      expect(d!.millisecondsSinceEpoch, 1776546259 * 1000);
    });

    test('empty string returns null', () {
      expect(FieldHelper.asDateTime(''), isNull);
    });
  });

  group('FieldHelper.asInt OEM quirk handling', () {
    test('numeric Strings parse (Samsung/OEM stringified columns)', () {
      expect(FieldHelper.asInt('42'), 42);
      expect(FieldHelper.asInt(' 42 '), 42);
    });

    test('empty String returns null (not throw)', () {
      expect(FieldHelper.asInt(''), isNull);
    });

    test('non-numeric Strings return null', () {
      expect(FieldHelper.asInt('not a number'), isNull);
    });
  });
}
