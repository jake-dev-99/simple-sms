# Simple SMS Example App

A comprehensive example application demonstrating MMS and SMS functionality using the `simple_sms` plugin.

## Features

This example app demonstrates:

### 📨 Sending Messages
- **Send SMS**: Send simple text messages
- **Send MMS with Image**: Send multimedia messages with a single image attachment
- **Send MMS with Multiple Attachments**: Send messages with multiple image attachments

### 📬 Receiving Messages
- **Receive SMS**: Handle incoming text messages
- **Receive MMS**: Handle incoming multimedia messages with attachments
- Real-time logging of all incoming messages and their details

### 🔐 Permissions Management
- Request and verify SMS/MMS permissions
- Set app as default SMS handler
- Request file access permissions for attachments

### 📊 Activity Monitoring
- Live activity log showing all operations
- Message sending/receiving status
- Detailed MMS part information (content types, filenames, etc.)

## Getting Started

### Prerequisites

1. **Android Device or Emulator**: This plugin only works on Android
2. **Flutter SDK**: Make sure you have Flutter installed
3. **Permissions**: The app will guide you through granting necessary permissions

### Installation

1. Navigate to the example directory:
```bash
cd example
```

2. Get dependencies:
```bash
flutter pub get
```

3. Run the app:
```bash
flutter run
```

## Usage

### First Launch

When you first launch the app, you'll need to:

1. **Grant Permissions**: Tap "Grant Permissions" to allow the app to access SMS/MMS
2. **Set as Default SMS App**: Tap "Set as Default SMS App" to enable full functionality

### Sending an SMS

1. Tap the **"Send SMS"** button (blue)
2. Enter the recipient's phone number (e.g., `+1234567890`)
3. Type your message
4. Tap **"Send"**

### Sending an MMS with Image

1. Tap the **"Send MMS"** button (green)
2. Enter the recipient's phone number
3. Optionally add a subject
4. Type your message
5. Tap **"Pick Image & Send"**
6. Select an image from your gallery
7. The MMS will be sent with the image attached

### Sending MMS with Multiple Attachments

1. Tap the **"Send MMS (Multiple Attachments)"** button (purple)
2. Enter the recipient's phone number
3. Type your message
4. Tap **"Pick Files & Send"**
5. Select multiple images from your gallery
6. All selected images will be attached to the MMS

### Monitoring Activity

The **Activity Log** at the bottom of the screen shows:
- Permission status changes
- Message sending attempts and results
- Incoming SMS/MMS messages
- Detailed information about MMS parts and attachments

You can clear the log at any time by tapping the clear icon (🗑️).

## Code Structure

### Main Components

- **`handleInboundMms(Mms mms)`**: Callback for incoming MMS messages
- **`handleInboundSms(Sms sms)`**: Callback for incoming SMS messages
- **Permission Management**: Methods for checking and requesting permissions
- **Send Methods**: Different methods for sending SMS and MMS with various attachment options

## Example Files

The example includes several files demonstrating different use cases:

### 1. `main.dart` - Full-Featured Interactive Example
The complete example app with a full UI showing all features. This is the default app that runs when you execute `flutter run`.

**Features:**
- Permission management UI
- Send SMS/MMS with dialog inputs
- Activity logging
- Real-time status display
- Multiple attachment handling

### 2. `simple_example.dart` - Quick Start Guide
A minimal example showing the core functionality without UI complexity. Perfect for quick reference or copying into your own app.

**Topics covered:**
- Basic initialization
- Requesting permissions
- Sending SMS
- Sending MMS with attachments
- Receiving messages

### 3. `advanced_mms_example.dart` - Advanced MMS Handling
Demonstrates advanced MMS processing and content type handling.

**Topics covered:**
- Processing different content types (images, video, audio, text)
- Extracting and displaying MMS parts
- Handling SMIL presentations
- Working with MMS metadata
- Creating custom MMS display widgets
- Sending mixed media (images + videos + audio)

