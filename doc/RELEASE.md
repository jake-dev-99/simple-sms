# Release flow

`main`-only with tag-driven pub.dev publishing, per the Simple Zen
Toolchain Architecture SOP.

```
feature branch
     │  PR  ▼  (CI runs)
    main
     │  push to main ▼  (auto-tag bumps version + tags commit)
     │  tag push ▼      (deploy.yml runs OIDC pub.dev release)
    pub.dev
```

## Branch intent

| Branch | Role |
|---|---|
| `main` | Production. Feature branches PR directly here. The push to `main` triggers auto-tagging; the tag push triggers the pub.dev publish. |

`develop` / `staging` branches were retired — solo-dev ops doesn't justify
a multi-branch promotion pipeline. CI runs on every PR; CD (pub.dev
publishing) runs on **tag push**, not on the merge.

## Cutting a release

1. Land your work on `main` via a PR. CI runs; merge.
2. The push to `main` triggers
   [`auto-tag.yml`](../.github/workflows/auto-tag.yml), which:
   - Reads the current `pubspec.yaml` version.
   - Finds the highest existing `simple_sms_native-v<semver>` tag.
   - Picks the next version with
     `max(pubspec_version, highest_tag + 0.0.1)`. Default path is a
     patch bump; an explicit minor/major bump in the pubspec wins over
     the patch default.
   - Rewrites the pubspec, commits with `[skip ci]`, tags, pushes.
3. The tag push fires
   [`deploy.yml`](../.github/workflows/deploy.yml), which verifies the
   tag version matches `pubspec.yaml` and runs `dart pub publish
   --force`. pub.dev authenticates via OIDC — no long-lived credentials.

**Shipping a minor or major release** is just *"bump the pubspec on the
merge PR"*. The auto-tagger respects it.

## Tag pattern

simple-sms is a single-package repo (not federated):

| Package | Tag prefix | Working dir |
|---|---|---|
| `simple_sms_native` | `simple_sms_native-v` | `.` (repo root) |

## One-time pub.dev setup

Before the first tag-triggered release, configure pub.dev:

1. Visit `https://pub.dev/packages/simple_sms_native/admin`.
2. Enable **Automated publishing** → *Publishing from GitHub Actions*.
3. Fill in:
   - **Repository**: `<owner>/simple-sms`
   - **Tag pattern**: `simple_sms_native-v{{version}}`
4. Save.

Without this, `dart pub publish` from the workflow errors with
`missing OIDC authorization` and the release fails cleanly — the
package is never half-published.

## Why this shape

- **CI on PR opened.** Catches breakage before merge. `push`-triggered
  CI was dropped because every landed change already passed the PR
  check; running again on merge is redundant spend.
- **CD on tag, not on merge.** Merges to `main` shouldn't force a
  pub.dev release — sometimes a merge is a doc fix, a revert, or a
  regression hotfix that's not ready to publish. The tag is the
  intent-to-release signal.
- **OIDC (no stored tokens).** Long-lived `PUB_DEV_CREDENTIALS` in a
  GitHub secret is the old pattern; OIDC is the current pub.dev
  recommendation and leaves no credential to steal.
