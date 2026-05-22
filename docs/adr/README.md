# Architecture Decision Records

Short records of the load-bearing decisions behind `simple_sms`. Each ADR
captures **context · decision · alternatives considered · consequences ·
resolution trigger** (the last only when the decision is bounded). New
decisions land here as numbered files (`NNNN-kebab-title.md`).

| # | Decision | Status |
| --- | --- | --- |
| _none yet_ | — | — |

## Prior design notes to formalize

- [`../../WHITEPAPER.md`](../../WHITEPAPER.md) — original design rationale.
- The **four-package layering contract** (permissions → `simple_permissions`,
  reads → `simple_query`) is a load-bearing decision — see
  [`../memory/feedback_simple_permissions_exclusivity.md`](../memory/feedback_simple_permissions_exclusivity.md)
  and [`../memory/feedback_simple_query_exclusivity.md`](../memory/feedback_simple_query_exclusivity.md);
  worth a formal ADR.

> As a Plugin (not an App), `simple-sms` has no per-concern Notion docs —
> these records are self-contained here.
