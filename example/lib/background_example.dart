/// Background message handling example for simple_sms.
///
/// When your app is the default SMS app and receives a message while killed,
/// Android delivers the message via a BroadcastReceiver. The plugin creates
/// a background Flutter engine and invokes a Dart entrypoint named
/// `initializeApp` to deliver the message.
///
/// This file demonstrates the required setup for reliable message delivery
/// in all app states: foreground, background, and killed.
library;

import 'package:flutter/material.dart';
import 'package:simple_sms_native/simple_sms.dart';

// ---------------------------------------------------------------------------
// 1. Define your message handlers as top-level functions.
//    These must not reference any widget state — the background engine
//    has no UI.
// ---------------------------------------------------------------------------

void handleInboundSms(Sms sms) {
  debugPrint('Received SMS from ${sms.address}: ${sms.body}');
  // In a real app: write to local DB, trigger notification, etc.
}

void handleInboundMms(Mms mms) {
  debugPrint('Received MMS from ${mms.address}: ${mms.body}');
  debugPrint('Attachments: ${mms.parts?.length ?? 0}');
}

// ---------------------------------------------------------------------------
// 2. Define the background entrypoint.
//    This MUST be:
//    - A top-level function (not a method on a class)
//    - Named exactly "initializeApp"
//    - Annotated with @pragma('vm:entry-point')
//
//    The native Android layer calls this when a message arrives and the
//    main Flutter engine is not running.
// ---------------------------------------------------------------------------

@pragma('vm:entry-point')
void initializeApp() {
  // Initialize the messaging callbacks so incoming messages can be delivered.
  // This is the same call you make in main() — the singleton handles
  // deduplication, so calling it twice is safe.
  AndroidMessaging.initialize(
    inboundSmsCallback: handleInboundSms,
    inboundMmsCallback: handleInboundMms,
  );
}

// ---------------------------------------------------------------------------
// 3. Your normal main() also initializes messaging — this handles the
//    foreground case. The handlers are shared with the background path.
// ---------------------------------------------------------------------------

void main() {
  // Initialize messaging (same callbacks as the background entrypoint)
  Android.initialize(
    inboundSmsCallback: handleInboundSms,
    inboundMmsCallback: handleInboundMms,
  );

  runApp(const BackgroundExampleApp());
}

class BackgroundExampleApp extends StatelessWidget {
  const BackgroundExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('Background SMS Example')),
        body: const Center(
          child: Padding(
            padding: EdgeInsets.all(24),
            child: Text(
              'Messages will be received even when this app is killed.\n\n'
              'Check logcat for incoming message logs.',
              textAlign: TextAlign.center,
            ),
          ),
        ),
      ),
    );
  }
}
