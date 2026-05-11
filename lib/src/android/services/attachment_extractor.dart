// `dart:io` also exports a `ContentType` type — hide it so our local
// MIME enum (declared in `enums/sms_mms_enums.dart`) resolves
// unambiguously inside this file.
import 'dart:io' hide ContentType;

import 'package:flutter/foundation.dart';
import 'package:simple_query/simple_query.dart';

import '../models/enums/sms_mms_enums.dart';
import '../models/filters/mms_filter.dart';
import '../models/messages/mms_part.dart';

/// Content URI for the Android MMS-part provider table. File-private
/// const so future URI changes are a single-site edit and there's no
/// risk of typos in the inline string literal at the query site.
const String _mmsPartUri = 'content://mms/part';

/// MMS attachment retrieval — list parts for a message + extract a part's
/// binary content to a local file.
///
/// **Tier 0f extraction (audit Step 0f).** Originally lived inside
/// [LookupService] alongside the contact / message / conversation
/// query methods. Split into its own file so the 1266-line
/// `lookup_service.dart` becomes per-domain modules.
///
/// LookupService keeps its public API for backward-compat — it now
/// delegates to [AttachmentExtractor.instance].
///
/// **Future work — `SimpleQuery` injection.** Sourcery review on PR
/// #89 suggested taking `SimpleQuery` as a constructor parameter so
/// this class can be unit-tested with a fake. Deferred because the
/// rest of the plugin (LookupService, AndroidMessaging, every other
/// `SimpleQuery.instance` callsite) uses the singleton directly;
/// flipping just AttachmentExtractor would be inconsistent. Tracked
/// for a future plugin-wide DI pass.
class AttachmentExtractor {
  AttachmentExtractor._();

  static final AttachmentExtractor instance = AttachmentExtractor._();

  /// Lists every part row for an MMS message, optionally filtered by
  /// content type substring.
  ///
  /// Each row is materialized via [MmsPart.fromRaw], which now throws
  /// on a fully-empty row (audit PR-B). Callers that iterate over
  /// many MMS messages should wrap individual conversions in their
  /// own try/catch so one bad row doesn't kill the whole pass.
  Future<List<MmsPart>> listMmsParts({
    required int mmsId,
    MmsPartFilter? filter,
  }) async {
    final conditions = <QueryFilterCondition>[
      QueryFilterCondition(
        field: 'mid',
        operator: QueryFilterOperator.equals,
        value: mmsId.toString(),
      ),
    ];
    final contentType = filter?.contentTypeContains;
    if (contentType != null && contentType.isNotEmpty) {
      conditions.add(
        QueryFilterCondition(
          field: 'ct',
          operator: QueryFilterOperator.contains,
          value: contentType,
        ),
      );
    }
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        filters: conditions,
        platformData: {'contentUri': _mmsPartUri},
      ),
    );
    return response.records
        .map((row) => MmsPart.fromRaw(Map<String, dynamic>.from(row)))
        .toList(growable: false);
  }

  /// Extracts the binary content of a single MMS part to [outputDirectory],
  /// returning the resulting [File]. The file is named [filename] if given,
  /// otherwise a name is derived from the part id + mime type.
  ///
  /// Internally this opens a binary handle via `simple_query`, copies the
  /// temporary content to the caller-specified location, and closes the
  /// handle. Throws if the part cannot be opened or copied.
  Future<File> extractMmsPart({
    required int partId,
    required String outputDirectory,
    String? filename,
  }) async {
    final handle = await SimpleQuery.instance.openBinary(
      BinaryRequest(
        domain: QueryDomain.messages,
        entityType: 'mmsPart',
        recordId: partId.toString(),
      ),
    );
    try {
      final dir = Directory(outputDirectory);
      if (!await dir.exists()) {
        await dir.create(recursive: true);
      }
      final targetName = filename ?? _deriveMmsPartFilename(partId, handle);
      final dest = File('${dir.path}/$targetName');
      await File(handle.localPath).copy(dest.path);
      return dest;
    } finally {
      // closeBinary is a best-effort cleanup; surfacing its failure here
      // would shadow the actual return value (or a real copy/IO error
      // raised in the try block). Keep it logged but non-fatal.
      try {
        await SimpleQuery.instance.closeBinary(handle.handleId);
      } catch (e) {
        // ignore: silent_catch — best-effort cleanup, see comment above.
        debugPrint('simple_sms: closeBinary failed for $partId: $e');
      }
    }
  }

  String _deriveMmsPartFilename(int partId, BinaryContentHandle handle) {
    return 'mms_part_$partId${extensionForMime(handle.mimeType)}';
  }

  /// Maps a MIME string to a dotted extension (e.g. `image/jpeg` → `.jpg`).
  /// Returns `''` when the MIME is null/empty. Delegates to
  /// [ContentType.fromMime] which handles both known and unknown MIME
  /// types and strips parameters.
  ///
  /// Public so other components (sync converters, attachment renderers)
  /// can derive consistent extensions without duplicating the rule.
  static String extensionForMime(String? mime) {
    if (mime == null || mime.trim().isEmpty) return '';
    return '.${ContentType.fromMime(mime).extension}';
  }
}
