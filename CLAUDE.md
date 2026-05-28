# CLAUDE.md

@AGENTS.md

The line above imports **[AGENTS.md](AGENTS.md)** — the canonical repo guide
(what this plugin is, the layering contract, build/test/verify, and the
"What NOT to do" rulings). Claude Code expands it into context
automatically; AGENTS.md is canonical so non-Claude tools read the same
source. Durable repo rulings auto-load from [`docs/memory/`](docs/memory/).
Put Claude-specific rulings below this import.
