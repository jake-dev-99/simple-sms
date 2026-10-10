import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Purpose: verify the release gate rejects each obsolete MMS artifact.
/// Returns no value; failed process contracts surface as test failures.
void main() {
  final gate = File('scripts/verify_mms_migration.sh').absolute.path;
  late Directory source;

  setUp(() {
    source = Directory.systemTemp.createTempSync('mms-migration-gate-');
  });
  tearDown(() {
    source.deleteSync(recursive: true);
  });

  Future<ProcessResult> check() => Process.run('bash', [gate, source.path]);

  test(
    'accepts first-party Kotlin without obsolete carrier resources',
    () async {
      File(
        '${source.path}/Codec.kt',
      ).writeAsStringSync('package io.simplezen.simple_sms.mms.codec\n');
      final result = await check();
      expect(result.exitCode, 0, reason: result.stderr.toString());
      expect(result.stdout, contains('checks passed'));
    },
  );

  for (final artifact in ['Legacy.java', 'mms_config.xml', 'apns.xml']) {
    test('rejects obsolete artifact $artifact', () async {
      File('${source.path}/$artifact').writeAsStringSync('obsolete');
      final result = await check();
      expect(result.exitCode, 1);
      expect(result.stderr, contains(artifact));
    });
  }

  for (final namespace in [
    'com.klinker.android.send_message',
    'com.android.mms',
    'com.google.android.mms',
  ]) {
    test('rejects obsolete namespace $namespace', () async {
      File('${source.path}/Legacy.kt').writeAsStringSync('package $namespace');
      final result = await check();
      expect(result.exitCode, 1);
      expect(result.stderr, contains(namespace));
    });
  }

  test('rejects Java-named paths regardless of their file type', () async {
    Directory('${source.path}/Legacy.java').createSync();
    final result = await check();
    expect(result.exitCode, 1);
    expect(result.stderr, contains('Legacy.java'));
  });

  test('fails when the source directory is missing', () async {
    final result = await Process.run('bash', [gate, '${source.path}/missing']);
    expect(result.exitCode, 2);
    expect(result.stderr, contains('Missing Android source directory'));
  });

  test('checks the real Android source tree', () async {
    final result = await Process.run('bash', [gate]);
    expect(result.exitCode, 0, reason: result.stderr.toString());
  });
}