### 4. `conversation_example.dart` - Thread Management
Shows how to work with conversation threads for building messaging UIs.

**Topics covered:**
- Sending messages to existing threads
- Organizing messages by thread ID
- Building conversation list UI
- Creating message bubble UI
- Thread-based message management

### Key Features in Code

```dart
// Initialize messaging with callbacks
AndroidMessaging.initialize(
  inboundMmsCallback: handleInboundMms,
  inboundSmsCallback: handleInboundSms,
);

// Send a simple SMS
final outboundMessage = OutboundMessage(
  body: "Hello!",
  addresses: {"+1234567890"},
  attachmentPaths: null,
);
await AndroidMessaging.instance.sendMessage(message: outboundMessage);

// Send MMS with attachments
final outboundMessage = OutboundMessage(
  body: "Check out this image!",
  addresses: {"+1234567890"},
  attachmentPaths: {"/path/to/image.jpg"},
);
await AndroidMessaging.instance.sendMessage(message: outboundMessage);
```

## Permissions Required

The app requires the following Android permissions:

- `android.permission.SEND_SMS`
- `android.permission.READ_SMS`
- `android.permission.RECEIVE_SMS`
- `android.permission.WRITE_SMS`
- `android.permission.RECEIVE_WAP_PUSH`
- `android.permission.RECEIVE_MMS`
- `android.permission.READ_EXTERNAL_STORAGE`
- `android.permission.READ_MEDIA_IMAGES`
- `android.permission.READ_MEDIA_VIDEO`
- `android.permission.READ_MEDIA_AUDIO`

And the following role:
- `android.app.role.SMS` (Default SMS App)

## Troubleshooting

### Messages Not Sending

1. Ensure you've granted all required permissions
2. Verify you've set the app as the default SMS app
3. Check the Activity Log for error messages
4. Make sure you're using a valid phone number format

### Can't Select Images

1. Grant file access permissions
2. Ensure you have images in your gallery
3. Check Android storage permissions

### Not Receiving Messages

1. Set the app as the default SMS app
2. Make sure callbacks are properly initialized in `main()`
3. Check the Activity Log for incoming message notifications

## API Reference

### OutboundMessage

```dart
OutboundMessage({
  required String body,              // Message text
  required Set<String> addresses,    // Recipient phone numbers
  Set<String>? attachmentPaths,     // File paths for attachments (optional)
  String? conversationId,           // Thread/conversation ID (optional)
})
```

### Mms Model

The `Mms` class contains comprehensive information about MMS messages:
- `id`: Message ID
- `address`: Sender/recipient address
- `subject`: Message subject
- `body`: Text content
- `parts`: List of MmsPart objects (attachments)
- `recipients`: List of message recipients
- `sender`: Message sender information
- And many more fields...

### MmsPart Model

Each MMS attachment is represented as an `MmsPart`:
- `contentType`: MIME type (image/jpeg, text/plain, etc.)
- `fileName`: Attachment filename
- `text`: Text content (for text parts)
- `dataLocation`: URI to the attachment data
- `isText`: Whether this part contains text
- `isSmil`: Whether this is a SMIL presentation part

## Testing

To test the app:

1. **Send Messages**: Use a real phone number or a testing service
2. **Receive Messages**: Send SMS/MMS to your device from another phone
3. **Check Logs**: Monitor the Activity Log for detailed information

## Example Use Cases

### Building a Messaging App

Use `conversation_example.dart` as a reference for:
- Creating a conversation list
- Displaying message threads
- Building message bubbles
- Managing thread-based messaging

### Processing MMS Attachments

Use `advanced_mms_example.dart` to:
- Extract images, videos, and audio from MMS
- Display different content types
- Handle SMIL presentations
- Access detailed MMS metadata

### Simple Integration

Use `simple_example.dart` for:
- Quick copy-paste integration
- Understanding core concepts
- Minimal working examples

## Learn More

For more information about the `simple_sms` plugin, see the main package documentation.

## License

This example app is part of the `simple_sms` package.
