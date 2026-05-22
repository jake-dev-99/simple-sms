# simple_sms

A modern SMS / MMS plugin for Android that provides comprehensive messaging functionality for Flutter applications.

> **Contributing / agents:** see [`AGENTS.md`](AGENTS.md) for build·test·verify, the four-package layering contract, and the "What NOT to do" rulings (Claude Code reads it via [`CLAUDE.md`](CLAUDE.md); durable rules in [`docs/memory/`](docs/memory/)). Governed by the Simple Zen SOP family (Notion).

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
  simple_sms: ^0.0.1
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
import 'package:simple_sms/simple_sms.dart';

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
    body: 'Hello from simple_sms!',
    addresses: {'+15551234567'},
    attachmentPaths: null,
  ),
);
```

### Request permissions

```dart
// Request SMS permissions
await AndroidPermissions.requestPermissions(Intention.texting);

// Request default SMS app role (required for full send/receive)
await AndroidPermissions.requestRole(Intention.texting);
```

### Look up contacts and messages

```dart
final service = LookupService();

// Find a contact by phone number
final contact = await service.lookupContactableByAddress('+15551234567');

// Get all SMS in a thread
final messages = await service.getSmsByThread(threadId);
```

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

## License

BSD 3-Clause. See [LICENSE](LICENSE) for details.
