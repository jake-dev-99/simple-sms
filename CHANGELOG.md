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
* `AndroidAction.sendNotification` — consumers must use `simple_notifications` (`SimpleNotifications.instance.showSimple`). The corresponding `sendNotification` MethodChannel handler and the orphaned `Notification.kt` class have been removed from the Android side.
* `AndroidDevice` and `AndroidSimCard` models — these belong in `simple_telephony_native` and have been relocated there. Import from that plugin instead.

### Deprecation note (follow-up)
* `AndroidPermissions` and the `Android.provisioning` property remain for now but are superseded by `simple_permissions_native`. A follow-up PR will migrate the example app off `AndroidPermissions` and remove the class entirely.

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
