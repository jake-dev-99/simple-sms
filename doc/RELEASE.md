# Release flow

Three-branch promotion pipeline with pub.dev publishing gated on
`main`:

```
feature branch
     │  PR  ▼  (CI runs)
  develop
     │  PR  ▼  (CI runs)
  staging
     │  PR  ▼  (CI runs)
    main
     │  push to main ▼  (auto-tag bumps version + tags commit)
     │  tag push ▼      (publish.yml runs OIDC pub.dev release)
    pub.dev
```

## Branch intent

| Branch | Role |
|---|---|
| `develop` | Default working branch. Feature branches PR here. Represents "what's shipping next, eventually." |
| `staging` | Pre-release gate. Merging `develop` -> `staging` signals "this is the shape of the next release; soaking before it hits prod." Pre-release pub.dev publishes (e.g. `0.4.0-dev.1`) can cut from here. |
| `main` | Production. Merging `staging` -> `main` is the release moment. Tag pushes on `main` trigger pub.dev publishing. |

CI runs on PRs to any of the three. CD (pub.dev publishing) runs
on **tag push** — a merge to `main` doesn't auto-publish; tagging
is the explicit release gesture.

## Cutting a release

1. Land your work on `develop` via PRs.
2. `develop` -> `staging` PR. CI runs. Merge.
3. `staging` -> `main` PR. CI runs. Merge.
4. The push to `main` triggers
   [`auto-tag.yml`](../.github/workflows/auto-tag.yml), which:
   - Reads the current `pubspec.yaml` version.
   - Finds the highest existing `simple_sms_native-v<semver>`
     tag.
   - Picks the next version with
     `max(pubspec_version, highest_tag + 0.0.1)`. Default
     path is a patch bump; an explicit minor/major bump in the
     pubspec wins over the patch default.
   - Rewrites the pubspec, commits with `[skip ci]`, tags,
     pushes.
5. The tag push fires
   [`publish.yml`](../.github/workflows/publish.yml), which
   verifies the tag version matches `pubspec.yaml` and runs
   `dart pub publish --force`. pub.dev authenticates via OIDC
   — no long-lived credentials.

**Shipping a minor or major release** is just *"bump the
pubspec on the merge PR"*. The auto-tagger respects it.

## Tag pattern

simple-sms is a single-package repo (not federated):

| Package | Tag prefix | Working dir |
|---|---|---|
| `simple_sms_native` | `simple_sms_native-v` | `.` (repo root) |

## One-time pub.dev setup

Before the first tag-triggered release, configure pub.dev:

1. Visit `https://pub.dev/packages/simple_sms_native/admin`.
2. Enable **Automated publishing** -> *Publishing from GitHub Actions*.
3. Fill in:
   - **Repository**: `<owner>/simple-sms`
   - **Tag pattern**: `simple_sms_native-v{{version}}`
4. Save.

Without this, `dart pub publish` from the workflow errors with
`missing OIDC authorization` and the release fails cleanly — the
package is never half-published.

## Why this shape

- **CI on PR opened.** Catches breakage before merge. `push`-
  triggered CI was dropped because every landed change already
  passed the PR check; running again on merge is redundant spend.
- **CD on tag, not on merge.** Merges to `main` shouldn't force
  a pub.dev release — sometimes a merge is a doc fix, a revert,
  or a regression hotfix that's not ready to publish. The tag is
  the intent-to-release signal.
- **OIDC (no stored tokens).** Long-lived `PUB_DEV_CREDENTIALS`
  in a GitHub secret is the old pattern; OIDC is the current
  pub.dev recommendation and leaves no credential to steal.
