## Unreleased

- Require simple_permissions_native ^1.6.4 so published resolution includes the repaired platform packages and native PermissionGuards API.

Release as a minor bump: `Sms.status` changes type, and destructive deletes
return typed results.

### Changed (breaking)

- **Destructive deletes are verified and typed (UNFY-244).**
  `AndroidDestructiveAction.deleteThread` / `deleteMessage` resolve to a
  `DeleteResult` instead of `bool`: `deleted`, `alreadyAbsent`, or (threads
  only) `newMessagesArrived` when messages arrived while the delete ran.
  Every delete requires the default-SMS role and is read back natively; one
  that can't be verified throws `DeleteFailedException` carrying a
  `DeleteFailureReason` (`notDefaultSmsApp`, `deleteIncomplete`,
  `deleteFailed`, `verifyFailed`, `invalidArgument`). A message the provider
  doesn't return resolves to `alreadyAbsent`, as in AOSP Messaging, including
  one the Android 14+ per-user subscription filter hides (UNFY-330).

- **`Sms.status` is now `SmsDeliveryStatus?` (UNFY-299).** It was typed as the
  MMS `AndroidMessageStatus` enum, so it was null for every SMS. It now carries
  the delivery status recorded from delivery reports: `none`, `complete`,
  `pending` or `failed`. Raw TP-Status values in the provider map by range.

### Added

- **Durable outbound identity (UNFY-310).** Optional `OutboundMessage.clientCorrelationId` is synchronously persisted with its exact SMS/MMS provider URI before radio handoff. `AndroidMessaging.getSendCorrelations` recovers that identity after restart. An indexed SQLite registry in Android's no-backup directory avoids whole-registry XML rewrites and prevents cloud backup/device transfer from restoring device-specific row IDs. Metadata contains no message content or recipient addresses; the exact provider `date` is retained only to verify row generation, never to discover or match a logical send. Unreleased XML metadata is not imported because it lacks a verified generation and device-local provenance.
  Correlated sends and recovery require `ReadSms` for provider-generation verification. Recording an uncorrelated send clears prior identity at its URI. Recovery prunes absent or generation-changed rows, preserving a job's surviving attempts in recorded order; provider unavailability fails without pruning identities.
  All recipient rows from one native MMS request share an attempt identity. Each surviving row of the latest attempt returns itself as `latestMessageUri`; rows from earlier retries return a surviving row of the latest attempt. Consumers comparing `latestMessageUri` with the requested URI therefore retain every current recipient row.
  Each logical send retains at most 64 live provider rows. The sender holds one shared engine lock across the recipient-row budget check and synchronous native persistence/radio initialization; competing sends cannot consume the same free capacity before provider creation; absent or generation-changed rows can free space, and live identities are never silently evicted. Lookups accept at most 1000 URIs per batch.

- **`SEND_RESULT_TIMEOUT` (UNFY-299).** `sendMessage` settles with this code
  when no carrier result arrives within 10 minutes of device uptime (e.g. the
  radio process crashed mid-send), instead of never completing.
  `details['sent']` is `null` (unknown). The row is marked failed; a carrier
  success that arrives later still moves it to sent.

- **MMS delivery reports (UNFY-110).** Outgoing MMS now request a delivery
  report, as SMS already do. The MMSC's message id from the send confirmation
  is saved as the row's `m_id` with its SENT result. Each recipient's
  M-Delivery.ind is matched to the row by that id. The message's verdict is
  saved as the row's `st`, once every recipient reported: `retrieved` when all
  got it, and the first failure (`expired`, `rejected` or `unreachable`) when
  none will. A group MMS that some recipients got and some never will has no
  verdict, so a resend never reaches anyone twice.
  `NormalizedMessage.deliveryState` maps a sent MMS's `st` to `delivered` or
  `failed`. With no verdict, the MMS stays `sent`.

### Changed

- **Migrates to built-in Kotlin (UNFY-174).** The Android build no longer
  applies the Kotlin Gradle Plugin. Updates the minimum supported SDK version
  to Flutter 3.44/Dart 3.12.
- **A send whose row can't be read back is `SEND_ACK_FAILED` (UNFY-284).**
  When the provider returns no cursor on the read-back after a send result,
  the row may still exist, so `sendMessage` fails with `SEND_ACK_FAILED`
  (`sent` carries the carrier outcome) instead of `MESSAGE_ROW_MISSING`, which
  stays for a row that is really gone.
- **A multipart SMS settles from every part (UNFY-180).** `sendMessage`
  answers only once every part has reported its carrier result, rather than
  as soon as any part reports a failure: failed if any part failed, with the
  result code of the first failure to arrive, else sent. The row moves to sent
  or failed at the same point, even when the parts report to different app
  processes: each part has its own status intents, and the parts' results are
  persisted until they add up. The row's delivery status likewise comes from
  every part's report: delivered only once every part is, failed if any part
  failed, pending in between; a 3GPP2 (CDMA) report on the last part, the only
  part CDMA asks one for, stands for the whole message. Results for a send
  whose row was deleted and its id reused are ignored. A send made by an
  earlier version and still pending across the update has its status intents
  cancelled when its row is reused, and a result of it already on its way
  is ignored for the 7 days a new send's results may take. If the app's files
  cannot be read or written, a one-part SMS still settles from its result and
  report; a multipart one is never settled from a single part: its delivery
  status is left as is, and its send settles as `SEND_RESULT_TIMEOUT` (or, if
  the app process died, is marked failed by the stale-outbox sweep).
- **Delivery reports reach `NormalizedMessage.deliveryState` (UNFY-299).** An
  outbound SMS whose delivery report failed is `failed`; a sent SMS whose
  report says complete is `delivered`.
