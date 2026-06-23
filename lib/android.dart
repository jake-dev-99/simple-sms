// Core API
export 'src/android/android.dart';
export 'src/android/messaging/android_messaging.dart';
export 'src/android/messaging/action.dart';
export 'src/android/messaging/destructive_action.dart';
// Permissions + default-SMS role management moved to simple_permissions_native;
// imports of AndroidPermissions / Intention / PermissionsEnums should switch
// to `package:simple_permissions_native/simple_permissions_native.dart`.
export 'src/android/services/lookup_service.dart';

// Models - Messages
export 'src/android/models/messages/sms.dart';
export 'src/android/models/messages/mms.dart';
export 'src/android/models/messages/mms_part.dart';
export 'src/android/models/messages/message_change_event.dart';
export 'src/android/models/messages/normalized_message.dart';
export 'src/android/models/messages/outbound_message.dart';

// Models - People
export 'src/android/models/people/message_participant.dart';
export 'src/android/models/people/contact.dart';
export 'src/android/models/people/contact_name.dart';
export 'src/android/models/people/contactables.dart';
export 'src/android/models/people/mms_participant.dart';

// Models - Conversations
export 'src/android/models/conversations/mms_sms_simple_conversations.dart';

// Models - Device
//
// NOTE: AndroidDevice and AndroidSimCard were intentionally removed from this
// plugin. Device info and SIM card enumeration are telephony concerns and
// have moved to `simple_telephony_native` (see its DeviceInfo / SimCard
// models). Consumers should import from that plugin instead.

// Models - Enums
export 'src/android/models/enums/contact_enums.dart';
export 'src/android/models/enums/sms_mms_enums.dart';

// Models - Filters (typed list-API inputs)
export 'src/android/models/filters/sort_direction.dart';
export 'src/android/models/filters/sms_filter.dart';
export 'src/android/models/filters/mms_filter.dart';
export 'src/android/models/filters/contact_filter.dart';
export 'src/android/models/filters/conversation_filter.dart';
