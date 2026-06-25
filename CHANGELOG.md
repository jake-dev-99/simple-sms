## 0.4.0

### Changed (breaking)

- **Per-message writes now require a channel.**
  `AndroidAction.markMessageAsRead` and `AndroidDestructiveAction.deleteMessage`
  take a required `SmsMmsType channel`. The native `_id` is unique only within
  its own SMS/MMS table, so the channel selects the correct table — the prior
  bare-id signatures guessed SMS-first and could mark or delete the **wrong**
  message when an SMS and an MMS shared an `_id` (UNFY-213). Callers pass the
  channel they already hold (it is part of the message identity in the read
  contract). `markConversationAsRead` / `deleteThread` are unchanged — they act
  by thread, which spans both tables.

### Fixed

- Per-message write handlers (`DeviceActions`, `DestructiveActions`) surface
  failures uniformly via `result.error`, with the originating stack trace in the
  `details` argument, instead of an unhandled exception (e.g. a `SecurityException`
  when the app is not the default SMS app) crashing the method-channel call.

## 0.3.0

### Added

- Provider contract (ADR-0014): a source-agnostic `NormalizedMessage` read
  surface (body/direction/status resolved at the plugin boundary), a
  `ContentObserver`-backed change-stream, attachment-by-reference (on-demand
  open/stream — the plugin is no longer the attachment system-of-record),
  `ChannelCapabilities` flags, and a unified SMS/MMS `MessageParticipant`
  contract (UNFY-165, UNFY-204–212).

## 0.2.0

### Added

- `OutboundMessage.subscriptionId` (`int?`) — selects which telephony
  subscription (SIM) an outbound SMS/MMS is sent from. The native
  `OutboundMessagingHandler` already read `message["subscriptionId"]` to
  build `SmsManager.createForSubscriptionId(...)`; this exposes the Dart
  field so callers can actually set it. When null, behaviour is unchanged
  (system default SMS SIM via `SmsManager.getDefaultSmsSubscriptionId()`).

  SIM *enumeration* for a send picker is intentionally **not** added here —
  that lives in `simple_telephony_native.listSimCards()` (see the
  "enumeration moved out" note in `android.dart`). This plugin owns the
  outbound send; it takes the chosen `subscriptionId` as input.

## 0.1.0

### First pub.dev release

`simple_sms_native` has never reached pub.dev — earlier git tags
(0.4.0 / 0.4.1 / 0.4.3) and pubspec versions (up through 0.5.0)
were authored ahead of the first publish. Tag history has been
pruned and the version reset to 0.1.0 so the published series
starts with semver storytelling that aligns with what users can
actually `flutter pub get`.

This entry stands in for everything previously developed under
the orphan tags. Notable surface as of 0.1.0:

- Typed SMS / MMS / participant / conversation models with
  `fromRaw` / `toRaw` golden-tested against Samsung Android 16
  ContentProvider shape, including OEM-extension columns and
  empty-string sentinel handling.
- Inbound + outbound messaging with cursor (`idAfter`) and
  offset pagination on `SmsFilter`, `MmsFilter`,
  `ConversationFilter`, `ContactFilter`.
- `LookupService` for thread-id-by-recipients (now de-duped),
  canonical-address-by-recipient-id, and per-MMS address rows.
- Permissions + default-SMS-app role delegated to
  `simple_permissions_native ^1.8.0`; provider queries via
  `simple_query ^0.6.0`.

## 0.5.0

### Coordinated release

Coordinated minor bump to align with the v1 publish-unblock
sequence across the simple_* federation. Dependency constraints
re-pinned to the freshly-coordinated cross-repo versions:

- `simple_query: ^0.3.0` → `^0.6.0`
- `simple_permissions_native: ^1.3.0` → `^1.8.0`

No source changes — version bump + constraint tightening only.

## 0.4.3

### Added
- `idAfter` field on `SmsFilter`, `MmsFilter`, `ConversationFilter`,
  and `ContactFilter` — cursor-based pagination anchor emitting
  `_id > ?` in the underlying query. Pair with the matching
  `SortField.id` on the corresponding `*Sort` so "after this id" is
  well-defined. Lets large-history sync pipelines avoid the
  `OFFSET` scan cost on the ContentProvider: index-served
  `WHERE _id > cursor ORDER BY _id ASC LIMIT N` stays `O(N)` per
  page regardless of how deep into the table the cursor is.

## 0.4.2

