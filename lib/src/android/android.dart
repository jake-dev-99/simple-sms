import 'dart:core';
import 'dart:io' show Platform;
import 'package:simple_sms_native/src/android/models/messages/mms.dart';
import 'package:simple_sms_native/src/android/models/messages/sms.dart';

import './messaging/android_messaging.dart';

/// Main entry point for the simple_sms plugin.
///
/// Provides access to SMS/MMS messaging and device actions on Android.
/// Must be initialized before use via [Android.initialize].
///
/// ## Access-state model
///
/// Runtime permissions **and** the default-SMS-app role live in
/// `simple_permissions_native` — this plugin assumes the caller has
/// already granted whatever the specific operation needs and will
/// surface `SecurityException` / silent empty results otherwise.
///
/// The relevant vocabulary:
///
/// | Operation | Needs |
/// | --- | --- |
/// | Read conversations / threads / messages | `ReadSms` permission (`READ_SMS`) |
/// | Receive inbound SMS via the native broadcast | `ReceiveSms` permission (`RECEIVE_SMS`) |
/// | Receive inbound MMS / write to the Telephony store | `DefaultSmsApp` role (`RoleManager.ROLE_SMS`) — only the default SMS app can write the provider |
/// | Send SMS | `SendSms` permission (`SEND_SMS`); marking a sent message as delivered also needs `DefaultSmsApp` |
///
/// Check / request via the unified permissions API:
///
/// ```dart
/// import 'package:simple_permissions_native/simple_permissions_native.dart';
///
/// // One-off request.
/// await SimplePermissionsNative.instance
///     .request(const DefaultSmsApp());
///
/// // Reactive observation — gate your UI on the role being held.
/// final observer = SimplePermissionsNative.instance.observe(const [
///   DefaultSmsApp(),
///   ReceiveSms(),
///   SendSms(),
/// ]);
/// observer.stream.listen((result) {
///   final canSend = result.isFullyGranted;
///   // enable compose, show "grant to continue" banner, etc.
/// });
/// ```
///
/// ## Usage
///
/// ```dart
/// final android = Android.initialize(
///   inboundSmsCallback: (sms) => print('SMS: ${sms.body}'),
///   inboundMmsCallback: (mms) => print('MMS: ${mms.body}'),
/// );
///
/// // Send a message — requires SendSms + DefaultSmsApp at runtime.
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
  ///
  /// Reassigning this updates the active inbound handler on the
  /// underlying [AndroidMessaging] singleton in place. The previous
  /// implementation stored a second copy on this facade that was never
  /// consulted — reassigning it had no effect.
  Function(Mms) get inboundMmsCallback => messaging.mmsCallback;
  set inboundMmsCallback(Function(Mms) value) => messaging.mmsCallback = value;

  /// Callback invoked when an SMS message is received.
  ///
  /// See [inboundMmsCallback] for the delegation rationale.
  Function(Sms) get inboundSmsCallback => messaging.smsCallback;
  set inboundSmsCallback(Function(Sms) value) => messaging.smsCallback = value;

  /// Returns the singleton instance. Throws if [initialize] has not been called.
  static Android get instance => _instance!;
  static Android? _instance;

  /// Send and receive SMS/MMS messages.
  late AndroidMessaging messaging;

  Android._internal() {
    messaging = AndroidMessaging.instance;
  }

  /// Initializes the plugin and registers callbacks for incoming messages.
  ///
  /// Must be called before accessing [instance] or any messaging features.
  /// Can only be called on Android — throws an [AssertionError] on other platforms.
  ///
  /// The [inboundSmsCallback] is invoked for each incoming SMS message.
  /// The [inboundMmsCallback] is invoked for each incoming MMS message.
  ///
  /// Safe to call multiple times: subsequent calls replace the active
  /// callbacks on the existing singleton. Useful for hot-reload flows and
  /// for background-engine entrypoints that re-initialise after the
  /// foreground engine has already registered callbacks.
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
    return _instance ??= Android._internal();
  }
}