- **Outbound SMS rows start in the outbox (UNFY-299).** A row moves to sent
  when the carrier accepts it and to failed when it rejects it, so in-flight
  sends read as `sending`. Results are recorded even when the app process
  has died. An SMS or MMS this app sent whose result never came (a reboot
  mid-send) is marked failed once it is 10 minutes overdue: checked whenever
  the plugin starts on an engine, and again 10 minutes later; a carrier
  success that arrives later still moves it to sent. A send the app is still
  awaiting times out instead (`SEND_RESULT_TIMEOUT`).
- **A send without the default-SMS role fails with `PERMISSION_DENIED`
  (UNFY-299)**, not `SEND_INITIATION_ERROR`: the provider refuses the SMS
  row, and nothing is sent.
- **Require `simple_query` 0.3.0 and `simple_query_android` 0.3.0
  (UNFY-299, UNFY-244).** The verified deletes call `simple_query_android`'s
  `ContentQuery.queryOrNull` directly, so both must be published first.
  `simple_query` 0.3.0 reports a provider that returned no cursor as
  `SimpleQueryError(unavailable)` instead of an empty result, so lookups can
  now throw it. Conversation enrichment tolerates it
  per thread: that thread comes back unenriched (null `participants`,
  `latestSms`, `latestMms`) instead of failing the list.
- **A send result that doesn't land on its row is retried (UNFY-335).** An
  app that lost the default-SMS role or its `WRITE_SMS` app-op has its
  provider writes ignored, not refused, so the row stayed in the outbox while
  `sendMessage` answered with it. Now:
  - A write the provider ignored is detected by reading the row back.
    `sendMessage` then answers `SEND_ACK_FAILED` with the carrier result in
    `details['sent']`. So does a write that took no row when the provider
    returns no cursor to check it, which used to answer
    `MESSAGE_ROW_MISSING`.
  - Such a write, or one that failed, is kept and retried once each send
    has been handed off, at each engine start, and before the stale-outbox
    sweep. A kept write is tied to its row's `date` as soon as the row can
    be read, and this app's next send forgets the writes owed to the row it
    takes, so a write is not applied to a message that reused the id. A row
    newer than the write is never its row. A carrier failure, or a failed
    delivery, is never replaced by a later write. A later write that lands
    drops an older write of its kind still owed to the row, unless that one
    is final. A write still owed 7 days after the row was first owed one of
    its kind is given up. Delivery-status writes get the same
    treatment; one waits while its row's send result is still owed.
- **Send results only rewrite a row still on its way out (UNFY-335).** A
  failed SMS result and every MMS result now carry the guard a successful SMS
  result already had. A late result for a deleted message never rewrites an
  inbound or draft row that reused its id, or a carrier failure already
  recorded. A failed SMS result still marks a sent row failed, as a part sent
  before parts were tracked may have moved it there on its own.
- **A send whose row is reused before its result comes is answered
  (UNFY-335).** If a pending send's row was deleted and a new send reused its
  id, the earlier `sendMessage` was never answered. It now settles
  at once with `MESSAGE_ROW_MISSING`, with `details['sent']` `null` (unknown).
- **`sendMessage` throws a `FormatException` for an answer that isn't a JSON
  object (UNFY-335)**, instead of a `TypeError`; no answer at all throws it
  too, instead of resolving to an empty map.
- **Conversation-list MMS previews fail loudly on unrecoverable errors
  (UNFY-253).** `listConversations` and `getConversationByThread` load the
  latest MMS parts in batches of up to 500 ids. A batch that fails as
  `unavailable` or `transientFailure` still leaves an empty preview; any
  other error, including `permissionDenied` and `invalidQuery`, now throws
  instead of returning the list with empty previews.
- **Contact lookup by address trims and percent-encodes it (UNFY-253).**
  An address with `+`, `#`, spaces or `@` now reaches the contacts filter
  intact, and a blank address returns null without a query.

## 0.5.1

- Require `simple_query ^0.2.2`, whose Android implementation preserves
  observer row identifiers, notification flags, and `selfChange`.

### Added

- `MessageChangeEvent.selfChange` now preserves the raw Android observer
  self-change marker in the public Dart event model. It does not identify
  application writes; suppressing reconciliation requires separate write
  correlation.

### Fixed

- Corrected the event documentation to reflect row identifiers supplied by
  Android row URI notifications.

## 0.5.0

### Changed (breaking)

- **Normalized thread reads are now bounded and newest-first (UNFY-209).**
  Replace `LookupService.getNormalizedMessagesByThread(threadId, ascending: ...)`
  with `getNormalizedThreadPage(threadId, limit: pageSize)`. The replacement
  requires `limit`, always returns newest-first pages, and removes caller-selected
  ascending order. Resume with
  `cursor: ThreadPageCursor.fromMessage(previousPage.last)` until a page contains
  fewer than `pageSize` messages.

### Added

- **Bounded thread pages with a composite cursor (UNFY-209).**
  `LookupService.getNormalizedThreadPage` accepts `ThreadPageCursor` to resume
  through timestamp ties using native id and SMS/MMS channel. Native date
  ordering includes an id tie-break before limiting; only returned MMS rows
  are hydrated. Timestamp-only `before` remains supported and exclusive.

- **Native mark-unread write surface.** `AndroidAction.markMessageAsUnread`
  (channel-qualified, symmetric to `markMessageAsRead`) and
  `AndroidAction.markConversationAsUnread` set the native `READ` flag back to
  `0` via `ContentResolver`. `SEEN` is deliberately left untouched so a message
  re-surfaces as unread without re-triggering a notification. Enables
  native-authoritative read-state (mark-unread) for consumers (UNFY-205 /
  ADR-0015); additive, non-breaking.

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
