// `readContactPhoto` reads a contact's thumbnail through simple_query and
// tells "no photo" apart from a failed read (UNFY-401).

import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:simple_query/simple_query.dart';
import 'package:simple_sms_native/android.dart';
// Importing the private services layer is allowed inside the package itself,
// so the `@visibleForTesting` request builder is reachable here.
import 'package:simple_sms_native/src/android/services/contact_lookup.dart';

import 'support/fake_content_provider_platform.dart';

const _contactsUri = 'content://com.android.contacts/contacts';
const _thumbUri = 'content://com.android.contacts/contacts/76/photo';

/// The first bytes of a JPEG file; the read must return them unchanged.
final _jpegBytes = Uint8List.fromList([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10]);

/// Purpose: build a contacts row as the provider returns it.
///
/// @param id the contact `_id`.
/// @param thumbUri the row's `photo_thumb_uri`, or null for no photo.
/// @returns the provider row.
/// @throws nothing.
Map<String, Object?> _contactRow({required int id, String? thumbUri}) => {
      '_id': id,
      'display_name': 'Contact $id',
      'photo_id': thumbUri == null ? null : 9001,
      'photo_thumb_uri': thumbUri,
    };

/// Purpose: install a fake provider whose binary stream reads [path].
///
/// @param path the file the stream's `localPath` names.
/// @param rows the contacts rows to seed.
/// @returns the installed fake.
/// @throws nothing.
FakeContentProviderPlatform _install({
  required String path,
  required List<Map<String, Object?>> rows,
}) =>
    FakeContentProviderPlatform.install(
      binaryContentHandle: BinaryContentHandle(handleId: 'h', localPath: path),
    )..seed(_contactsUri, rows);

void main() {
  late Directory dir;

  setUp(() {
    dir = Directory.systemTemp.createTempSync('contact_photo_read_test');
    addTearDown(() => dir.deleteSync(recursive: true));
  });

  test('returns the thumbnail bytes and closes the stream', () async {
    final file = File('${dir.path}/photo')..writeAsBytesSync(_jpegBytes);
    final provider = _install(
      path: file.path,
      rows: [_contactRow(id: 76, thumbUri: _thumbUri)],
    );

    final bytes = await LookupService().readContactPhoto(76);

    expect(bytes, _jpegBytes);
    expect(provider.binaryRequests, [contactPhotoBinaryRequest(_thumbUri)]);
    expect(provider.closeBinaryCalls, 1);
  });

  test('streams the thumbnail the provider names, in the contacts domain',
      () {
    final request = contactPhotoBinaryRequest(_thumbUri);

    expect(request.domain, QueryDomain.contacts);
    expect(request.platformData, {'contentUri': _thumbUri});
  });

  test('answers null without a stream for a contact with no photo', () async {
    final provider = _install(
      path: '${dir.path}/unused',
      rows: [_contactRow(id: 1)],
    );

    expect(await LookupService().readContactPhoto(1), isNull);
    expect(provider.openBinaryCalls, 0);
  });

  test('answers null without a stream for a contact that is gone', () async {
    final provider = _install(
      path: '${dir.path}/unused',
      rows: [_contactRow(id: 1, thumbUri: _thumbUri)],
    );

    expect(await LookupService().readContactPhoto(404), isNull);
    expect(provider.openBinaryCalls, 0);
  });

  test('throws when a contact with a photo yields no bytes', () async {
    final file = File('${dir.path}/empty')..writeAsBytesSync(const []);
    final provider = _install(
      path: file.path,
      rows: [_contactRow(id: 76, thumbUri: _thumbUri)],
    );

    await expectLater(
      LookupService().readContactPhoto(76),
      throwsA(
        isA<ContactPhotoReadException>()
            .having((e) => e.contactId, 'contactId', 76),
      ),
    );
    expect(provider.closeBinaryCalls, 1);
  });

  test('times out a stalled stream and still closes it', () async {
    // A FIFO with no writer never yields bytes, like a stalled provider copy.
    final fifo = '${dir.path}/stalled';
    final made = await Process.run('mkfifo', [fifo]);
    expect(made.exitCode, 0, reason: '${made.stderr}');
    final provider = _install(
      path: fifo,
      rows: [_contactRow(id: 76, thumbUri: _thumbUri)],
    );

    await expectLater(
      LookupService().readContactPhoto(
        76,
        timeout: const Duration(milliseconds: 50),
      ),
      throwsA(isA<TimeoutException>()),
    );
    expect(provider.closeBinaryCalls, 1);

    // Open and close a writer so the abandoned reader sees end of file and
    // releases its I/O thread before the directory is deleted.
    await File(fifo).writeAsBytes(const []);
  });

  test('times out a stalled contact lookup before opening a stream', () async {
    final provider = _install(path: '${dir.path}/unused', rows: const []);
    provider.holdQueriesTo(_contactsUri, Completer<void>().future);

    await expectLater(
      LookupService().readContactPhoto(
        76,
        timeout: const Duration(milliseconds: 50),
      ),
      throwsA(isA<TimeoutException>()),
    );
    expect(provider.openBinaryCalls, 0);
  });

  test('reads a stream of exactly maxBytes', () async {
    final file = File('${dir.path}/photo')..writeAsBytesSync(_jpegBytes);
    _install(
      path: file.path,
      rows: [_contactRow(id: 76, thumbUri: _thumbUri)],
    );

    final bytes = await LookupService().readContactPhoto(
      76,
      maxBytes: _jpegBytes.length,
    );

    expect(bytes, _jpegBytes);
  });

  test('refuses a stream over maxBytes and still closes it', () async {
    final file = File('${dir.path}/photo')..writeAsBytesSync(_jpegBytes);
    final provider = _install(
      path: file.path,
      rows: [_contactRow(id: 76, thumbUri: _thumbUri)],
    );

    await expectLater(
      LookupService().readContactPhoto(76, maxBytes: _jpegBytes.length - 1),
      throwsA(
        isA<ContactPhotoReadException>()
            .having((e) => e.contactId, 'contactId', 76),
      ),
    );
    expect(provider.closeBinaryCalls, 1);
  });

  test('surfaces a provider failure instead of answering no photo', () async {
    final provider = _install(path: '${dir.path}/unused', rows: const []);
    provider.failQueriesTo(_contactsUri, StateError('provider down'));

    await expectLater(
      LookupService().readContactPhoto(76),
      throwsA(isA<StateError>()),
    );
    expect(provider.openBinaryCalls, 0);
  });

  group('hasPhoto', () {
    test('is true when the provider names a thumbnail', () {
      final contact =
          AndroidContact.fromRaw(_contactRow(id: 76, thumbUri: _thumbUri));
      expect(contact.hasPhoto, isTrue);
    });

    test('is false when the provider names no thumbnail', () {
      expect(AndroidContact.fromRaw(_contactRow(id: 1)).hasPhoto, isFalse);
    });
  });
}
