/// A minimal example showing the core functionality of simple_sms
///
/// This file demonstrates the basic usage without all the UI complexity.
/// Perfect for quick reference or copying into your own app.
library;

import 'package:flutter/material.dart';
import 'package:simple_sms/simple_sms.dart';

/// STEP 1: Initialize messaging with callbacks
///
/// Call this in your main() function before runApp()
void initializeMessaging() {
  AndroidMessaging.initialize(
    inboundMmsCallback: (Mms mms) {
      debugPrint('📨 Received MMS from ${mms.address}');
      debugPrint('   Subject: ${mms.subject}');
      debugPrint('   Body: ${mms.body}');
      debugPrint('   Attachments: ${mms.parts?.length ?? 0}');
    },
    inboundSmsCallback: (Sms sms) {
      debugPrint('💬 Received SMS from ${sms.address}');
      debugPrint('   Body: ${sms.body}');
    },
  );
}

/// STEP 2: Request permissions
///
/// Before you can send/receive messages, you need permissions
Future<void> requestPermissions() async {
  // Request SMS/MMS permissions
  await AndroidPermissions.requestPermissions(Intention.texting);

  // Request file access for MMS attachments
  await AndroidPermissions.requestPermissions(Intention.fileAccess);

  // Set as default SMS app (required for full functionality)
  await AndroidPermissions.requestRole(Intention.texting);
}

/// STEP 3: Send a simple SMS
Future<void> sendSimpleSms() async {
  final message = OutboundMessage(
    body: 'Hello from simple_sms!',
    addresses: {'+1234567890'}, // Replace with actual phone number
    attachmentPaths: null, // No attachments for SMS
  );

  try {
    final result = await AndroidMessaging.instance.sendMessage(
      message: message,
    );
    debugPrint('✅ SMS sent: $result');
  } catch (e) {
    debugPrint('❌ Failed to send SMS: $e');
  }
}

/// STEP 4: Send an MMS with image attachment
Future<void> sendMmsWithImage() async {
  final message = OutboundMessage(
    body: 'Check out this image!',
    addresses: {'+1234567890'}, // Replace with actual phone number
    attachmentPaths: {
      '/path/to/image.jpg', // Replace with actual file path
    },
  );

  try {
    final result = await AndroidMessaging.instance.sendMessage(
      message: message,
    );
    debugPrint('✅ MMS sent: $result');
  } catch (e) {
    debugPrint('❌ Failed to send MMS: $e');
  }
}

/// STEP 5: Send MMS to multiple recipients with multiple attachments
Future<void> sendGroupMms() async {
  final message = OutboundMessage(
    body: 'Group message with multiple attachments!',
    addresses: {'+1234567890', '+0987654321'},
    attachmentPaths: {
      '/path/to/image1.jpg',
      '/path/to/image2.jpg',
      '/path/to/video.mp4',
    },
  );

  try {
    final result = await AndroidMessaging.instance.sendMessage(
      message: message,
    );
    debugPrint('✅ Group MMS sent: $result');
  } catch (e) {
    debugPrint('❌ Failed to send MMS: $e');
  }
}

/// OPTIONAL: Check if you have permissions before attempting operations
Future<bool> checkIfReady() async {
  // Check if default SMS app
  final isDefaultApp = await AndroidPermissions.checkRole(Intention.texting);

  // Check if we have all required permissions
  final permissions = await AndroidPermissions.checkPermissions(
    Intention.texting,
  );
  final hasAllPermissions = permissions.values.every((granted) => granted);

  debugPrint('Default SMS App: $isDefaultApp');
  debugPrint('Has All Permissions: $hasAllPermissions');

  return isDefaultApp && hasAllPermissions;
}

/// Example usage in a Flutter app
void main() {
  // Initialize before running the app
  initializeMessaging();

  runApp(const MySimpleApp());
}

class MySimpleApp extends StatelessWidget {
  const MySimpleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('Simple SMS Example')),
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              ElevatedButton(
                onPressed: requestPermissions,
                child: const Text('Request Permissions'),
              ),
              const SizedBox(height: 16),
              ElevatedButton(
                onPressed: sendSimpleSms,
                child: const Text('Send SMS'),
              ),
              const SizedBox(height: 16),
              ElevatedButton(
                onPressed: sendMmsWithImage,
                child: const Text('Send MMS'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
