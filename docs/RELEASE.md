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
     │  tag push ▼  (CD publishes to pub.dev)
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
2. When ready, open a PR `develop` -> `staging`. CI runs. Merge.
3. Open a PR `staging` -> `main`. CI runs. Merge.
4. On `main`, bump `version:` in `pubspec.yaml` + add a
   `CHANGELOG.md` entry (if not already in the merge).
5. Tag the release commit:

   ```sh
   git tag simple_sms_native-v0.4.0
   git push origin simple_sms_native-v0.4.0
   ```

   The [`publish.yml`](../.github/workflows/publish.yml) workflow
   matches the tag, verifies the pubspec version agrees, and
   runs `dart pub publish --force`. pub.dev authenticates via
   OIDC — no long-lived credentials.

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
