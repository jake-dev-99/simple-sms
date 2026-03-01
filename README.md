# simple_sms

A modern MMS / SMS plugin for Android that provides comprehensive messaging functionality for Flutter applications. iOS support coming soon!

Note: This is my first package published to pub.dev and may not fully align with best practices. I'll continue iterating on this as my personal needs grow, and my overall knowledge develops. Feel free to submit a PR or contact me directly for any contributions.

## Features

- 📱 Send and receive SMS and MMS messages
- 💬 Manage conversations and threads
- 👥 Handle contacts and participants
- 📎 Support for MMS attachments
- 📞 Access device and SIM card information
- 🔍 Query message history
- 🔔 Local notification support

## Platform Support

| Android | iOS | Web | macOS | Windows | Linux |
| --------- | ----- | ----- | ------- | --------- | ------- |
| ✅      | ❌  | ❌  | ❌    | ❌      | ❌    |

## Installation

Add this to your package's `pubspec.yaml` file:

```yaml
dependencies:
    simple_sms: ^0.0.1
```

Then run:

```bash
flutter pub get
```

## Permissions

Add the following permissions to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.READ_CONTACTS" />
```

Don't forget to request runtime permissions in your app.

## Usage

```dart
import 'package:simple_sms/android.dart';

// Send an SMS
final message = OutboundMessage(
    address: '+1234567890',
    body: 'Hello from simple_sms!',
);
await AndroidMessaging.sendMessage(message);

// Query conversations
final conversations = await AndroidMessaging.getConversations();

// Access device information
final device = await Device.getDeviceInfo();
final simCards = await Device.getSimCards();
```

## Models

The plugin exports various models for working with messages:

- `Sms` - SMS message data
- `Mms` - MMS message data
- `MmsPart` - MMS attachment parts
- `OutboundMessage` - For sending messages
- `MmsSmsConversations` - Conversation threads
- `Contact` - Contact information
- `MmsParticipant` - Message participants
- `Device` - Device information
- `SimCard` - SIM card details

## License

BSD-style license. See LICENSE file for details.

## Contributing

Contributions are welcome! Please open an issue or submit a pull request.

## Homepage

Visit [simplezen.io](https://simplezen.io) for more information.# simple-sms
# simple-sms
# simple-sms
# simple-sms
# simple-sms
