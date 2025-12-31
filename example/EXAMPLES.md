# Simple SMS Example Files Guide

This directory contains multiple example files demonstrating different aspects of the `simple_sms` plugin. Choose the example that best fits your use case.

## 📱 Quick Reference

| File | Best For | Complexity | Key Features |
|------|----------|------------|--------------|
| `main.dart` | Production reference | ⭐⭐⭐ | Full UI, permissions, logging |
| `simple_example.dart` | Quick start | ⭐ | Minimal code, easy to understand |
| `advanced_mms_example.dart` | MMS processing | ⭐⭐⭐ | Content types, attachments |
| `conversation_example.dart` | Messaging apps | ⭐⭐⭐ | Threads, chat UI |

---

## 📄 File Details

### 1. `main.dart` - Full-Featured Example App

**When to use:** Building a production app or need a complete reference

**What it shows:**
- ✅ Complete permission flow (request, check, handle)
- ✅ Interactive UI with buttons and dialogs
- ✅ Activity logging for debugging
- ✅ Send SMS with user input
- ✅ Send MMS with image picker
- ✅ Send MMS with multiple attachments
- ✅ Real-time status updates
- ✅ Incoming message handling

**Code highlights:**
```dart
// Initialize with callbacks
AndroidMessaging.initialize(
  inboundMmsCallback: handleInboundMms,
  inboundSmsCallback: handleInboundSms,
);

// Request permissions
await AndroidPermissions.requestPermissions(Intention.texting);

// Send MMS with image
final message = OutboundMessage(
  body: 'Check this out!',
  addresses: {'+1234567890'},
  attachmentPaths: {imagePath},
);
```

**Try it:**
```bash
flutter run
```

---

### 2. `simple_example.dart` - Minimal Quick Start

**When to use:** Learning the basics or integrating into existing app

**What it shows:**
- ✅ Minimal initialization code
- ✅ Basic SMS sending
- ✅ Basic MMS with attachments
- ✅ Permission requests
- ✅ Simple callbacks
- ✅ Clean, readable code

**Code highlights:**
```dart
// Step 1: Initialize
void initializeMessaging() {
  AndroidMessaging.initialize(
    inboundMmsCallback: (Mms mms) {
      debugPrint('Received MMS from ${mms.address}');
    },
    inboundSmsCallback: (Sms sms) {
      debugPrint('Received SMS from ${sms.address}');
    },
  );
}

// Step 2: Send SMS
Future<void> sendSimpleSms() async {
  final message = OutboundMessage(
    body: 'Hello!',
    addresses: {'+1234567890'},
    attachmentPaths: null,
  );
  await AndroidMessaging.instance.sendMessage(message: message);
}
```

**Perfect for:**
- Copy-paste integration
- Understanding core concepts
- Quick prototypes

---

### 3. `advanced_mms_example.dart` - Advanced MMS Processing

**When to use:** Need to work with MMS attachments and content

**What it shows:**
- ✅ Parse MMS parts (images, video, audio, text)
- ✅ Extract specific content types
- ✅ Handle SMIL presentations
- ✅ Access comprehensive MMS metadata
- ✅ Display MMS in custom widgets
- ✅ Send mixed media messages

**Code highlights:**
```dart
// Extract images from MMS
List<MmsPart> extractImages(Mms mms) {
  return mms.parts!.where((part) {
    return part.contentType.value.toLowerCase().contains('image/');
  }).toList();
}

// Process incoming MMS comprehensively
void processInboundMms(Mms mms) {
  debugPrint('From: ${mms.address}');
  debugPrint('Subject: ${mms.subject}');

  for (var part in mms.parts!) {
    if (part.isText) {
      debugPrint('Text: ${part.text}');
    } else if (part.contentType.value.contains('image')) {
      debugPrint('Image: ${part.fileName}');
    }
  }
}

// Send MMS with mixed media
await AdvancedMmsSender.sendMixedMediaMms(
  recipients: {'+1234567890'},
  message: 'Mixed media!',
  imagePaths: ['/path/to/image.jpg'],
  videoPaths: ['/path/to/video.mp4'],
  audioPaths: ['/path/to/audio.mp3'],
);
```

**Perfect for:**
- Media-heavy messaging apps
- Content management systems
- File sharing features
- Understanding MMS structure

---

### 4. `conversation_example.dart` - Thread Management

**When to use:** Building a messaging app with conversations

**What it shows:**
- ✅ Organize messages by thread ID
- ✅ Conversation list UI
- ✅ Message bubbles (incoming/outgoing)
- ✅ Thread-based message sending
- ✅ Real-time conversation updates
- ✅ Message timestamps

