import 'dart:core';
import 'dart:io' show Platform;
import 'package:simple_sms/src/android/models/messages/mms.dart';
import 'package:simple_sms/src/android/models/messages/sms.dart';

import './messaging/action.dart';
import './messaging/destructive_action.dart';
import './messaging/android_messaging.dart';
import 'permissions/permissions.dart';

/// Main entry point for the simple_sms plugin.
///
/// Provides access to SMS/MMS messaging, permissions, and device actions
/// on Android. Must be initialized before use via [Android.initialize].
///
/// ```dart
/// final android = Android.initialize(
///   inboundSmsCallback: (sms) => print('SMS: ${sms.body}'),
///   inboundMmsCallback: (mms) => print('MMS: ${mms.body}'),
/// );
///
/// // Send a message
/// await android.messaging.sendMessage(
///   message: OutboundMessage(
///     body: 'Hello!',
///     addresses: {'+15551234567'},
///     attachmentPaths: null,
///   ),
/// );
/// ```
///
/// For background message handling (when the app is killed), define a
/// top-level function named `initializeApp` annotated with
/// `@pragma('vm:entry-point')` that calls [Android.initialize].
class Android {
  /// Callback invoked when an MMS message is received.
  Function(Mms) inboundMmsCallback;

  /// Callback invoked when an SMS message is received.
  Function(Sms) inboundSmsCallback;

  /// Returns the singleton instance. Throws if [initialize] has not been called.
  static Android get instance => _instance!;
  static Android? _instance;

  /// Delete messages and conversations.
  late AndroidDestructiveAction destructiveAction;

  /// Mark messages as read, send notifications, launch contacts.
  late AndroidAction action;

  /// Check and request SMS permissions and default SMS app role.
  late AndroidPermissions provisioning;

  /// Send and receive SMS/MMS messages.
  late AndroidMessaging messaging;

  Android._internal({
    required this.inboundMmsCallback,
    required this.inboundSmsCallback,
  }) {
    destructiveAction = AndroidDestructiveAction();
    action = AndroidAction();
    provisioning = AndroidPermissions();
    messaging = AndroidMessaging.instance;
  }

  /// Initializes the plugin and registers callbacks for incoming messages.
  ///
  /// Must be called before accessing [instance] or any messaging features.
  /// Can only be called on Android — throws an [AssertionError] on other platforms.
  ///
  /// The [inboundSmsCallback] is invoked for each incoming SMS message.
  /// The [inboundMmsCallback] is invoked for each incoming MMS message.
  factory Android.initialize({
    required Function(Sms) inboundSmsCallback,
    required Function(Mms) inboundMmsCallback,
  }) {
    assert(
      Platform.isAndroid,
      'simple_sms: Android.initialize() can only be called on Android. '
      'This plugin does not support iOS, web, or desktop platforms.',
    );
    AndroidMessaging.initialize(
      inboundSmsCallback: inboundSmsCallback,
      inboundMmsCallback: inboundMmsCallback,
    );
    _instance ??= Android._internal(
      inboundSmsCallback: inboundSmsCallback,
      inboundMmsCallback: inboundMmsCallback,
    );

    return _instance!;
  }
}
