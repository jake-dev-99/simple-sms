import 'package:flutter_test/flutter_test.dart';
import 'package:simple_sms_native/android.dart';

void main() {
  test('thread page excludes the cursor boundary before MMS hydration',
      () async {
    final boundary = DateTime.utc(2026, 8, 1, 12);
    final lookup = _ThreadPageLookup(
      sms: [
        _sms(id: 3, sentAt: boundary),
        _sms(id: 2, sentAt: boundary.subtract(const Duration(hours: 1))),
      ],
      mms: [
        _mms(id: 9, sentAt: boundary),
        _mms(id: 8, sentAt: boundary.subtract(const Duration(hours: 2))),
      ],
    );

    final page = await lookup.getNormalizedThreadPage(
      7,
      limit: 3,
      before: boundary,
    );

    expect(page.map((message) => (message.channel, message.id)), [
      (SmsMmsType.sms, 2),
      (SmsMmsType.mms, 8),
    ]);
    expect(
      lookup.smsFilter?.dateTo,
      boundary.subtract(const Duration(milliseconds: 1)),
      reason: 'the provider gets an exact exclusive cut via its inclusive API',
    );
    expect(
      lookup.mmsFilter?.dateTo,
      boundary.subtract(const Duration(milliseconds: 1)),
      reason: 'the MMS seconds filter receives the same exact exclusive cut',
    );
    expect(lookup.mmsFilter?.types,
        containsAll(<MmsMessageType>[
          MmsMessageType.sendRequest,
          MmsMessageType.retrieveConfirmationInd,
        ]));
    expect(lookup.hydratedMmsIds, [8],
        reason: 'boundary MMS and all off-page MMS stay unhydrated');
  });

  test('thread page caps newest-first membership and hydrates only page MMS',
      () async {
    final start = DateTime.utc(2026, 8, 1, 12);
    final lookup = _ThreadPageLookup(
      sms: [
        _sms(id: 6, sentAt: start.add(const Duration(minutes: 6))),
        _sms(id: 5, sentAt: start.add(const Duration(minutes: 4))),
      ],
      mms: [
        _mms(id: 9, sentAt: start.add(const Duration(minutes: 5))),
        _mms(id: 8, sentAt: start.add(const Duration(minutes: 3))),
        _mms(id: 7, sentAt: start.add(const Duration(minutes: 2))),
      ],
    );

    final page = await lookup.getNormalizedThreadPage(7, limit: 2);

    expect(page.map((message) => (message.channel, message.id)), [
      (SmsMmsType.sms, 6),
      (SmsMmsType.mms, 9),
    ]);
    expect(lookup.hydratedMmsIds, [9],
        reason: 'off-page MMS rows must not trigger parts hydration');
  });
}

class _ThreadPageLookup extends LookupService {
  _ThreadPageLookup({required this.sms, required this.mms});

  final List<Sms> sms;
  final List<Mms> mms;
  SmsFilter? smsFilter;
  MmsFilter? mmsFilter;
  final List<int> hydratedMmsIds = [];

  @override
  Future<List<Sms>> listSms({
    SmsFilter? filter,
    SmsSort? sort,
    int? limit,
    int? offset,
  }) async {
    smsFilter = filter;
    final dateTo = filter?.dateTo;
    final filtered = dateTo == null
        ? sms
        : sms.where((message) => !message.date.isAfter(dateTo)).toList();
    return limit == null ? filtered : filtered.take(limit).toList();
  }

  @override
  Future<List<Mms>> listMms({
    MmsFilter? filter,
    MmsSort? sort,
    int? limit,
    int? offset,
  }) async {
    mmsFilter = filter;
    final dateTo = filter?.dateTo;
    final latestSecond = dateTo == null
        ? null
        : dateTo.millisecondsSinceEpoch ~/ Duration.millisecondsPerSecond;
    final filtered = latestSecond == null
        ? mms
        : mms
            .where(
              (message) =>
                  message.date!.millisecondsSinceEpoch ~/
                      Duration.millisecondsPerSecond <=
                  latestSecond,
            )
            .toList();
    return limit == null ? filtered : filtered.take(limit).toList();
  }

  @override
  Future<List<MmsPart>> listMmsParts({
    required int mmsId,
    MmsPartFilter? filter,
  }) async {
    hydratedMmsIds.add(mmsId);
    return const [];
  }

  @override
  Future<List<MmsParticipant>> listMmsAddressesByMessage(int mmsId) async =>
      const [];
}

Sms _sms({required int id, required DateTime sentAt}) => Sms.fromRaw({
      '_id': id,
      'thread_id': 7,
      'date': sentAt.millisecondsSinceEpoch,
      'type': SmsMessageType.inbox.value,
    });

Mms _mms({required int id, required DateTime sentAt}) => Mms.fromRaw({
      '_id': id,
      'thread_id': 7,
      'date': sentAt.millisecondsSinceEpoch ~/ 1000,
      'm_type': MmsMessageType.retrieveConfirmationInd.value,
      'read': 1,
      'body': 'mms $id',
      'recipients': const <Map<String, dynamic>>[],
      'parts': const <Map<String, dynamic>>[],
    });
