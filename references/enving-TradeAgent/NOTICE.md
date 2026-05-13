# NOTICE — enving/TradeAgent

This directory contains files copied verbatim from [enving/TradeAgent](https://github.com/enving/TradeAgent), licensed under the MIT License. See `LICENSE`.

## Copied files

| File | Source path in upstream repo |
|---|---|
| `alpaca_client.py` | `src/mcp_clients/alpaca_client.py` |
| `orch_prompts.py` | `src/agents/orchestrator/prompts.py` |

These are kept unmodified for reference.

## How we use these patterns

- **`alpaca_client.py`** — model for the TypeScript Alpaca Activity worker. Adopted concepts:
  - Bracket-order construction: `MarketOrderRequest` / `LimitOrderRequest` with `OrderClass.BRACKET` + `StopLossRequest` + `TakeProfitRequest`.
  - Documented quirks reused as code comments: bracket orders require whole-share quantities; round prices to 2 decimal places.
  - `paper=True` hard-coded by default — the paper/live split is a deployment concern in our setup (separate task queues), not a per-call flag.
- **`orch_prompts.py`** — five JSON-output orchestration prompts. Adopted concepts:
  - `SIGNAL_QUALITY_SCORING_PROMPT` → influences `orchestrator/agents/strategy.md`.
  - `MULTI_SIGNAL_PRIORITIZATION_PROMPT` → influences the Watch-list ranking logic and (later) multi-symbol prioritization at session start.
  - JSON-only output discipline with explicit schema-in-prompt — we instead use the OpenAI Agents SDK's `output_type=PydanticModel` for stricter typing, but the schema-in-prompt mirrors are useful documentation.

## Modifications in our codebase

Our adaptations live in `brokers/alpaca/` (TypeScript port of bracket-order logic) and `orchestrator/agents/*.md`. We do not maintain Python clones of these files.