**Code highlights:**
```dart
// Send to existing thread
await sendMessageToThread(
  conversationId: '12345',
  message: 'Reply to this thread',
);

// Manage threads
class MessageThreadManager {
  final Map<int, List<dynamic>> _threads = {};

  void addSms(Sms sms) {
    _threads.putIfAbsent(sms.threadId, () => []);
    _threads[sms.threadId]!.add(sms);
  }

  List<dynamic> getThread(int threadId) {
    return _threads[threadId] ?? [];
  }
}

// Display conversation list
ConversationListScreen()
  └─ ConversationListItem (for each thread)
      └─ ConversationScreen (message thread)
          └─ MessageBubble (for each message)
```

**Perfect for:**
- Messaging apps
- Customer support systems
- Chat interfaces
- Thread-based communication

---

## 🚀 Getting Started

### Option 1: Run the Full Example
```bash
cd example
flutter pub get
flutter run
```

### Option 2: Copy Simple Example
1. Open `lib/simple_example.dart`
2. Copy the initialization and send functions
3. Paste into your app
4. Replace phone numbers with real values

### Option 3: Study Advanced Examples
1. Review the example that matches your use case
2. Understand the patterns and APIs
3. Adapt to your specific needs

---

## 📚 Common Patterns

### Initialization (All Examples)
```dart
void main() {
  AndroidMessaging.initialize(
    inboundMmsCallback: handleInboundMms,
    inboundSmsCallback: handleInboundSms,
  );
  runApp(MyApp());
}
```

### Permissions (All Examples)
```dart
// Check permissions
final hasPerms = await AndroidPermissions.checkPermissions(Intention.texting);

// Request permissions
await AndroidPermissions.requestPermissions(Intention.texting);

// Request default SMS role
await AndroidPermissions.requestRole(Intention.texting);
```

### Sending SMS (Simple)
```dart
final message = OutboundMessage(
  body: 'Hello!',
  addresses: {'+1234567890'},
  attachmentPaths: null,
);
await AndroidMessaging.instance.sendMessage(message: message);
```

### Sending MMS (With Attachments)
```dart
final message = OutboundMessage(
  body: 'Check this out!',
  addresses: {'+1234567890'},
  attachmentPaths: {'/path/to/image.jpg'},
);
await AndroidMessaging.instance.sendMessage(message: message);
```

### Receiving Messages
```dart
void handleInboundMms(Mms mms) {
  debugPrint('MMS from ${mms.address}: ${mms.body}');
  // Process mms.parts for attachments
}

void handleInboundSms(Sms sms) {
  debugPrint('SMS from ${sms.address}: ${sms.body}');
}
```

---

## 🎯 Choose Your Path

### "I just want to send an SMS"
→ Use `simple_example.dart`
→ Focus on `sendSimpleSms()` function

### "I need to send images via MMS"
→ Use `main.dart` or `simple_example.dart`
→ Focus on `sendMmsWithImage()` function

### "I'm building a messaging app"
→ Use `conversation_example.dart`
→ Study the thread management patterns

### "I need to handle complex MMS content"
→ Use `advanced_mms_example.dart`
→ Study content extraction functions

### "I want a complete reference"
→ Use `main.dart`
→ Run it and explore all features

---

## 💡 Tips

1. **Start Simple:** Begin with `simple_example.dart` to understand basics
2. **Check Permissions:** Always verify permissions before sending
3. **Handle Errors:** Wrap API calls in try-catch blocks
4. **Test Incrementally:** Test SMS before MMS, single attachments before multiple
5. **Use Debug Prints:** Log incoming messages to understand structure
6. **Read the Models:** Study `Mms` and `Sms` classes to see all available fields

---

## 🔗 Related Documentation

- [Main README](../README.md) - Plugin overview
- [Example README](README.md) - Running the example
- [API Documentation](https://pub.dev/documentation/simple_sms/latest/) - Full API reference

---

## 🐛 Troubleshooting

### "Messages aren't sending"
1. Check if you have permissions (see `main.dart` permission flow)
2. Verify you're the default SMS app
3. Check phone number format (+1234567890)
4. Review logs for error messages

### "Can't receive messages"
1. Ensure you set callbacks in `main()` before `runApp()`
2. Set app as default SMS handler
3. Check callback functions are being called

### "Image attachments not working"
1. Request file access permissions (`Intention.fileAccess`)
2. Verify file paths are correct and accessible
3. Check file format is supported (JPEG, PNG, etc.)

### "Need more help"
- Check the full example in `main.dart` for working code
- Review error messages in the Activity Log
- See main plugin documentation

---

## 📝 License

All examples are part of the `simple_sms` package and follow the same license.
