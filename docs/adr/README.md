# Architecture Decision Records

Short records of the load-bearing decisions behind `simple_sms_native`. Each ADR
captures **context · decision · alternatives considered · consequences ·
resolution trigger** (the last only when the decision is bounded). New
decisions land here as numbered files (`NNNN-kebab-title.md`).

| # | Decision | Status |
| --- | --- | --- |
| _none yet_ | — | — |

## Prior design notes to formalize

- [`../../WHITEPAPER.md`](../../WHITEPAPER.md) — original design rationale.
- The **four-package layering contract** — permissions/role flow goes through
  `simple_permissions_native`; ContentProvider reads go through `simple_query`.
  These bindings are stated in [`AGENTS.md`](../../AGENTS.md) "What NOT to do"
  rulings and are worth a formal ADR.

> As a Plugin (not an App), `simple-sms` has no per-concern Notion docs —
> these records are self-contained here.