### Fixed
- `LookupService` (`listContacts`, `listSms`, `listMms`,
  `listConversations`, and the `lookup*`/`getStructuredName`
  helpers) now queries `simple_query` with
  `QueryDomain.platformSpecific` + explicit `contentUri`,
  instead of the typed domains. Previously the typed-domain
  path went through `_normalizeRecord` in
  `simple_query_android`, which reshapes rows into the
  canonical `{id, displayName, …}` schema — stripping the raw
  Android columns (`_id`, `display_name`, `date`, `body`, …)
  that `AndroidContact.fromRaw` / `Sms.fromRaw` / `Mms.fromRaw`
  / `AndroidSimpleConversation.fromRaw` all depend on. Every
  list call crashed with
  `"Null check operator used on a null value"` on the missing
  `_id`, and `LookupService`'s `try/catch` swallowed the error
  and returned `[]` — so consumer sync loops cleanly but
  silently reported 0 rows. Switching to `platformSpecific`
  restores the raw-row contract.

## 0.4.1

### Fixed
- `SimpleSmsPlugin.onDetachedFromEngine` no longer crashes with
  `UninitializedPropertyAccessException: lateinit property messageChannel
  has not been initialized` on engine teardown. The four channel
  `lateinit var`s are only populated during `onAttachedToActivity`, so a
  headless background `FlutterEngine` (e.g. the one Workmanager spins up
  for a sync task) that attaches and detaches without ever seeing an
  Activity used to bring the app down. Each `setMethodCallHandler(null)`
  is now guarded by `::channel.isInitialized`.

## 0.4.0

### Changed
- `Query.kt` internal `getCursorData` + `queryToFile` content-type probe now delegate to `simple_query`'s `ContentQuery` Kotlin helper (added in `simple_query_android` 0.3.0) instead of calling `ContentResolver.query(...)` directly. Rule 1 of the cross-plugin consolidation (*"content-provider queries route through simple_query"*) now upheld at the Kotlin layer too, not just at the Dart API.
- Bumps the `simple_query` constraint from `^0.2.0` to `^0.3.0` (required for `ContentQuery`).
- Two deliberate behaviour shifts from the previous inline implementation, safe because simple-sms's internal callers (`MmsDatabaseWriter`, `InboundSmsHandler`) read string / long / uri columns and the `io.simplezen.simple_sms/query` method channel has no Dart consumers:
  - BLOB columns null-coalesced (matches the Pigeon path).
  - `FIELD_TYPE_FLOAT` surfaces as `Double` rather than `Float`.

### Internal
- `android/build.gradle.kts` adds `implementation(project(\":simple_query_android\"))`. Resolves via Flutter's plugin-loader because the root pubspec already declares `simple_query` at the pub level.

## 0.3.0

### Added
- `simple_permissions_native` is now a declared pubspec dependency (formerly referenced only by doc comment). It owns every runtime permission **and** the `DefaultSmsApp` role that this plugin relies on at runtime — consumers use a single API for access state across the plugin family.

### Docs
- Every public operation that depends on a permission or role now names it explicitly in its doc comment and links the `simple_permissions_native` call that grants it (`check(...)`, `request(...)`, `observe([...])`).
- README gains a vocabulary table: `ReadSms` / `ReceiveSms` / `SendSms` / `DefaultSmsApp` and which operations need which.
- Plugin description updated to stop listing "default-app role management" as a native feature — that lives in `simple_permissions_native`.

### Unchanged
- No native code changes. The plugin already delegated at runtime (`SimpleSmsPlugin.onRequestPermissionsResult` returns false, internal queries do silent permission checks); this release formalises the contract with a real dep + explicit docs.

## 0.2.0

### Removed (breaking)
* `AndroidPermissions` class — `checkRole`, `requestRole`, `checkPermissions`,
  `requestPermissions`, plus the `PermissionsEnums` / `Intention` enum — and
  the `Android.provisioning` facade property. All of this is covered by
  `simple_permissions_native` (`SimplePermissionsNative.instance`):
    - `AndroidPermissions.checkRole(Intention.texting)` →
      `sp.check(const DefaultSmsApp()) == PermissionGrant.granted`
    - `AndroidPermissions.requestRole(Intention.texting)` →
      `sp.request(const DefaultSmsApp())`
    - `AndroidPermissions.checkPermissions(Intention.texting)` →
      `sp.checkIntentionDetailed(Intention.texting)` (returns
      `PermissionResult` — use `.isFullyGranted` / `.denied` / etc.)
    - `AndroidPermissions.requestPermissions(Intention.texting)` →
      `sp.requestIntentionDetailed(Intention.texting)`
    - `Intention.fileAccess` → `Intention.mediaVisual` (+ `Intention.mediaAudio`
      when audio attachments matter; see the `Intention` class on
      `simple_permissions_native` for finer-grained splits)
