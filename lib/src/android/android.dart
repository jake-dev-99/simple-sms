import 'dart:core';
import 'package:simple_sms/src/android/models/messages/mms.dart';
import 'package:simple_sms/src/android/models/messages/sms.dart';

import './messaging/action.dart';
import './messaging/destructive_action.dart';
import './messaging/android_messaging.dart';
import 'permissions/permissions.dart';
import 'queries/android_query.dart';

export 'models/conversations/mms_sms_conversations.dart';
export 'models/conversations/mms_sms_simple_conversations.dart';
export 'models/device/device.dart';
export 'models/device/simcard.dart';
export 'models/enums/attachment_enums.dart';
export 'models/enums/contact_enums.dart';
export 'models/enums/conversation_enums.dart';
export 'models/enums/device_enums.dart';
export 'models/enums/sms_mms_enums.dart';
export 'models/messages/mms.dart';
export 'models/messages/sms.dart';
export 'models/messages/outbound_message.dart';
export 'models/messages/mms_part.dart';
export 'models/people/contact.dart';

class Android {
  Function(Mms) inboundMmsCallback;
  Function(Sms) inboundSmsCallback;
  static Android get instance => _instance!;
  static Android? _instance;

  late AndroidQuery query;
  late AndroidDestructiveAction destructiveAction;
  late AndroidAction action;
  late AndroidPermissions provisioning;
  late AndroidMessaging messaging;

  Android._internal({
    required this.inboundMmsCallback,
    required this.inboundSmsCallback,
  }) {
    query = AndroidQuery();
    destructiveAction = AndroidDestructiveAction();
    action = AndroidAction();
    provisioning = AndroidPermissions();
    messaging = AndroidMessaging.instance;
  }

  factory Android.initialize({
    required Function(Sms) inboundSmsCallback,
    required Function(Mms) inboundMmsCallback,
  }) {
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
