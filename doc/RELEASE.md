# Release flow

`main`-only with manual cut + tag-driven pub.dev publishing, per the
Simple Zen Toolchain Architecture SOP.

```text
feature branch
     │  PR  ▼  (CI runs)
    main
     │  release.yml workflow_dispatch ▼  (bumps pubspec + CHANGELOG, tags)
     │  tag push ▼                       (deploy.yml runs OIDC pub.dev release)
    pub.dev
```

## Branch intent

| Branch | Role |
|---|---|
| `main` | Production. Feature branches PR directly here. The "Cut Release" workflow is dispatched manually on `main`; the resulting tag push triggers the pub.dev publish. |

`develop` / `staging` branches were retired — solo-dev ops doesn't justify
a multi-branch promotion pipeline. CI runs on every PR; CD (pub.dev
publishing) runs on **tag push**, not on the merge.

## Cutting a release

1. Land your work on `main` via a PR. CI runs; merge.
2. Dispatch the **Cut Release** workflow
   ([`release.yml`](../.github/workflows/release.yml)) on `main` via
   the GitHub Actions UI (`workflow_dispatch`). Pick the bump
   (`patch` / `minor` / `major`). The workflow:
   - Resolves root and example dependencies against pub.dev, without local
     sibling overrides. An unpublished or incompatible dependency stops the
     workflow before it creates a release commit or tag.
   - Bumps `pubspec.yaml` per the selected semver level.
   - Generates a `CHANGELOG.md` entry from git log since the last tag.
   - Commits the bump + entry, tags the commit, pushes both.
3. The tag push fires
   [`deploy.yml`](../.github/workflows/deploy.yml), which verifies the
   tag version matches `pubspec.yaml`, resolves published dependencies again,
   and runs `flutter pub publish --force` with validation enabled.
   `dart-lang/setup-dart` configures pub.dev's temporary OIDC credential;
   Flutter supplies the SDK used by the package.

For the first-party MMS migration (UNFY-123), the public Dart API and channel
contracts remain stable: select **minor** (`0.5.1` → `0.6.0`). Complete the
Samsung/Verizon send-and-receive regression before cutting the release, then
bump Unify's dependency only after the version is available on pub.dev.

The old `auto-tag.yml` flow was retired — tags only exist now when a
human deliberately invokes this workflow.

## Tag pattern

simple-sms is a single-package repo (not federated):

| Package | Tag prefix | Working dir |
|---|---|---|
| `simple_sms_native` | `simple_sms_native-v` | `.` (repo root) |

## One-time pub.dev setup

Before the first tag-triggered release, configure pub.dev:

1. If the package does not exist yet, an authorized uploader must publish its
   first version interactively with `flutter pub publish` from the reviewed
   release commit, without local sibling overrides. Pub.dev cannot automate
   the creation of a new package.
2. Visit `https://pub.dev/packages/simple_sms_native/admin`.
3. Enable **Automated publishing** → *Publishing from GitHub Actions*.
4. Fill in:
   - **Repository**: `<owner>/simple-sms`
   - **Tag pattern**: `simple_sms_native-v{{version}}`
5. Save.

See [Dart's automated publishing instructions](https://dart.dev/tools/pub/automated-publishing).

Without this, `dart pub publish` from the workflow errors with
`missing OIDC authorization` and the release fails cleanly — the
package is never half-published.

## Why this shape

- **CI on PR opened.** Catches breakage before merge. `push`-triggered
  CI was dropped because every landed change already passed the PR
  check; running again on merge is redundant spend.
- **Manual release dispatch.** Every release is a deliberate human
  act, not a side effect of a merge. Doc-fix merges, reverts, and
  regression hotfixes don't accidentally publish.
- **CD on tag.** The tag push is the only path to pub.dev — deploy
  cannot run from a merge or a dispatch directly.
- **OIDC (no stored tokens).** Long-lived `PUB_DEV_CREDENTIALS` in a
  GitHub secret is the old pattern; OIDC is the current pub.dev
  recommendation and leaves no credential to steal.