* Native Kotlin: deleted `device/PermissionsHandler.kt` and the
  `io.simplezen.simple_sms/permissions` MethodChannel. The associated
  `REQUEST_CODE_ROLE` / `REQUEST_CODE_PERMISSIONS` + pending-result plumbing
  in `SimpleSmsPlugin.kt` was removed; the plugin's `onActivityResult` /
  `onRequestPermissionsResult` overrides now return `false` so sibling
  plugins on the same `ActivityPluginBinding` own their own results.

### Example
* Migrated `example/lib/main.dart` + `example/lib/simple_example.dart` +
  `example/EXAMPLES.md` + `example/QUICK_REFERENCE.md` + other docs to
  `simple_permissions_native`. Example pubspec adds
  `simple_permissions_native: ^1.2.0` with a temporary
  `dependency_overrides` block to force path resolution until
  `simple_query_android` 0.2.1 (with the updated permissions pin) is
  published.

## 0.1.0 (legacy unpublished)

> Note: this 0.1.0 entry predates the pub.dev publish series and is
> retained here for archival reference only. The `## 0.1.0` heading
> at the top of this file describes the actual first published
> release. The work below was developed under git tags 0.1.x — 0.4.x
> that never reached pub.dev and have since been deleted from origin.

### Added
* `LookupService.listSms({filter, sort, limit, offset})` — typed list query for SMS, replacing raw content-URI queries from consumers. `SmsFilter` / `SmsSort` / `SortDirection` value types exposed from the top-level import.
* `LookupService.getSmsById(id)` — convenience single-row lookup built on `listSms`.
* `LookupService.listMms({filter, sort, limit, offset})` — typed list query for MMS. `MmsFilter` / `MmsSort` exposed.
* `LookupService.listMmsParts({mmsId, filter})` — list part rows (body + attachments) for an MMS. `MmsPartFilter` exposed.
* `LookupService.extractMmsPart({partId, outputDirectory, filename})` — resolve an MMS part's binary content to a file on disk; wraps `SimpleQuery.openBinary` / `closeBinary`.
* `LookupService.listContacts({filter, sort, limit, offset})` — typed list query for contacts. `ContactFilter` / `ContactSort` exposed.
* `LookupService.listConversations({filter, sort, limit, offset, enrich})` — typed list query against `content://mms-sms/conversations?simple=true`. When `enrich` is true (default) each returned `AndroidSimpleConversation` gets its `participants` / `latestSms` / `latestMms` resolved via follow-up lookups.
* `LookupService.getConversationByThread(threadId, {enrich})` — single-thread lookup shorthand.
* `LookupService.listContactablesForContact(contactId)` — every `data` row for a contact (phone numbers, emails, other MIME-typed entries) via `content://com.android.contacts/data`.
* `LookupService.getStructuredName(contactId, {accountType})` — resolve a contact's given/family/phonetic name fields from the structured-name data row.
* `AndroidSimpleConversation`: new optional fields `participants` (`List<Contactable>?`), `latestSms` (`Sms?`), `latestMms` (`Mms?`); `enrich(...)` copy-constructor.

### Removed
* `AndroidAction.sendNotification` — notifications are out of scope for an SMS plugin. Consumers should use their own notification layer (e.g. [`flutter_local_notifications`](https://pub.dev/packages/flutter_local_notifications), which is widely supported and covers Android MessagingStyle, channels, and actions). The corresponding `sendNotification` MethodChannel handler and the orphaned `Notification.kt` class have been removed from the Android side.
* `AndroidDevice` and `AndroidSimCard` models — these belong in `simple_telephony_native` and have been relocated there. Import from that plugin instead.

### Deprecation note (follow-up)
* `AndroidPermissions` and the `Android.provisioning` property remain for now
  but are superseded by `simple_permissions_native`. Removed entirely in 0.2.0.

## 0.0.3

* Reduce published package size by excluding unused vendored Android framework code
* Fix SDK constraint to use stable Dart release
* Fix absolute paths in settings.gradle.kts for pub.dev consumers

## 0.0.1

* Initial release
* Send and receive SMS and MMS messages on Android
* Background message delivery when app is killed
* Conversation thread management
* Contact and participant resolution via `LookupService`
* MMS attachment support (images, video, audio)
* Permission and default SMS app role management
* Device and SIM card information queries
* Local notification support