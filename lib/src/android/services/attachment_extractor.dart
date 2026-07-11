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

/// Construct the `simple_query` [BinaryRequest] that opens an MMS part by
/// its native row id.
///
/// Factored out so the request shape is the single point of truth for every
/// open-path — eager [AttachmentExtractor.extractMmsPart], on-demand
/// [AttachmentExtractor.openMmsPart], scoped
/// [AttachmentExtractor.withMmsPart] — and so the shape is unit-testable
/// without going through `simple_query`'s binary backend.
@visibleForTesting
BinaryRequest mmsPartBinaryRequest(int partId) => BinaryRequest(
      domain: QueryDomain.messages,
      entityType: 'mmsPart',
      recordId: partId.toString(),
    );

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

  /// Batched variant of [listMmsParts] — fetches parts for MANY MMS ids in a
  /// single `mid IN (...)` query, grouped by owning MMS id.
  ///
  /// Conversation-list enrichment hydrates every thread's latest-MMS parts;
  /// doing that with one [listMmsParts] per thread fired N concurrent
  /// `content://mms/part` queries and made large inboxes slow (UNFY-250). This
  /// collapses them into one query. A malformed / unknown-MIME part row is
  /// skipped (logged) rather than failing the whole batch — the same per-row
  /// guard the [listMmsParts] doc asks many-message callers to apply.
  Future<Map<int, List<MmsPart>>> listMmsPartsForMessages(
    List<int> mmsIds,
  ) async {
    if (mmsIds.isEmpty) return const {};
    final response = await SimpleQuery.instance.query(
      QueryRequest(
        domain: QueryDomain.platformSpecific,
        filters: [
          QueryFilterCondition(
            field: 'mid',
            operator: QueryFilterOperator.inList,
            value: mmsIds.map((id) => id.toString()).toList(),
          ),
        ],
        platformData: {'contentUri': _mmsPartUri},
      ),
    );
    final byMid = <int, List<MmsPart>>{};
    for (final row in response.records) {
      try {
        final part = MmsPart.fromRaw(Map<String, dynamic>.from(row));
        final mid = part.messageId;
        if (mid == null) continue;
        (byMid[mid] ??= <MmsPart>[]).add(part);
      } catch (e) {
        debugPrint(
          '[diag][simple-sms] listMmsPartsForMessages skipped a bad part row: $e',
        );
      }
    }
    return byMid;
  }

  /// Extracts the binary content of a single MMS part to [outputDirectory],
  /// returning the resulting [File]. The file is named [filename] if given,
  /// otherwise a name is derived from the part id + mime type.
  ///
  /// **Legacy materialization path** (ADR-0014, UNFY-211). Forces a copy to
  /// caller-owned disk and is the system-of-record pattern the provider
  /// migration moves away from. New callers should prefer [openMmsPart] /
  /// [withMmsPart], which hand out a self-closing handle the host opens
  /// on demand and disposes — caching is the host's choice, not forced.
  /// Retained for the existing sync pipeline until UNFY-218 retires it.
  ///
  /// Internally this opens a binary handle via `simple_query`, copies the
  /// temporary content to the caller-specified location, and closes the
  /// handle. Throws if the part cannot be opened or copied.
  Future<File> extractMmsPart({
    required int partId,
    required String outputDirectory,
    String? filename,
  }) async {
    final handle =
        await SimpleQuery.instance.openBinary(mmsPartBinaryRequest(partId));
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

  /// Opens an MMS part for **on-demand** binary access (ADR-0014, UNFY-211).
  ///
  /// Returns a [BinaryContent] whose lifetime the caller owns: the host
  /// reads bytes (via `File(content.localPath).readAsBytes()`, etc.) when
  /// it actually needs to render the attachment, and **is responsible for
  /// calling `close()` when done** — same contract as
  /// [SimpleQuery.openBinaryContent]. No copy is forced into a caller-owned
  /// directory: caching is the host's choice, not the provider's mandate.
  /// Pair with [NormalizedAttachment.partId].
  ///
  /// Prefer [withMmsPart] when the read is scoped to a single function
  /// body: it pairs the open with a guaranteed close, which this raw form
  /// leaves to the caller.
  Future<BinaryContent> openMmsPart(int partId) {
    return SimpleQuery.instance.openBinaryContent(mmsPartBinaryRequest(partId));
  }

  /// Opens an MMS part for the duration of [body] and closes it when [body]
  /// returns or throws (ADR-0014, UNFY-211). Returns whatever [body] returns.
  ///
  /// ```dart
  /// final bytes = await LookupService().withMmsPart(
  ///   attachment.partId,
  ///   (content) => File(content.localPath).readAsBytes(),
  /// );
  /// ```
  Future<R> withMmsPart<R>(
    int partId,
    Future<R> Function(BinaryContent content) body,
  ) {
    return SimpleQuery.instance
        .withBinaryContent(mmsPartBinaryRequest(partId), body);
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
