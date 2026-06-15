# AGENTS.md — working in `simple-sms`

Canonical guide for any agent or contributor in this repo (Claude Code
reads it via [`CLAUDE.md`](CLAUDE.md), which `@`-imports this file).
Keep it short and true.

## What this is

**`simple_sms_native`** — a modern **Android** SMS/MMS Flutter plugin: typed
inbound/outbound messaging, a lookup service, and conversation enrichment.
It is a **Simple Zen Plugin** (a library, not a consumer app), consumed by
**Unify Messages+**. Governance: the Simple Zen SOP family in Notion
(Documentation Standard, Code Quality Standards, Toolchain Architecture).
As `Type = Plugin`, the App-only gates (Linear project, Figma, consumer
Category, GTM/brand) do **not** apply; code-quality, semver/API-stability,
tests, and docs do.

It is one of **four sister repos** under a deliberate **layering
contract**: **simple-sms** (messaging, pub package `simple_sms_native`) ·
**simple-permissions** (permissions + default-SMS role, pub package
`simple_permissions_native`) · **simple-query** (ContentProvider reads,
pub package `simple_query`) · **simple-telephony** (calls, pub package
`simple_telephony_native`). simple-sms depends on the other three for
those concerns — it does not reimplement them. See "What NOT to do."

> **Naming convention used in this doc:** hyphenated names refer to the
> **repo** (`simple-sms`, GitHub); underscored names refer to the
> **pub package** the repo publishes (`simple_sms_native`, pub.dev).
> Imports and `pubspec.yaml` dependencies use the underscored form.

## Layout

```text
lib/         # Dart API
android/     # Kotlin implementation (the real work — Android-only plugin)
example/     # example app
test/        # tests
tool/        # repo tooling (one-off scripts)
scripts/     # lint scripts run by CI + the verify gate
```

Single-package plugin (not federated — the four sister repos above are
each their own published package; "federation" here describes the
inter-package layering contract, not Flutter's federated-plugin
mechanism). Dart `^3.7.0`, Flutter `>=3.27.0`.

## Build · test · verify

Mirror CI (`.github/workflows/verify.yml`). Before any push:

```sh
flutter pub get
flutter analyze --no-fatal-warnings
flutter test
flutter pub publish --dry-run     # keep it publishable
```

**When you touch `android/` (Kotlin), also run the native gate.** `flutter
test` is Dart-only — it never compiles or tests the Kotlin in `android/`, so
a native change can otherwise pass everything above with **zero Kotlin
exercised** (UNFY-162 — the hole that let the UNFY-182 MMS-send regression
reach `main`). The native unit tests must run through the *example app's*
Gradle (a bare `:simple_sms_native` invocation can't configure on its own),
so build once to inject the wrapper + `local.properties`, then test:

```sh
(cd example && flutter build apk --debug)               # injects gradlew + local.properties
(cd example/android && ./gradlew :simple_sms_native:testDebugUnitTest --console=plain)
```

On CI, the Dart gate (`verify.yml`) runs per-PR, and a path-gated **PR-time**
native gate — `verify-native.yml`, triggered by `android/**`, the example
Android project, and the `pubspec*.yaml` manifests — now runs the native build +
unit tests on **every PR that touches the native side** (UNFY-162). The *full*
APK build still also runs **at tag time** as the pre-publish gate in `deploy.yml`
(matching `verify.yml`'s own header). CI enforces this now, but the local native
gate above is still the fast way to catch a break before pushing any `android/`
change — don't wait for CI to tell you.

## Conventions that have teeth

- **The layering contract is binding** (see What NOT to do): permissions →
  `simple_permissions_native`, ContentProvider reads → `simple_query`. Don't
  duplicate either here.
- **Provider accuracy is a correctness gate.** Android SMS/MMS provider
  schemas (esp. on Samsung OEM builds) are full of footguns — verify column
  names/types/URIs against real device data before coding, never guess.
- `analysis_options.yaml` is the lint baseline; analyze must be clean.
- The design rationale lives in [`WHITEPAPER.md`](WHITEPAPER.md).

## Git workflow

`main`-only with git tags for CD releases, per the Simple Zen Toolchain
Architecture SOP (no `develop`/`staging`). Normal work: one short-lived
branch off `main` per work item; PRs target `main`.

**Release hardening** uses a **release-candidate branch off `main`** (e.g.
`release/vX.Y.Z-rc.N`): granular one-commit-per-fix, **no per-change PRs**,
and a **single roll-up PR into `main`** as the review surface. After it
merges, the **Cut Release** workflow ([`release.yml`](.github/workflows/release.yml),
manual `workflow_dispatch`) tags the commit, and the resulting tag push
fires [`deploy.yml`](.github/workflows/deploy.yml) (OIDC pub.dev publish).
See [`docs/runbooks/`](docs/runbooks/) and [`doc/RELEASE.md`](doc/RELEASE.md).

## What NOT to do (binding rulings)

- **Don't handle permissions directly.** All runtime-permission
  checks/requests and the default-SMS-app role (`RoleManager.ROLE_SMS`) go
  through **`simple_permissions_native`** (Kotlin: `PermissionGuards`).
  Never call `checkSelfPermission` / `requestPermissions` /
  `RoleManager.createRequestRoleIntent` directly. simple-sms is
  permission-*aware* (documents what each method needs, surfaces
  `SecurityException`/empty results) but never *requests*. Activity/permission
  result listeners on `SimpleSmsPlugin` decline (`return false`) so
  `simple_permissions_native` handles them.
- **Don't read ContentProviders directly.** Every Android **lookup read**
  routes through **`simple_query`** (Dart `SimpleQuery.instance.query(...)`;
  Kotlin `io.simplezen.simple_query.ContentQuery.query(...)`). Never drop to
  `ContentResolver.query` or bespoke cursor reads for a lookup. **Writes
  (insert/update/delete) stay on `ContentResolver`** — `simple_query` is
  read-only by design. Two documented read exceptions: (1) the **binary bytes
  stream** (`openInputStream` on a part) — only the bytes, not the content-type
  probe; (2) the **vendored MMS PDU codec** (`pdu_alt.PduPersister` + its
  `SqliteWrapper` / `RateController` / `SubscriptionIdChecker` helpers), whose
  reads are binary PDU reconstruction (positional column-index + BLOB +
  exact-type + `Cursor.count`/exception semantics that `ContentQuery` can't
  represent) — direct by design (UNFY-156).
- **Don't guess provider column semantics.** Verify URI + join columns +
  value shapes against real device-provider data before coding (watch
  `thread_id` vs `_id`, `mid`, `msg_id`, `contact_id`; `?simple=true` row
  shapes; Samsung OEM columns/types).
- **Don't break the public Dart API without a semver-appropriate bump** —
  Unify Messages+ and pub.dev consumers depend on it.
- **Don't push without the verify gate green.** CI is a backstop, not
  discovery.
- **Don't commit secrets.**
- **Don't add app-level concerns** here (GTM, brand, product roadmap) —
  this is a library.
