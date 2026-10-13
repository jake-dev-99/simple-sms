import 'package:flutter/services.dart';

/// Run a verified native write and translate its `PlatformException` into the
/// write's typed failure contract.
///
/// [reasonFor] maps a native error code to the write's failure reason, or
/// null when the code is not one of its failure codes; [failure] builds the
/// typed exception for a known reason. An unknown code (e.g. a new native code
/// or an engine channel error) throws [StateError] carrying what native sent:
/// a reason is never guessed. [operation] names the write in that message.
Future<Object?> invokeVerifiedWrite<R extends Object>({
  required String operation,
  required Future<Object?> Function() invoke,
  required R? Function(String code) reasonFor,
  required Exception Function(R reason, PlatformException error) failure,
}) async {
  try {
    return await invoke();
  } on PlatformException catch (e) {
    final reason = reasonFor(e.code);
    if (reason == null) {
      throw StateError(
        'Unrecognized $operation error code from native: "${e.code}" '
        '(${e.message}) ${e.details ?? ''}',
      );
    }
    throw failure(reason, e);
  }
}
