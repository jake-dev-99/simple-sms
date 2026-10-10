# simple_sms_native

A modern SMS / MMS plugin for Android that provides comprehensive messaging functionality for Flutter applications.

> **Contributing / agents:** see [`AGENTS.md`](AGENTS.md) for build·test·verify, the four-package layering contract, and the "What NOT to do" rulings (Claude Code reads it via [`CLAUDE.md`](CLAUDE.md)). Governed by the Simple Zen [Documentation Standard](https://www.notion.so/3673802ee6ba81e0b892f7cfae1216b9), [Code Quality Standard](https://www.notion.so/3673802ee6ba81b1af59f02aece61595), and [Toolchain Architecture](https://www.notion.so/3673802ee6ba81cfb3f9d8d000115a52) in Notion.

## Features

- Send and receive SMS and MMS messages
- Background message delivery (even when app is killed)
- Conversation thread management
- Contact and participant resolution
- MMS attachment support (images, video, audio)
- Runtime permissions + default-SMS-app role management are delegated
  to [`simple_permissions_native`](https://pub.dev/packages/simple_permissions_native)
  so consumers have one source of truth for access state.

## Platform Support

| Android | iOS | Web | macOS | Windows | Linux |
|---------|-----|-----|-------|---------|-------|
| API 30+ | -   | -   | -     | -       | -     |

## Getting Started

Add to your `pubspec.yaml`:

```yaml
dependencies:
  simple_sms_native: ^0.5.1
```

### Permissions

Declare these in your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.RECEIVE_MMS" />
<uses-permission android:name="android.permission.RECEIVE_WAP_PUSH" />
```

**Runtime grants + the default-SMS-app role** go through
`simple_permissions_native`:

```dart
import 'package:simple_permissions_native/simple_permissions_native.dart';

// Check and request everything the plugin needs to function.
final ok = await SimplePermissionsNative.instance.requestAll(const [
  ReceiveSms(),
  ReadSms(),
  SendSms(),
  DefaultSmsApp(), // Android role; only the default SMS app can write
                   // the Telephony provider or mark messages delivered.
]);
if (!ok.isFullyGranted) {
  // Show "grant to continue" banner / disable writes.
}

// Or observe reactively so the UI updates when the user grants via
// system Settings.
final observer = SimplePermissionsNative.instance.observe(const [
  DefaultSmsApp(),
  ReceiveSms(),
  SendSms(),
]);
```

| Operation | Needs |
| --- | --- |
| Read conversations / threads / messages | `ReadSms` |
| Receive inbound SMS broadcasts | `ReceiveSms` |
| Receive MMS / write Telephony provider | `DefaultSmsApp` |
| Send SMS | `SendSms` + `DefaultSmsApp` (to mark delivered) |

### Initialize

```dart
import 'package:simple_sms_native/simple_sms_native.dart';

void main() {
  Android.initialize(
    inboundSmsCallback: (Sms sms) {
      print('SMS from ${sms.address}: ${sms.body}');
    },
    inboundMmsCallback: (Mms mms) {
      print('MMS from ${mms.address}: ${mms.body}');
    },
  );
  runApp(const MyApp());
}
```

### Send a message

```dart
final result = await Android.instance.messaging.sendMessage(
  message: OutboundMessage(
    body: 'Hello from simple_sms_native!',
    addresses: {'+15551234567'},
    attachmentPaths: null,
  ),
);
```

### Look up contacts and messages

```dart
final service = LookupService();

// Find a contact by phone number
final contact = await service.lookupContactableByAddress('+15551234567');

// Get all SMS in a thread
final messages = await service.getSmsByThread(threadId);
```

### Mark messages and conversations read or unread

Read-state actions write the authoritative Android SMS/MMS store. Obtain the
`DefaultSmsApp` role through `simple_permissions_native` before calling them;
these methods do not request permissions.

```dart
import 'package:simple_sms_native/android.dart';

// The native ID is unique within its SMS or MMS table, so always pass its type.
await AndroidAction.markMessageAsUnread('42', channel: SmsMmsType.sms);
await AndroidAction.markMessageAsRead('42', channel: SmsMmsType.sms);
await AndroidAction.markMessageAsUnread('17', channel: SmsMmsType.mms);
await AndroidAction.markMessageAsRead('17', channel: SmsMmsType.mms);

// Conversation IDs are native thread IDs; these actions update both tables.
await AndroidAction.markConversationAsUnread('123');
await AndroidAction.markConversationAsRead('123');
```

Each method returns `Future<bool>`: `true` means the provider reported updated
rows; `false` means no rows were updated. Conversation actions update only rows
whose read flag differs, so repeating an action on an unchanged conversation
returns `false`. A missing message or conversation also returns `false`.

Marking read sets `READ=1` and `SEEN=1`. Marking unread sets `READ=0` and preserves
`SEEN`, restoring the follow-up cue without making the message newly unseen.
Provider failures throw `PlatformException` with `MARK_READ_FAILED` or
`MARK_UNREAD_FAILED`; callers must handle these errors. Conversation writes
update SMS and MMS separately, so a provider failure can leave one table
updated. Retrying the same action safely sets the requested flags again.

The explicit read/unread method pairs provide the symmetric API. Existing
`markMessageAsRead` and `markConversationAsRead` callers remain supported.

### Background message handling

When your app is the default SMS app, messages arrive even when the app is killed. Define a top-level entrypoint:

```dart
@pragma('vm:entry-point')
void initializeApp() {
  AndroidMessaging.initialize(
    inboundSmsCallback: handleSms,
    inboundMmsCallback: handleMms,
  );
}
```

See `example/lib/background_example.dart` for the full pattern.

## Android implementation and validation

The retained SMS/MMS code is maintained as Kotlin under
`io.simplezen.simple_sms.mms`: `codec` holds PDU parsing and composition,
`storage` holds persistence helpers, `sending` holds the outbound transaction
layer, and `support` holds the shared helpers. This internal namespace change
preserves the public Dart API, models, imports, and platform-channel contracts.

MMS transfer uses Android's `SmsManager`. The platform and the selected SIM's
carrier configuration own maximum-message-size and HTTP parameters; the plugin
does not ship a carrier APN table, `mms_config.xml`, or an 800 KiB size override.
The Verizon content-location completion in `resolveVerizonDownloadUrl` remains
part of inbound notification handling and is covered by native unit tests.

With `simple-permissions` and `simple-query` checked out beside this repo, run
the local gates before pushing:

```sh
flutter pub get
flutter analyze --no-fatal-warnings
flutter test
./scripts/verify_mms_migration.sh
flutter pub publish --dry-run
(cd example && flutter pub get && flutter build apk --debug)
(cd example/android && ./gradlew :simple_sms_native:testDebugUnitTest --console=plain)
```

Check that the publish dry-run includes root `LICENSE` and `NOTICE`. `LICENSE`
contains every retained license so Flutter can include them in the consuming
app's license bundle; a standalone `NOTICE` is not automatically collected.
The migration check rejects production Java, retired upstream package names,
and the removed carrier XML files. These local gates compile and test the
code, but do not establish carrier interoperability.

For a Samsung Galaxy S24 Ultra with an active Verizon SIM, connect the phone
and record the tested commit, Android/One UI build, active SMS/data SIM, and
network state. Obtain the device ID with `flutter devices`, then run:

```sh
cd example
flutter run -d <device-id>
```

In the example, tap **Grant Permissions** and **Set as Default SMS App**. Use
test recipients and messages, and keep the Flutter console open: inbound
callbacks print there. Repeat the following checks with mobile data available,
first with Wi-Fi off and then with Wi-Fi on:

| Check | Evidence to record |
| --- | --- |
| Send and receive short SMS, Unicode SMS, and a multipart-length body | Recipient sees the complete text; returned native record and inbound callback match the SMS provider row. |
| Tap **Send MMS** with one image, then **Send MMS (Multiple Attachments)** with two distinct images | Recipient sees the text and all attachments; the returned record reports the actual send outcome. |
| Receive image MMS and a group MMS from another phone | One inbox record per message, expected sender/recipients, body and attachment MIME types; no duplicate record from paired WAP-push broadcasts. |
| Background the app and receive SMS/MMS | Provider persistence and callbacks still complete. For a terminated-process check, use the `initializeApp` entrypoint pattern in `background_example.dart`; swiping away the app is distinct from Android force-stop. |
| Deny SMS access or remove the default-SMS role and retry the affected operation | The permission state and operation error are visible; no false success or unintended provider write. Restore access before the next case. |

Inspect provider records through `LookupService`/`simple_query` in the test
host, including message ID, thread ID, type, read/seen state, and MMS part
count. The stock example is an interactive send/receive harness, not an
automated device suite or a provider-inspection screen. Record actual device
results separately from JVM/Dart test results; no Samsung/Verizon sign-off is
implied by a successful build or package dry-run.

## Release requirements

The MMS migration preserves the public Dart API and channel contracts, so the
next release uses a **minor** bump (`0.5.1` to `0.6.0`). The Cut Release workflow
owns the version and tag; follow [Release flow](doc/RELEASE.md) after the device
regression and code review pass.

Local sibling overrides validate development sources, not the published
dependency graph. Release and publish workflows resolve the package and
bundled example without those overrides. `simple_query ^0.2.2` is required for
the native `ContentQuery` API; published `0.2.0` lacks it and also has an
incompatible permissions constraint. Publish the required sibling versions
before cutting this release. Do not lower the constraint or bypass dependency
validation to make publication succeed.

## Acknowledgements

The Android SMS/MMS internals contain Kotlin ports and adaptations of
[**android-smsmms**](https://github.com/klinker41/android-smsmms) by **Jacob
Klinker**, including the AOSP MMS stack it carried. The sending layer, PDU
codec, persistence helpers, and associated types are derived licensed code.
Their Apache-2.0 obligations remain after translation and namespace changes.

Retained source notices also name **The Android Open Source Project**,
**Esmertec AG**, and **The Linux Foundation**. Android Gradle scaffolding
retains **The Flutter Authors**' BSD attribution. See [NOTICE](NOTICE) for the
component inventory and [LICENSE](LICENSE) for the complete license texts.

## License

Original Simple Zen code is BSD 3-Clause. Retained android-smsmms/AOSP-derived
components remain Apache-2.0, and Flutter scaffolding retains its upstream BSD
terms. All license texts are included in [LICENSE](LICENSE).
