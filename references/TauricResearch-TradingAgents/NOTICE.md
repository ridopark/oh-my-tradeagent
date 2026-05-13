# NOTICE — TauricResearch/TradingAgents

This directory contains files copied verbatim from [TauricResearch/TradingAgents](https://github.com/TauricResearch/TradingAgents), licensed under the Apache License, Version 2.0. See `LICENSE`.

## Copied files

| File | Source path in upstream repo |
|---|---|
| `memory.py` | `tradingagents/agents/utils/memory.py` |
| `reflection.py` | `tradingagents/graph/reflection.py` |

These are kept unmodified for reference.

## How we use these patterns

- **`memory.py`** — model for `orchestrator/memory/trading_memory.md` and the `append_trading_memory` / `load_trading_memory` Activities. Adopted concepts:
  - HTML-comment hard separator (`<!-- ENTRY_END -->`).
  - Bracketed tag line: `[date | ticker | rating | outcome]`.
  - Two-phase write: pending entry on decision, append `REFLECTION:` block once outcome is known.
  - Atomic write via temp-file + `os.replace`.
  - Resolved-entry rotation under a configurable cap (pending entries are always kept).
- **`reflection.py`** — model for `orchestrator/agents/reflection.md`. Adopted concepts:
  - Terse 2–4-sentence reflection format.
  - Three-part structure: directional-call review, thesis-component review, one concrete lesson.

## Modifications in our codebase

Our adaptations live in `orchestrator/memory/` and `orchestrator/agents/reflection.md`; both reference this directory in code comments. Intraday changes: `holding_days` → `holding_minutes`; `alpha vs SPY` → intraday benchmark (e.g., SPY return over the same window).
