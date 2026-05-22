---
name: Release-candidate branch workflow — granular commits, single roll-up PR to main
description: For release hardening, don't open a PR per change. Each change is its own commit on a release-candidate branch off main; only the release branch → main roll-up PR gets opened for review. Tagging main triggers CD.
type: feedback
---
For remediation/hardening work against a release-candidate branch (e.g. `release/v0.5.0-rc.1`, branched off `main`): one commit per change, all landed on the release branch, no intermediate per-change PRs. Only the roll-up PR from the release branch into `main` gets opened — that's the one the user reviews and approves. Once merged, **tagging `main` triggers the CD release** (the `cut_release` / `release.yml` + `deploy.yml` workflows).

**Why:** The user wants to batch-review the full set of fixes as a single coherent release rather than churning through dozens of tiny PRs. Per-change commits keep the git history granular (so any single fix can be reverted or cited), but intermediate PRs are pure overhead for this workflow. The user said explicitly: "we don't need a PR for every change — just make sure each change has its own commit, and that all commits are merged locally onto the release branch. The only PR needed is to merge the release branch into main." This stays inside the main-only model: a release branch is a (longer-lived) feature branch, and the roll-up is its PR-to-main.

**How to apply:**
- Work directly on the release branch (or short-lived local branches that fast-forward back onto it — no `origin/<feature>` push, no PR per change).
- Each item in the hardening backlog gets its own commit with a descriptive Conventional-Commits message (`fix(conversations): …`, `chore(deps): remove unused pigeon dev dep`, etc.).
- Keep commits focused and small — one bug, one refactor, one deletion. Don't bundle.
- Push to the release branch's remote only to refresh the existing `release/... → main` PR, never to create new PRs for sub-changes.
- Before pushing, rebase / keep history linear if reasonable; don't force-push over work the user has already seen without saying so.
- When the release branch is "ready," the existing PR into `main` is the review surface. After it merges, tag `main` to ship via CD.
