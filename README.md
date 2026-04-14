# simple_sms

A modern SMS / MMS plugin for Android that provides comprehensive messaging functionality for Flutter applications.

## Features

- Send and receive SMS and MMS messages
- Background message delivery (even when app is killed)
- Conversation thread management
- Contact and participant resolution
- MMS attachment support (images, video, audio)
- Permission and default SMS app role management
- Device and SIM card information queries

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

Add these to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.RECEIVE_MMS" />
<uses-permission android:name="android.permission.RECEIVE_WAP_PUSH" />
```

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
