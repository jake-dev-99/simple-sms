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
- **Don't read ContentProviders directly.** Every Android read routes
  through **`simple_query`** (Dart `SimpleQuery.instance.query(...)`; Kotlin
  `io.simplezen.simple_query.ContentQuery.query(...)`). Never drop to
  `ContentResolver.query` or bespoke cursor reads. The one documented
  exception is the **binary bytes stream** (`openInputStream` on a part) —
  and only the bytes, not the content-type probe.
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
