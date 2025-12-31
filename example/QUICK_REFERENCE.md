# Simple SMS - Quick Reference Card

## ⚡ 30-Second Setup

```dart
import 'package:simple_sms/simple_sms.dart';

void main() {
  // 1. Initialize with callbacks
  AndroidMessaging.initialize(
    inboundMmsCallback: (mms) => print('MMS: ${mms.body}'),
    inboundSmsCallback: (sms) => print('SMS: ${sms.body}'),
  );

  runApp(MyApp());
}

// 2. Request permissions (call from a widget)
await AndroidPermissions.requestPermissions(Intention.texting);
await AndroidPermissions.requestRole(Intention.texting);

// 3. Send a message
final msg = OutboundMessage(
  body: 'Hello!',
  addresses: {'+1234567890'},
  attachmentPaths: null, // or {'/path/to/file.jpg'} for MMS
);
await AndroidMessaging.instance.sendMessage(message: msg);
```

---

## 📋 Common Tasks

### Send SMS
```dart
final message = OutboundMessage(
  body: 'Your message here',
  addresses: {'+1234567890'},
  attachmentPaths: null,
);
await AndroidMessaging.instance.sendMessage(message: message);
```

### Send MMS with Image
```dart
final message = OutboundMessage(
  body: 'Check this out!',
  addresses: {'+1234567890'},
  attachmentPaths: {'/storage/image.jpg'},
);
await AndroidMessaging.instance.sendMessage(message: message);
```

### Send to Multiple Recipients
```dart
final message = OutboundMessage(
  body: 'Group message',
  addresses: {'+1234567890', '+0987654321', '+1122334455'},
  attachmentPaths: null,
);
await AndroidMessaging.instance.sendMessage(message: message);
```

### Send MMS with Multiple Attachments
```dart
final message = OutboundMessage(
  body: 'Multiple files',
  addresses: {'+1234567890'},
  attachmentPaths: {
    '/path/to/image1.jpg',
    '/path/to/image2.jpg',
    '/path/to/video.mp4',
  },
);
await AndroidMessaging.instance.sendMessage(message: message);
```

### Send to Existing Thread
```dart
final message = OutboundMessage(
  body: 'Reply message',
  addresses: {}, // Can be empty with conversationId
  attachmentPaths: null,
  conversationId: '12345',
);
await AndroidMessaging.instance.sendMessage(message: message);
```

---

## 🔐 Permissions

### Check Permissions
```dart
final perms = await AndroidPermissions.checkPermissions(Intention.texting);
final allGranted = perms.values.every((granted) => granted);
```

### Request SMS Permissions
```dart
await AndroidPermissions.requestPermissions(Intention.texting);
```

### Request File Access (for MMS)
```dart
await AndroidPermissions.requestPermissions(Intention.fileAccess);
```

### Set as Default SMS App
```dart
final isDefault = await AndroidPermissions.checkRole(Intention.texting);
if (!isDefault) {
  await AndroidPermissions.requestRole(Intention.texting);
}
```

---

## 📨 Receiving Messages

### Handle Incoming SMS
```dart
void handleSms(Sms sms) {
  print('From: ${sms.address}');
  print('Body: ${sms.body}');
  print('Date: ${sms.date}');
  print('Thread ID: ${sms.threadId}');
}
```

### Handle Incoming MMS
```dart
void handleMms(Mms mms) {
  print('From: ${mms.address}');
  print('Subject: ${mms.subject}');
  print('Body: ${mms.body}');
  print('Attachments: ${mms.parts?.length ?? 0}');

  // Process attachments
  mms.parts?.forEach((part) {
    if (part.isText) {
      print('Text: ${part.text}');
    } else {
      print('File: ${part.fileName} (${part.contentType.value})');
      print('Location: ${part.dataLocation}');
    }
  });
}
```

---

## 🎨 MMS Content Processing

### Extract Images
```dart
List<MmsPart> getImages(Mms mms) {
  return mms.parts?.where((p) =>
    p.contentType.value.toLowerCase().contains('image/')
  ).toList() ?? [];
}
```

