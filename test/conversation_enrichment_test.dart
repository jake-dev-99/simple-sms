import 'package:flutter_test/flutter_test.dart';
import 'package:simple_query_platform_interface/simple_query_platform_interface.dart';
import 'package:simple_sms_native/android.dart';

/// UNFY-299: one thread's enrichment hitting an unavailable provider leaves
/// that thread unenriched; it never fails the whole conversation list.
void main() {
  late SimpleQueryPlatform original;
  late _Provider provider;

  setUp(() {
    original = SimpleQueryPlatform.instance;
    provider = _Provider();
    SimpleQueryPlatform.instance = provider;
  });
  tearDown(() => SimpleQueryPlatform.instance = original);

  test('an unavailable enrichment read leaves only that thread bare', () async {
    provider.failThread = 2;

    final conversations = await LookupService().listConversations();

    expect(conversations.map((c) => c.threadId), [1, 2]);
    expect(conversations[0].latestSms?.id, 10);
    expect(conversations[0].participants, isEmpty);
    expect(conversations[1].latestSms, isNull);
    expect(conversations[1].participants, isNull);
  });

  test('any other enrichment error still fails the list', () async {
    provider
      ..failThread = 2
      ..failCode = SimpleQueryErrorCode.permissionDenied;

    await expectLater(
      LookupService().listConversations(),
      throwsA(
        isA<SimpleQueryError>().having(
          (e) => e.code,
          'code',
          SimpleQueryErrorCode.permissionDenied,
        ),
      ),
    );
  });

  test('a real error is rethrown even when an unavailable read failed first',
      () async {
    // Thread 2's latest-SMS read is unavailable; its latest-MMS read, which
    // runs alongside, hits a real error.
    provider
      ..failThread = 2
      ..mmsFailure = SimpleQueryErrorCode.invalidQuery;

    await expectLater(
      LookupService().listConversations(),
      throwsA(
        isA<SimpleQueryError>().having(
          (e) => e.code,
          'code',
          SimpleQueryErrorCode.invalidQuery,
        ),
      ),
    );
  });

  test('an unavailable conversations provider still fails the list', () async {
    provider.conversationsUnavailable = true;

    await expectLater(
      LookupService().listConversations(),
      throwsA(
        isA<SimpleQueryError>().having(
          (e) => e.code,
          'code',
          SimpleQueryErrorCode.unavailable,
        ),
      ),
    );
  });
}

/// Two threads with no recipients and one SMS each. [failThread]'s latest-SMS
/// read throws [failCode], the way the Android bridge reports a null cursor.
class _Provider extends SimpleQueryPlatform {
  int? failThread;
  SimpleQueryErrorCode failCode = SimpleQueryErrorCode.unavailable;
  bool conversationsUnavailable = false;

  /// When set, [failThread]'s latest-MMS read throws this code too.
  SimpleQueryErrorCode? mmsFailure;

  @override
  Future<QueryResult> query(QueryRequest request) async {
    final uri = request.platformData?['contentUri'] as String?;
    if (uri == 'content://mms-sms/conversations?simple=true') {
      if (conversationsUnavailable) throw _error(uri!);
      return QueryResult(records: [_thread(1), _thread(2)]);
    }
    if (uri == 'content://sms') {
      final threadId = int.parse(
        request.filters.firstWhere((f) => f.field == 'thread_id').value
            .toString(),
      );
      if (threadId == failThread) throw _error(uri!, code: failCode);
      return QueryResult(
        records: [
          {'_id': threadId * 10, 'thread_id': threadId, 'date': 0, 'type': 2},
        ],
      );
    }
    if (uri == 'content://mms' && mmsFailure != null) {
      final threadId = int.parse(
        request.filters.firstWhere((f) => f.field == 'thread_id').value
            .toString(),
      );
      // Fail after the SMS read has, so the unavailable error comes first.
      await Future<void>.delayed(Duration.zero);
      if (threadId == failThread) throw _error(uri!, code: mmsFailure);
    }
    return const QueryResult(records: []);
  }

  SimpleQueryError _error(String uri, {SimpleQueryErrorCode? code}) =>
      SimpleQueryError(
        code: code ?? SimpleQueryErrorCode.unavailable,
        message: 'simple_query: content provider returned no cursor for $uri',
        details: {'contentUri': uri},
      );

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

Map<String, Object?> _thread(int id) => {
  '_id': id,
  'date': 0,
  'recipient_ids': '',
  'message_count': 1,
};
