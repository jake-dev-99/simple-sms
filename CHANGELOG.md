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

## 0.1.0

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