### Extract Videos
```dart
List<MmsPart> getVideos(Mms mms) {
  return mms.parts?.where((p) =>
    p.contentType.value.toLowerCase().contains('video/')
  ).toList() ?? [];
}
```

### Get All Text
```dart
String getAllText(Mms mms) {
  return mms.parts
    ?.where((p) => p.isText && !p.isSmil)
    .map((p) => p.text ?? '')
    .where((text) => text.isNotEmpty)
    .join('\n\n') ?? mms.body;
}
```

---

## 🎯 Data Models

### OutboundMessage
```dart
OutboundMessage({
  required String body,              // Message text
  required Set<String> addresses,    // Phone numbers
  Set<String>? attachmentPaths,     // File paths (null for SMS)
  String? conversationId,           // Thread ID (optional)
})
```

### Sms
```dart
class Sms {
  int id;
  String? address;         // Sender/recipient
  String? body;           // Message text
  DateTime? date;         // Received date
  int threadId;           // Conversation thread
  SmsType? type;          // INBOX, SENT, etc.
  bool read;              // Read status
  // ... many more fields
}
```

### Mms
```dart
class Mms {
  int id;
  String? address;           // Sender/recipient
  String? subject;          // MMS subject
  String body;              // Text content
  DateTime? date;           // Received date
  int threadId;             // Conversation thread
  List<MmsPart>? parts;     // Attachments
  List<MmsParticipant>? recipients;
  MmsParticipant? sender;
  // ... many more fields
}
```

### MmsPart
```dart
class MmsPart {
  int id;
  ContentType contentType;   // MIME type
  String? fileName;         // Attachment name
  String? text;            // Text content
  Uri? dataLocation;       // File URI
  bool isText;             // Is text part?
  bool isSmil;             // Is SMIL?
  // ... more fields
}
```

---

## 🔧 Error Handling

### Basic Try-Catch
```dart
try {
  await AndroidMessaging.instance.sendMessage(message: msg);
  print('✅ Message sent');
} catch (e) {
  print('❌ Error: $e');
}
```

### Check Before Send
```dart
Future<void> safeSend(OutboundMessage msg) async {
  // Check permissions
  final perms = await AndroidPermissions.checkPermissions(
    Intention.texting
  );
  if (!perms.values.every((g) => g)) {
    throw Exception('Missing permissions');
  }

  // Check default app
  final isDefault = await AndroidPermissions.checkRole(
    Intention.texting
  );
  if (!isDefault) {
    throw Exception('Not default SMS app');
  }

  // Send
  await AndroidMessaging.instance.sendMessage(message: msg);
}
```

---

## 💡 Best Practices

1. **Always initialize in main()** before runApp()
2. **Request permissions early** in your app flow
3. **Check permissions** before attempting to send
4. **Set as default SMS app** for full functionality
5. **Handle errors gracefully** with try-catch
6. **Log incoming messages** during development
7. **Validate phone numbers** before sending
8. **Request file permissions** before accessing attachments

---

## 📱 Required Permissions

Add to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.WRITE_SMS" />
<uses-permission android:name="android.permission.RECEIVE_MMS" />
<uses-permission android:name="android.permission.RECEIVE_WAP_PUSH" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
```

---

## 🐛 Common Issues

| Issue | Solution |
|-------|----------|
| Messages not sending | Check permissions & default app status |
| Can't receive messages | Initialize callbacks in main() |
| Images not attaching | Request file access permissions |
| "Not default SMS app" error | Call `requestRole(Intention.texting)` |
| Null exception | Check if MMS parts/fields exist before accessing |

---

## 📚 Learn More

- **Full Example:** See `example/lib/main.dart`
- **Simple Example:** See `example/lib/simple_example.dart`
- **Advanced MMS:** See `example/lib/advanced_mms_example.dart`
- **Conversations:** See `example/lib/conversation_example.dart`
- **Examples Guide:** See `example/EXAMPLES.md`

---

## 🚀 Next Steps

1. Copy the 30-second setup into your app
2. Run the example app to see it in action
3. Read the appropriate example file for your use case
4. Adapt the code to your specific needs
5. Test on a real device

---

**Need Help?** Check the example files or the main documentation!
