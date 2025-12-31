# simple_sms Project Whitepaper

**Version:** 0.0.1
**Date:** December 2025
**Author:** Jake (SimplZen.io)

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Problem Statement](#problem-statement)
3. [Solution Architecture](#solution-architecture)
4. [Technical Requirements](#technical-requirements)
5. [Design Decisions](#design-decisions)
6. [Implementation Details](#implementation-details)
7. [Data Models](#data-models)
8. [Platform Channel Architecture](#platform-channel-architecture)
9. [MMS Subsystem](#mms-subsystem)
10. [Security & Permissions](#security--permissions)
11. [Deployment & Distribution](#deployment--distribution)
12. [Future Roadmap](#future-roadmap)
13. [Appendices](#appendices)

---

## 1. Executive Summary

`simple_sms` is a comprehensive Flutter plugin that provides native Android SMS and MMS messaging capabilities to Flutter applications. The plugin bridges Flutter's Dart runtime with Android's native Telephony APIs through platform channels, enabling developers to build full-featured messaging applications.

### Key Capabilities

- **Send/Receive SMS**: Full SMS messaging with delivery status tracking
- **Send/Receive MMS**: Multimedia messaging with support for images, video, audio, and multi-part attachments
- **Conversation Management**: Query and manage message threads
- **Contact Integration**: Link messages to device contacts
- **Device Information**: Access SIM card and device telephony information
- **Default SMS App Support**: Full functionality when set as the device's default messaging app

### Target Use Cases

1. Custom messaging applications
2. Business SMS/MMS automation
3. Messaging clients with custom UI/UX
4. SMS-based authentication and notification systems
5. Unified communication platforms

---

## 2. Problem Statement

### The Challenge

Building SMS/MMS functionality in Flutter applications presents significant challenges:

1. **No Native Flutter Support**: Flutter has no built-in SMS/MMS APIs
2. **Android Complexity**: Android's Telephony API is fragmented across versions and OEM implementations
3. **MMS Protocol Complexity**: MMS involves WAP Push, PDU parsing, carrier-specific configurations, and multi-table database operations
4. **Permission Complexity**: SMS apps require special roles and runtime permissions
5. **Existing Solutions Fall Short**: Current Flutter SMS plugins typically only support basic SMS sending, lacking MMS, receiving, and comprehensive querying

### Market Gap

| Feature | Existing Plugins | simple_sms |
|---------|-----------------|------------|
| Send SMS | ✅ | ✅ |
| Receive SMS | Limited | ✅ |
| Send MMS | ❌ | ✅ |
| Receive MMS | ❌ | ✅ |
| MMS Attachments | ❌ | ✅ |
| Conversation Queries | Limited | ✅ |
| Default SMS App | ❌ | ✅ |
| Contact Integration | ❌ | ✅ |

---

## 3. Solution Architecture

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Flutter Application                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────┐ │
│  │ AndroidMessaging │  │  AndroidQuery    │  │ AndroidPermissions│
│  │    Singleton     │  │   Static API     │  │   Static API   │ │
│  └────────┬─────────┘  └────────┬─────────┘  └───────┬────────┘ │
│           │                     │                     │          │
│  ┌────────┴─────────────────────┴─────────────────────┴────────┐ │
│  │                     Interop Layer                            │ │
│  │  (MessagingInterop, QueryInterop, PermissionsInterop)       │ │
│  └──────────────────────────┬───────────────────────────────────┘ │
│                             │                                    │
└─────────────────────────────┼────────────────────────────────────┘
                              │ Platform Channels (MethodChannel)
┌─────────────────────────────┼────────────────────────────────────┐
│                             ▼                                    │
│  ┌──────────────────────────────────────────────────────────────┐│
│  │                   SimpleSmsPlugin.kt                         ││
│  │              (FlutterPlugin, ActivityAware)                  ││
│  └──────────────────────────┬───────────────────────────────────┘│
│                             │                                    │
│  ┌──────────┬───────────────┼───────────────┬──────────────────┐ │
│  ▼          ▼               ▼               ▼                  ▼ │
│ Query   Messaging    Permissions      Actions         Device    │
│Handler   Handler       Handler        Handler         Handler   │
│  │          │               │               │              │    │
│  └──────────┴───────────────┴───────────────┴──────────────┘    │
│                             │                                    │
│  ┌──────────────────────────┴───────────────────────────────────┐│
│  │              Android Telephony APIs                          ││
│  │    (ContentResolver, SmsManager, Telephony.Mms/Sms)         ││
│  └──────────────────────────────────────────────────────────────┘│
│                                                                  │
│  ┌──────────────────────────────────────────────────────────────┐│
│  │           Vendored Libraries (google_*)                      ││
│  │  • klinker/android-smsmms (MMS sending)                     ││
│  │  • Google MMS PDU parsers (com.google.android.mms.pdu_alt)  ││
│  │  • libphonenumber (phone formatting)                        ││
│  └──────────────────────────────────────────────────────────────┘│
│                         Android Native Layer                     │
└──────────────────────────────────────────────────────────────────┘
```

### Directory Structure

```
simple_sms/
├── lib/                              # Dart/Flutter code
│   ├── android.dart                  # Public API exports
│   ├── simple_sms.dart               # Extended exports (actions, permissions)
│   └── src/
│       ├── android/
│       │   ├── messaging/            # AndroidMessaging singleton
│       │   ├── models/               # Data models (Sms, Mms, etc.)
│       │   ├── permissions/          # Permission handling
│       │   └── queries/              # Query builders
│       ├── interfaces/               # Abstract interfaces
│       └── interop/                  # Platform channel wrappers
│
├── android/                          # Android native code
│   ├── src/main/kotlin/.../simple_sms/
│   │   ├── SimpleSmsPlugin.kt        # Plugin entry point
│   │   ├── messaging/                # Send/receive handlers
│   │   ├── models/                   # Kotlin data classes
│   │   ├── queries/                  # ContentResolver queries
│   │   └── device/                   # Permissions, device info
│   │
│   └── google_*/                     # Vendored AOSP libraries
│       ├── google_apps_messaging_core/  # Klinker SMS/MMS library
│       ├── google_i18n_libphonenumber/  # Phone number formatting
│       └── ...                          # Additional support libs
│
└── example/                          # Example application
    └── lib/main.dart                 # Full-featured demo
```

---

## 4. Technical Requirements

### Development Environment

| Requirement | Specification |
|-------------|---------------|
| Flutter SDK | ≥ 3.3.0 |
| Dart SDK | ^3.7.0 |
| Android Studio | Latest stable |
| Kotlin | 2.1.20 |
| Gradle | 8.9.2 |
| Android Build Tools | 36.0.0 |
| NDK Version | 28.0.13004108 |

### Android Target Configuration

| Setting | Value | Rationale |
|---------|-------|-----------|
| minSdk | 30 (Android 11) | Required for modern SMS APIs and scoped storage |
| compileSdk | 35 (Android 15) | Latest API access |
| targetSdk | 35 | Current Android recommendations |
| Java Version | 17 | Kotlin/Android compatibility |

### Required Android Permissions

```xml
<!-- Core Messaging -->
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.RECEIVE_WAP_PUSH" />
<uses-permission android:name="android.permission.RECEIVE_MMS" />

<!-- Phone/Device -->
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.READ_PHONE_NUMBERS" />
<uses-permission android:name="android.permission.CALL_PHONE" />

<!-- Contacts -->
<uses-permission android:name="android.permission.READ_CONTACTS" />
<uses-permission android:name="android.permission.WRITE_CONTACTS" />

<!-- Network (MMS) -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />

<!-- Storage (Attachments) -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
```

### Required Android Features

```xml
<uses-feature android:name="android.hardware.telephony" android:required="true" />
<uses-feature android:name="android.hardware.sms" android:required="true" />
```

### Role Requirements

For full functionality (especially MMS receiving), the app must be set as the **default SMS application**:

```dart
// Request default SMS app role
await AndroidPermissions.requestRole(Intention.texting);
// Role: android.app.role.SMS
```

---

## 5. Design Decisions

### 5.1 Platform Channel Strategy

**Decision:** Use multiple dedicated MethodChannels instead of a single multiplexed channel.

**Rationale:**
- Clear separation of concerns
- Independent handler implementations
- Easier debugging and maintenance
- Parallel development capability

**Channels Implemented:**

| Channel Name | Purpose | Handler |
|--------------|---------|---------|
| `io.simplezen.simple_sms/messaging` | Send SMS/MMS | OutboundMessagingHandler |
| `io.simplezen.simple_sms/inbound_messaging` | Receive messages | InboundMessaging |
| `io.simplezen.simple_sms/query` | Database queries | Query |
| `io.simplezen.simple_sms/permissions` | Permission management | PermissionsHandler |
| `io.simplezen.simple_sms/actions` | Device actions | DeviceActions |
| `io.simplezen.simple_sms/destructive_actions` | Delete operations | DestructiveActions |

### 5.2 Vendored Dependencies

**Decision:** Vendor Google/AOSP messaging libraries rather than use Maven dependencies.

**Rationale:**
- **Stability**: AOSP MMS code is no longer actively published to Maven
- **Customization**: Ability to patch for specific carrier/device issues
- **Conflict Avoidance**: Prevents version conflicts with host applications
- **Completeness**: Full source access for debugging

**Vendored Projects:**

| Project | Source | Purpose |
|---------|--------|---------|
| `google_apps_messaging_core` | AOSP + klinker | MMS sending via Transaction/Message classes |
| `google_i18n_libphonenumber` | Google | Phone number parsing and formatting |
| `google_chips` | AOSP | UI components (dependency) |
| `google_photoviewer` | AOSP | Media handling (dependency) |
| `google_vcard` | AOSP | Contact vCard support |
| `google_ex` | AOSP | Extended utilities |

### 5.3 Model Serialization Pattern

**Decision:** Use JSON serialization for all platform channel data transfer.

**Implementation:**
```dart
// Dart: All models implement ModelInterface
abstract interface class ModelInterface {
  final int? id;
  final Map<String, dynamic>? sourceMap;

  factory ModelInterface.fromJson(Map<String, dynamic> json);
  Map<String, dynamic> toJson();
}
```

```kotlin
// Kotlin: Custom AnySerializer for flexible JSON handling
object AnySerializer : KSerializer<Any?> {
    fun encodeToString(value: Any?): String
    fun decodeFromString(value: String): Any?
}
```

**Rationale:**
- Universal compatibility between Dart and Kotlin
- Human-readable debugging
- Flexible schema evolution
- Support for nullable and optional fields

### 5.4 Singleton Pattern for Messaging

**Decision:** AndroidMessaging uses singleton pattern with explicit initialization.

```dart
// Required in main() before any message handling
AndroidMessaging.initialize(
  inboundSmsCallback: handleSms,
  inboundMmsCallback: handleMms,
);

// Access via singleton
await AndroidMessaging.instance.sendMessage(message: outbound);
```

**Rationale:**
- Ensures callbacks are registered before any messages arrive
- Single source of truth for message handling
- Prevents duplicate registrations
- Clear lifecycle management

---

## 6. Implementation Details

### 6.1 Outbound Message Flow

#### SMS Sending Flow

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────────┐
│  Dart Layer     │     │  Platform Channel │     │  Android Native     │
├─────────────────┤     ├──────────────────┤     ├─────────────────────┤
│                 │     │                  │     │                     │
│ OutboundMessage │────▶│ MessagingInterop │────▶│ OutboundMessaging   │
│   .toJson()     │     │  .sendMessage()  │     │    Handler          │
│                 │     │                  │     │         │           │
└─────────────────┘     └──────────────────┘     │         ▼           │
                                                 │ MmsDatabaseWriter   │
                                                 │   .insertSms()      │
                                                 │         │           │
                                                 │         ▼           │
                                                 │   SmsManager        │
                                                 │   .sendTextMessage()│
                                                 │         │           │
                                                 │         ▼           │
                                                 │ BroadcastReceiver   │
                                                 │ (SENT confirmation) │
                                                 │         │           │
                                                 │         ▼           │
                                                 │ Update DB status    │
                                                 │ Return result JSON  │
                                                 └─────────────────────┘
```

#### MMS Sending Flow

```
┌─────────────────┐     ┌─────────────────────────────────────────────┐
│ OutboundMessage │     │              Android Native                  │
│ with attachments│────▶├─────────────────────────────────────────────┤
└─────────────────┘     │                                             │
                        │  1. Get/Create Thread ID                    │
                        │     Telephony.Threads.getOrCreateThreadId() │
                        │                           │                 │
                        │                           ▼                 │
                        │  2. Insert MMS to Database                  │
                        │     MmsDatabaseWriter.insertMms()           │
                        │     - Insert to Mms.Inbox.CONTENT_URI       │
                        │     - Insert addresses (MmsAddr)            │
                        │     - Insert parts (MmsPart)                │
                        │                           │                 │
                        │                           ▼                 │
                        │  3. Send via Klinker Library                │
                        │     Transaction(context, settings)          │
                        │     .sendNewMessage(message, threadId)      │
                        │                           │                 │
                        │                           ▼                 │
                        │  4. BroadcastReceiver handles result        │
                        │     OutboundMessagingReceiver               │
                        │     - Update status in database             │
                        │     - Query final message state             │
                        │     - Return complete MMS JSON              │
                        └─────────────────────────────────────────────┘
```

### 6.2 Inbound Message Flow

#### SMS Receiving

```
┌────────────────────┐     ┌────────────────────────────────────────┐
│ Android System     │     │            Plugin Layer                 │
│ SMS_DELIVER Intent │────▶├────────────────────────────────────────┤
└────────────────────┘     │                                        │
                           │  InboundSmsHandler (BroadcastReceiver) │
                           │           │                            │
                           │           ▼                            │
                           │  1. Parse SmsMessage from Intent       │
                           │  2. Create ContentValues               │
                           │  3. Insert to Telephony.Sms.Inbox      │
                           │  4. Query inserted message             │
                           │           │                            │
                           │           ▼                            │
                           │  InboundMessaging.transferInboundMessage()
                           │           │                            │
                           │           ▼                            │
                           │  MethodChannel.invokeMethod(           │
                           │    "receiveInboundSmsMessage",         │
                           │    jsonString                          │
                           │  )                                     │
                           └───────────┬────────────────────────────┘
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────┐
│                        Dart Layer                                 │
├──────────────────────────────────────────────────────────────────┤
│  AndroidMessaging.receiveMessage(MethodCall)                     │
│           │                                                      │
│           ▼                                                      │
│  Sms.fromRaw(jsonDecode(arguments))                             │
│           │                                                      │
│           ▼                                                      │
│  smsCallback(smsMessage)  // User-provided callback             │
└──────────────────────────────────────────────────────────────────┘
```

#### MMS Receiving (Complex Multi-Stage Process)

```
Stage 1: WAP Push Notification
┌──────────────────────────┐
│ WAP_PUSH_DELIVER Intent  │
│ (application/vnd.wap.    │
│  mms-message)            │
└───────────┬──────────────┘
            │
            ▼
┌──────────────────────────┐
│ InboundMmsHandler        │
│ (BroadcastReceiver)      │
│                          │
│ 1. Extract PDU bytes     │
│ 2. Parse NotificationInd │
│ 3. Get contentLocation   │
│ 4. Get transactionId     │
└───────────┬──────────────┘
            │
            ▼
Stage 2: MMS Download
┌──────────────────────────┐
│ startMmsDownload()       │
│                          │
│ 1. Create temp file      │
│ 2. Get SmsManager        │
│ 3. Call downloadMms()    │
│    - contentLocation URI │
│    - FileProvider URI    │
│    - PendingIntent       │
└───────────┬──────────────┘
            │
            ▼
Stage 3: Download Complete
┌──────────────────────────┐
│ MmsDownloadReceiver      │
│                          │
│ 1. Read PDU from file    │
│ 2. Parse RetrieveConf    │
│ 3. Extract parts (text,  │
│    images, etc.)         │
│ 4. Get/create threadId   │
│ 5. Insert MMS to DB      │
│ 6. Insert parts to DB    │
│ 7. Insert addresses      │
│ 8. Query final message   │
└───────────┬──────────────┘
            │
            ▼
Stage 4: Deliver to Flutter
┌──────────────────────────┐
│ InboundMessaging         │
│ .transferInboundMessage( │
│   MessageType.MMS,       │
│   messageMap             │
│ )                        │
└───────────┬──────────────┘
            │
            ▼
┌──────────────────────────┐
│ Dart: mmsCallback(mms)   │
└──────────────────────────┘
```

### 6.3 Query System Implementation

The query system wraps Android's ContentProvider pattern:

```dart
// Dart API (lib/src/android/queries/android_query.dart)
class AndroidQuery {
  static final ContactQueries contacts = ContactQueries();
  static final ConversationQueries conversations = ConversationQueries();
  static final MessageQueries messages = MessageQueries();
  static final AndroidDeviceQueries device = AndroidDeviceQueries();

  // Raw query for custom needs
  static Future<List<Map<String, dynamic>>> raw(QueryObj queryObj);
}

// Example usage
final messages = await AndroidQuery.messages.getSms(
  selection: 'thread_id = ?',
  selectionArgs: ['123'],
  sortOrder: 'date DESC',
);
```

```kotlin
// Kotlin Implementation (Query.kt)
class Query(val context: Context) : MethodChannel.MethodCallHandler {

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "query" -> {
                val queryObj = QueryObj(
                    call.argument<String>("contentUri") ?: "",
                    call.argument<List<String>>("projection"),
                    call.argument<String>("selection"),
                    call.argument<List<String>>("selectionArgs"),
                    call.argument<String>("sortOrder")
                )
                val data = query(queryObj)
                result.success(data)
            }
            // ... other methods
        }
    }

    fun query(query: QueryObj): List<Map<String, Any?>> {
        return getCursorData(context, query).map { it.toSortedMap() }
    }
}
```

### 6.4 BroadcastReceiver Registration

All receivers are registered in AndroidManifest.xml with appropriate permissions:

```xml
<!-- SMS Receiver -->
<receiver
    android:name="io.simplezen.simple_sms.messaging.InboundSmsHandler"
    android:enabled="true"
    android:directBootAware="true"
    android:exported="true"
    android:permission="android.permission.BROADCAST_SMS">
    <intent-filter>
        <action android:name="android.provider.Telephony.SMS_DELIVER" />
    </intent-filter>
</receiver>

<!-- MMS Receiver -->
<receiver
    android:name="io.simplezen.simple_sms.messaging.InboundMmsHandler"
    android:enabled="true"
    android:directBootAware="true"
    android:exported="true"
    android:permission="android.permission.BROADCAST_WAP_PUSH">
    <intent-filter>
        <action android:name="android.provider.Telephony.WAP_PUSH_DELIVER" />
        <data android:mimeType="application/vnd.wap.mms-message" />
    </intent-filter>
</receiver>
```

---

## 7. Data Models

### 7.1 Core Message Models

#### SMS Model

```dart
class Sms implements ModelInterface {
  // Identity
  final int id;
  final int threadId;

  // Content
  final String? body;
  final String? address;
  final String? subject;

  // Timestamps
  final DateTime date;
  final DateTime? dateSent;
  final DateTime? deliveryDate;

  // Status
  final SmsMessageType? type;      // inbox, sent, draft, outbox, failed, queued
  final AndroidMessageStatus? status;
  final bool? read;
  final bool? seen;

  // Device Info
  final int? subscriptionId;
  final int? simSlot;
  final String? serviceCenter;

  // Computed Properties
  Future<Contactable?>? get sender;    // For outbox messages
  Future<Contactable?>? get recipient; // For inbox messages
}
```

#### MMS Model

```dart
class Mms implements ModelInterface {
  // Identity
  final int id;
  final int threadId;

  // Content
  final String body;                      // Extracted from text parts
  final List<MmsPart>? parts;            // All message parts
  final String? subject;

  // Participants
  final List<MmsParticipant>? recipients;
  final MmsParticipant? sender;
  final String? address;

  // Timestamps
  final DateTime? date;
  final DateTime? dateSent;
  final DateTime? deliveryDate;
  final DateTime? expiryDate;

  // MMS-Specific
  final MessageBox? messageBox;          // inbox, sent, drafts, outbox
  final MmsMessageType? type;
  final ContentType? contentType;
  final int? messageSize;
  final String? transactionId;
  final DeliveryReport? deliveryReport;
  final ReadStatus? readStatus;

  // Status
  final bool read;
  final AndroidMessageStatus? status;
  final MessagePriority? priority;

  // Device
  final int simSlot;
  final int? subscriptionId;
}
```

#### MmsPart Model (Attachments)

```dart
class MmsPart implements ModelInterface {
  final int id;
  final String contentLocation;
  final ContentType contentType;    // MIME type enum

  // Content
  final String? text;               // For text/plain parts
  final Uri? dataLocation;          // File URI for binary parts
  final String? fileName;

  // Metadata
  final CharSet? charset;
  final String? contentId;
  final int? sequence;

  // Computed
  bool get isText;                  // contentType contains "text"
  bool get isSmil;                  // contentType contains "smil"
}
```

#### OutboundMessage Model

```dart
class OutboundMessage {
  final String body;
  final Set<String> addresses;           // Recipients (phone numbers)
  final Set<String>? attachmentPaths;    // File paths for MMS
  final String? conversationId;          // Optional thread ID

  // Automatically determines SMS vs MMS based on:
  // - Has attachments → MMS
  // - Body length >= 160 → MMS
  // - Multiple recipients → MMS (group message)
}
```

### 7.2 Conversation Models

```dart
class AndroidFullConversation implements ModelInterface {
  final int id;
  final List<String> recipientIds;

  // Latest Message Info
  final String address;
  final String body;
  final int date;
  final int dateSent;
  final SmsMmsType? smsMmsType;

  // State
  final bool? isRead;
  final bool? isArchived;
  final bool? isBlocked;
  final bool? isPinned;
  final bool? isMuted;

  // Counts
  final int messageCount;
  final int unreadCount;
}

class AndroidSimpleConversation implements ModelInterface {
  final int id;
  final String? recipientIds;
  final int? messageCount;
  final String? snippet;              // Preview text
  final int? snippetCharset;
  final bool? read;
  final bool? archived;
  final int? date;
}
```

### 7.3 People Models

```dart
class Contactable implements ModelInterface {
  // Identity
  final int id;
  final int contactId;
  final int rawContactId;

  // Display
  final String displayName;
  final String value;                // Primary data (phone, email, etc.)
  final String mimetype;             // Type of contactable data

  // Contact Link
  Future<AndroidContact?> get contact;

  // Photos
  final String photoUri;
  final String photoThumbUri;
}

class MmsParticipant implements ModelInterface {
  final int id;
  final String? address;             // Phone number
  final AndroidParticipantType? participantType;  // FROM, TO, CC, BCC
  final int? contactId;
  final int? msgId;

  // Linked Data
  Future<AndroidContact?> get contact;
  Future<Contactable?> get contactable;
  Future<Mms?> get message;
}
```

### 7.4 Device Models

```dart
class Device {
  final String brand;
  final String model;
  final String os;                   // Android SDK version
  final List<SimCard> sims;
}

class SimCard {
  final int subscriptionId;
  final String? displayName;
  final String? carrierName;
  final String? number;
  final int? simSlotIndex;
  final String? iccId;
  final bool? isEmbedded;
}
```

### 7.5 Enum Definitions

```dart
// Message Types
enum SmsMessageType {
  all, inbox, sent, draft, outbox, failed, queued
}

enum MmsMessageType {
  sendRequest, sendConfirm, notificationIndication,
  retrieveConfirmation, acknowledgeIndication,
  deliveryIndication, readOriginalIndication,
  readReceiptIndication
}

// Status
enum AndroidMessageStatus {
  expired, retrieved, rejected, deferred,
  unrecognized, indeterminate, forwarded, unreachable
}

enum MessagePriority { low, normal, high }

// MMS-Specific
enum MessageBox { inbox, sent, drafts, outbox, failed }
enum DeliveryReport { notRequested, requested, unknown }
enum ReadStatus { read, deletedWithoutBeingRead, unknown }

// Content Types (MIME)
enum ContentType {
  textPlain, textHtml, textVcard, textXVcard,
  imageJpeg, imagePng, imageGif, imageBmp, imageWebp,
  videoMp4, video3gpp, videoWebm,
  audioMpeg, audioOgg, audioAmr,
  applicationSmil, applicationOctetStream,
  // ... 40+ content types
}

// Participants
enum AndroidParticipantType {
  bcc, cc, from, to
}
```

---

## 8. Platform Channel Architecture

### 8.1 Channel Definitions

```dart
// Dart Side - lib/src/interop/

// Messaging Channel
class MessagingInterop {
  static const MethodChannel methodChannel = MethodChannel(
    'io.simplezen.simple_sms/messaging',
  );
}

// Query Channel
class QueryInterop {
  static const MethodChannel methodChannel = MethodChannel(
    'io.simplezen.simple_sms/query',
  );
}

// Permissions Channel
class PermissionsInterop {
  static const MethodChannel methodChannel = MethodChannel(
    'io.simplezen.simple_sms/permissions',
  );
}
```

```kotlin
// Kotlin Side - SimpleSmsPlugin.kt

fun initializeMethodChannels(context: Context, binaryMessenger: BinaryMessenger) {
    messageChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/messaging")
    messageChannel.setMethodCallHandler(OutboundMessagingHandler(applicationContext))

    queryChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/query")
    queryChannel.setMethodCallHandler(Query(applicationContext))

    permissionsChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/permissions")
    permissionsChannel.setMethodCallHandler(PermissionsHandler(applicationContext))

    actionsChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/actions")
    actionsChannel.setMethodCallHandler(DeviceActions(applicationContext))

    destructiveActionsChannel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/destructive_actions")
    destructiveActionsChannel.setMethodCallHandler(DestructiveActions(applicationContext))
}
```

### 8.2 Method Call Patterns

#### Request/Response Pattern (Queries, Sends)

```dart
// Dart: Make request
final result = await methodChannel.invokeMethod<String>(
  "sendMessage",
  message.toJson(),
);
return jsonDecode(result ?? '{}');
```

```kotlin
// Kotlin: Handle request
override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    when (call.method) {
        "sendMessage" -> {
            val message = call.arguments as Map<String, Any?>
            // ... process message
            val finalMessageStr = AnySerializer.encodeToString(finalMessage)
            result.success(finalMessageStr)
        }
        else -> result.notImplemented()
    }
}
```

#### Callback Pattern (Inbound Messages)

```kotlin
// Kotlin: Invoke Dart method
val channel = MethodChannel(binaryMessenger, "io.simplezen.simple_sms/inbound_messaging")
val jsonString = AnySerializer.encodeToString(payload)

Handler(Looper.getMainLooper()).post {
    channel.invokeMethod("receiveInboundSmsMessage", jsonString,
        object : MethodChannel.Result {
            override fun success(result: Any?) {
                Log.d("InboundMessaging", "Message delivered to Dart")
            }
            override fun error(code: String, msg: String?, details: Any?) {
                Log.e("InboundMessaging", "Failed: $msg")
            }
            override fun notImplemented() {
                Log.e("InboundMessaging", "Method not implemented")
            }
        }
    )
}
```

```dart
// Dart: Handle callback
final inboundChannel = MethodChannel('io.simplezen.simple_sms/inbound_messaging');
inboundChannel.setMethodCallHandler(receiveMessage);

Future<bool> receiveMessage(MethodCall methodCall) async {
    final messageData = jsonDecode(methodCall.arguments as String);

    if (methodCall.method == 'receiveInboundSmsMessage') {
        final sms = Sms.fromRaw(messageData);
        return await smsCallback(sms);
    } else if (methodCall.method == 'receiveInboundMmsMessage') {
        final mms = await Mms.fromRaw(messageData);
        return await mmsCallback(mms);
    }
    throw PlatformException(code: 'UNKNOWN_METHOD');
}
```

### 8.3 Error Handling

```kotlin
// Kotlin: Structured error responses
try {
    // ... operation
    result.success(data)
} catch (e: SecurityException) {
    result.error("PERMISSION_DENIED", e.message, null)
} catch (e: IllegalArgumentException) {
    result.error("INVALID_ARGUMENT", e.message, null)
} catch (e: Exception) {
    result.error("UNKNOWN_ERROR", e.message, e.stackTraceToString())
}
```

```dart
// Dart: Handle errors
try {
    return await methodChannel.invokeMethod<String>("query", queryObj);
} on PlatformException catch (e) {
    if (e.code == 'PERMISSION_DENIED') {
        throw PermissionDeniedException(e.message);
    }
    rethrow;
}
```

---

## 9. MMS Subsystem

### 9.1 MMS Protocol Overview

MMS (Multimedia Messaging Service) operates over mobile data using WAP (Wireless Application Protocol):

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Sender    │────▶│   MMSC      │────▶│   MMSC      │────▶│  Recipient  │
│   Device    │     │  (Carrier)  │     │  (Carrier)  │     │   Device    │
└─────────────┘     └─────────────┘     └─────────────┘     └─────────────┘
      │                   │                   │                   │
      │                   │                   │                   │
   SendReq           Store/Forward       WAP Push            Download
   (Upload)                              Notification        RetrieveConf
```

### 9.2 MMS Database Structure

MMS messages are stored across multiple tables:

```sql
-- Main MMS table (Telephony.Mms)
CREATE TABLE mms (
    _id INTEGER PRIMARY KEY,
    thread_id INTEGER,
    date INTEGER,
    date_sent INTEGER,
    msg_box INTEGER,          -- inbox=1, sent=2, drafts=3, outbox=4
    read INTEGER,
    seen INTEGER,
    sub TEXT,                 -- subject
    sub_cs INTEGER,           -- subject charset
    ct_t TEXT,                -- content type
    ct_l TEXT,                -- content location
    m_id TEXT,                -- message id
    m_size INTEGER,
    m_type INTEGER,           -- message type
    pri INTEGER,              -- priority
    tr_id TEXT,               -- transaction id
    d_rpt INTEGER,            -- delivery report
    rr INTEGER,               -- read report
    sub_id INTEGER,           -- subscription id
    ...
);

-- MMS Parts table (Telephony.Mms.Part)
CREATE TABLE part (
    _id INTEGER PRIMARY KEY,
    mid INTEGER,              -- message id (FK to mms._id)
    seq INTEGER,              -- sequence number
    ct TEXT,                  -- content type (MIME)
    name TEXT,
    chset INTEGER,            -- charset
    cd TEXT,                  -- content disposition
    fn TEXT,                  -- filename
    cid TEXT,                 -- content id
    cl TEXT,                  -- content location
    ctt_s TEXT,               -- content type start
    ctt_t TEXT,               -- content type type
    _data TEXT,               -- file path for binary data
    text TEXT,                -- text content
    ...
);

-- MMS Addresses table (Telephony.Mms.Addr)
CREATE TABLE addr (
    _id INTEGER PRIMARY KEY,
    msg_id INTEGER,           -- FK to mms._id
    address TEXT,             -- phone number
    type INTEGER,             -- FROM=137, TO=151, CC=130, BCC=129
    charset INTEGER,
    ...
);
```

### 9.3 Klinker SMS/MMS Library Integration

The plugin uses the klinker/android-smsmms library for MMS transport:

```kotlin
// Settings configuration
val settings = Settings().apply {
    deliveryReports = true
    useSystemSending = true
    sendLongAsMms = true
    sendLongAsMmsAfter = 3  // Convert to MMS after 3 SMS segments
}

// Create message
val message = Message(
    body,
    addresses.toTypedArray()
)

// Add attachments
for (path in attachmentPaths) {
    val file = File(path)
    val uri = FileProvider.getUriForFile(context, authority, file)
    val mimeType = getMimeType(file)
    message.addMedia(
        uri,
        mimeType,
        file.name
    )
}

// Send
val transaction = Transaction(context, settings)
transaction.sendNewMessage(message, threadId)
```

### 9.4 PDU Parsing (Google MMS Library)

```kotlin
// Inbound MMS notification parsing
val pdu = intent.getByteArrayExtra("data")
val notification = PduParser(pdu, true).parse()

if (notification is NotificationInd) {
    val contentLocation = notification.contentLocation  // Download URL
    val transactionId = notification.transactionId
    val messageSize = notification.messageSize
    val from = notification.from?.string
}

// Downloaded MMS parsing
val retrieveConf = PduParser(pduBytes, true).parse() as RetrieveConf

val subject = retrieveConf.subject?.string
val from = retrieveConf.from?.string
val to = retrieveConf.to?.map { it.string }
val cc = retrieveConf.cc?.map { it.string }
val body = retrieveConf.body  // PduBody containing parts

// Extract parts
for (i in 0 until body.partsNum) {
    val part = body.getPart(i)
    val contentType = String(part.contentType)
    val data = part.data  // Binary content
}
```

### 9.5 MMS Part Handling

```kotlin
// Insert MMS parts to database
fun insertMmsParts(context: Context, msgId: Long, parts: List<MmsPart>): List<Map<String, Any?>> {
    val mmsPartsUri = Mms.Part.getPartUriForMessage(msgId.toString())
    val results = mutableListOf<Map<String, Any?>>()

    for (part in parts) {
        // Insert part metadata
        val newUri = context.contentResolver.insert(mmsPartsUri, part.contentValues)

        // Write binary data if present
        if (newUri != null && !part.mimeType.contains("text") && part.data.isNotEmpty()) {
            context.contentResolver.openOutputStream(newUri)?.use { out ->
                out.write(part.data)
            }
        }

        // Query and return the inserted part
        val newPart = Query(context).query(QueryObj(contentUri = newUri.toString())).first()
        results.add(newPart)
    }

    return results
}
```

### 9.6 MMS Attachment Retrieval

```kotlin
// Read MMS part data to temp file
fun queryToFile(query: QueryObj): String? {
    val resolver = context.contentResolver
    val contentUri = query.contentUri.toUri()

    // Skip text/SMIL parts
    resolver.query(contentUri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val contentType = cursor.getString(cursor.getColumnIndex("ct"))
            if (contentType?.contains("smil") == true ||
                contentType?.contains("text") == true) {
                return null
            }
        }
    }

    // Copy to temp file
    val tempFile = File.createTempFile("mms_part_", null, context.cacheDir)
    resolver.openInputStream(contentUri)?.use { input ->
        FileOutputStream(tempFile).use { output ->
            input.copyTo(output)
        }
    }

    return tempFile.absolutePath
}
```

---

## 10. Security & Permissions

### 10.1 Permission Model

The plugin uses Android's runtime permission system with an "Intention" abstraction:

```dart
enum Intention {
  texting,     // SMS/MMS messaging
  calling,     // Phone calls
  contacts,    // Contact access
  device,      // Device info
  fileAccess;  // Media/storage

  String? get role;           // Required Android role
  List<String> get permissions; // Required permissions
}
```

#### Permission Groups by Intention

**Texting Intention:**
```dart
permissions: [
  'android.permission.SEND_SMS',
  'android.permission.READ_SMS',
  'android.permission.RECEIVE_SMS',
  'android.permission.WRITE_SMS',
  'android.permission.RECEIVE_WAP_PUSH',
  'android.permission.RECEIVE_MMS',
]
role: 'android.app.role.SMS'
```

**File Access Intention:**
```dart
permissions: [
  'android.permission.READ_EXTERNAL_STORAGE',
  'android.permission.READ_MEDIA_IMAGES',
  'android.permission.READ_MEDIA_VIDEO',
  'android.permission.READ_MEDIA_AUDIO',
]
```

### 10.2 Permission Request Flow

```dart
// Check current permission state
final permissions = await AndroidPermissions.checkPermissions(Intention.texting);
final hasRole = await AndroidPermissions.checkRole(Intention.texting);

// Request permissions
if (!permissions.values.every((granted) => granted)) {
  await AndroidPermissions.requestPermissions(Intention.texting);
}

// Request default SMS app role (required for MMS receiving)
if (!hasRole) {
  await AndroidPermissions.requestRole(Intention.texting);
}
```

### 10.3 Default SMS App Requirements

To receive MMS messages, the app must be the device's default SMS application. This requires:

1. **Manifest declarations** for all SMS/MMS receivers
2. **Role request** via `RoleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)`
3. **User approval** through system dialog

When set as default, the app is responsible for:
- Receiving all SMS/MMS broadcasts
- Writing received messages to the system database
- Displaying notifications to the user

### 10.4 Data Security Considerations

| Concern | Mitigation |
|---------|------------|
| Message content exposure | Messages stored in system database (encrypted at rest) |
| Attachment handling | Temp files in app cache directory, deleted after processing |
| Contact data | Read-only access, no external transmission |
| Phone numbers | E.164 normalization via libphonenumber |
| Platform channel data | JSON serialization, no persistent storage |

### 10.5 FileProvider Configuration

```xml
<!-- Required for MMS file handling -->
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.provider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths"/>
</provider>
```

```xml
<!-- res/xml/file_paths.xml -->
<paths>
    <cache-path name="mms_temp" path="." />
    <external-path name="external" path="." />
    <external-files-path name="external_files" path="." />
</paths>
```

---

## 11. Deployment & Distribution

### 11.1 Package Structure

```yaml
# pubspec.yaml
name: simple_sms
description: "A modern MMS / SMS plugin for Android"
version: 0.0.1
homepage: simplezen.io

environment:
  sdk: ^3.7.0-0
  flutter: '>=3.3.0'

flutter:
  plugin:
    platforms:
      android:
        package: io.simplezen.simple_sms
        pluginClass: SimpleSmsPlugin
      ios:
        pluginClass: SimpleSmsPlugin  # Planned
```

### 11.2 Build Configuration

#### Plugin build.gradle.kts

```kotlin
android {
    namespace = "io.simplezen.simple_sms"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Vendored projects
    implementation(project(":google_apps_messaging_core"))
    implementation(project(":google_i18n_libphonenumber"))
    implementation(project(":google_chips"))
    implementation(project(":google_photoviewer"))
    implementation(project(":google_vcard"))
    implementation(project(":google_ex"))

    // External dependencies
    implementation("com.squareup.okhttp3:okhttp:3.14.9")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}
```

### 11.3 Consumer App Integration

```yaml
# Consumer app pubspec.yaml
dependencies:
  simple_sms: ^0.0.1
```

```xml
<!-- Consumer app AndroidManifest.xml -->
<!-- Must include all required permissions -->
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.READ_SMS" />
<!-- ... all permissions from Section 4 -->

<!-- Must declare intent to be default SMS app -->
<activity android:name=".MainActivity">
    <intent-filter>
        <action android:name="android.intent.action.SENDTO" />
        <data android:scheme="smsto" />
    </intent-filter>
</activity>
```

### 11.4 Development Workflow

```bash
# Clone and setup
git clone <repository>
cd simple_sms
flutter pub get

# Run example app
cd example
flutter run

# After Kotlin changes
flutter clean && flutter run

# Build for release
flutter build apk --release
```

### 11.5 Testing Considerations

| Test Type | Approach |
|-----------|----------|
| Unit Tests | Dart model serialization, enum mappings |
| Integration Tests | Platform channel communication |
| Manual Testing | Real device with SIM card required |
| MMS Testing | Requires carrier network, default SMS role |

**Testing Limitations:**
- Emulators cannot send/receive real SMS/MMS
- MMS requires active mobile data connection
- Some carriers have MMS size limits (300KB - 1MB)

---

## 12. Future Roadmap

### 12.1 Short-Term (v0.1.x)

- [ ] **iOS Support**: Implement MessageUI framework integration
- [ ] **RCS Support**: Rich Communication Services where available
- [ ] **Scheduled Messages**: Database-backed message scheduling
- [ ] **Message Search**: Full-text search across conversations
- [ ] **Batch Operations**: Bulk delete, archive, mark-read

### 12.2 Medium-Term (v0.2.x)

- [ ] **Backup/Restore**: Export/import message history
- [ ] **Analytics Events**: Message send/receive hooks
- [ ] **Custom Notifications**: Configurable notification handling
- [ ] **Group Chat Management**: Create/modify MMS groups
- [ ] **Delivery Receipts**: Enhanced delivery status tracking

### 12.3 Long-Term (v1.0.x)

- [ ] **End-to-End Encryption**: Optional E2EE for supported clients
- [ ] **Cloud Sync**: Optional cloud message synchronization
- [ ] **Web Interface**: Companion web messaging portal
- [ ] **Desktop Support**: macOS/Windows/Linux SMS relay
- [ ] **Carrier Profiles**: Pre-configured MMS settings by carrier

### 12.4 Known Limitations

| Limitation | Reason | Workaround |
|------------|--------|------------|
| Android only | iOS SMS APIs are restricted | iOS planned |
| minSdk 30 | Modern Telephony APIs | Cannot support older devices |
| MMS requires default app | Android security model | Inform users |
| Carrier-specific MMS issues | MMS protocol varies | Klinker library handles most |
| No tablet support | Telephony feature required | WiFi calling may work |

---

## 13. Appendices

### Appendix A: Content Type Reference

| Category | ContentType Values |
|----------|-------------------|
| Text | `textPlain`, `textHtml`, `textVcard`, `textXVcard`, `textXVcalendar` |
| Image | `imageJpeg`, `imagePng`, `imageGif`, `imageBmp`, `imageWebp`, `imageHeic` |
| Video | `videoMp4`, `video3gpp`, `video3gpp2`, `videoWebm`, `videoMpeg` |
| Audio | `audioMpeg`, `audioOgg`, `audioAmr`, `audioAac`, `audio3gpp` |
| Application | `applicationSmil`, `applicationOctetStream`, `applicationPdf` |

### Appendix B: Android Telephony URIs

| URI | Purpose |
|-----|---------|
| `content://sms` | All SMS messages |
| `content://sms/inbox` | Received SMS |
| `content://sms/sent` | Sent SMS |
| `content://mms` | All MMS messages |
| `content://mms/inbox` | Received MMS |
| `content://mms/sent` | Sent MMS |
| `content://mms/part` | MMS attachments |
| `content://mms-sms/conversations` | Conversation threads |
| `content://contacts` | Device contacts |

### Appendix C: Error Codes

| Code | Meaning | Resolution |
|------|---------|------------|
| `PERMISSION_DENIED` | Missing runtime permission | Request permissions |
| `NOT_DEFAULT_APP` | Not default SMS handler | Request SMS role |
| `SEND_FAILED` | Message send failure | Check network/carrier |
| `INVALID_ADDRESS` | Malformed phone number | Validate with libphonenumber |
| `ATTACHMENT_TOO_LARGE` | MMS size exceeds limit | Compress attachments |
| `NO_SIM` | No active SIM card | Device configuration |

### Appendix D: Gradle Module Structure

```
android/
├── build.gradle.kts           # Main plugin build
├── settings.gradle.kts        # Module includes
├── src/main/                  # Plugin source
│
└── lib/                       # Vendored libraries
    ├── google_apps_messaging_core/
    │   ├── build.gradle.kts
    │   └── src/com/android/messaging/
    │       └── ... (klinker library)
    │
    ├── google_i18n_libphonenumber/
    │   └── ... (phone formatting)
    │
    ├── google_chips/
    ├── google_photoviewer/
    ├── google_vcard/
    └── google_ex/
```

### Appendix E: Quick Start Code

```dart
import 'package:simple_sms/simple_sms.dart';

void main() {
  // 1. Initialize with callbacks (MUST be in main)
  AndroidMessaging.initialize(
    inboundSmsCallback: (Sms sms) async {
      print('SMS from ${sms.address}: ${sms.body}');
    },
    inboundMmsCallback: (Mms mms) async {
      print('MMS from ${mms.address}: ${mms.body}');
      for (final part in mms.parts ?? []) {
        print('  Attachment: ${part.contentType.value}');
      }
    },
  );

  runApp(MyApp());
}

// 2. Request permissions (call once, e.g., on first launch)
Future<void> setupPermissions() async {
  await AndroidPermissions.requestPermissions(Intention.texting);
  await AndroidPermissions.requestPermissions(Intention.fileAccess);
  await AndroidPermissions.requestRole(Intention.texting);
}

// 3. Send SMS
Future<void> sendSms() async {
  final message = OutboundMessage(
    body: "Hello!",
    addresses: {"+1234567890"},
    attachmentPaths: null,
  );
  await AndroidMessaging.instance.sendMessage(message: message);
}

// 4. Send MMS with attachment
Future<void> sendMms() async {
  final message = OutboundMessage(
    body: "Check out this photo!",
    addresses: {"+1234567890"},
    attachmentPaths: {"/path/to/image.jpg"},
  );
  await AndroidMessaging.instance.sendMessage(message: message);
}

// 5. Query conversations
Future<void> loadConversations() async {
  final conversations = await AndroidQuery.conversations.getAllConversations(
    sortOrder: 'date DESC',
  );
  for (final conv in conversations) {
    print('Thread ${conv.id}: ${conv.recipientIds}');
  }
}
```

---

## Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 0.0.1 | December 2025 | Jake | Initial whitepaper |

---

*This whitepaper is a living document and will be updated as the project evolves.*
