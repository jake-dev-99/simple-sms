import 'package:flutter_test/flutter_test.dart';
import 'package:simple_query/simple_query.dart';
// Importing the private services layer is allowed inside the package itself
// (this test file is part of `simple_sms_native`), so the
// `@visibleForTesting` helper is reachable here without a public re-export.
import 'package:simple_sms_native/src/android/services/attachment_extractor.dart';

/// Pins the on-demand attachment-open contract (ADR-0014, UNFY-211): every
/// open path — eager `extractMmsPart`, on-demand `openMmsPart`, scoped
/// `withMmsPart` — must build the same [BinaryRequest] shape, since they
/// all hit the same `simple_query` binary backend for the same MMS-part
/// row. A drift between paths would mean two of them open a different
/// record than the third.

void main() {
  group('mmsPartBinaryRequest', () {
    test('targets the messages domain with the mmsPart entityType', () {
      final req = mmsPartBinaryRequest(42);
      expect(req.domain, QueryDomain.messages);
      expect(req.entityType, 'mmsPart');
    });

    test('encodes the partId as recordId (stringified int)', () {
      final req = mmsPartBinaryRequest(123456);
      expect(req.recordId, '123456');
    });

    test('encodes 0 / large ids round-trip-safe', () {
      expect(mmsPartBinaryRequest(0).recordId, '0');
      expect(mmsPartBinaryRequest(9007199254740991).recordId,
          '9007199254740991'); // safe-int ceiling
    });

    test('two requests for the same partId are equal', () {
      // Stability matters because consumers may cache/dedupe by request.
      expect(mmsPartBinaryRequest(7), mmsPartBinaryRequest(7));
    });

    test('different partIds produce different requests', () {
      expect(mmsPartBinaryRequest(1), isNot(mmsPartBinaryRequest(2)));
    });
  });
}
