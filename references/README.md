# References

Third-party material we've borrowed from or studied, kept here verbatim with attribution. Each subdirectory contains the original `LICENSE` plus a `NOTICE.md` describing exactly which files were lifted and how we've adapted them.

## What's here

| Source | License | What we borrowed | Mapped into |
|---|---|---|---|
| [TauricResearch/TradingAgents](https://github.com/TauricResearch/TradingAgents) | Apache-2.0 | `memory.py` (append-only markdown decision log), `reflection.py` (reflection prompt + flow) | `orchestrator/memory/`, `orchestrator/agents/reflection.md` |
| [enving/TradeAgent](https://github.com/enving/TradeAgent) | MIT | `alpaca_client.py` (bracket-order construction + Alpaca quirks), `orch_prompts.py` (five JSON-output orchestration prompts) | `brokers/alpaca/`, `orchestrator/agents/*.md` |

## What's NOT here

**HKUDS/AI-Trader** was investigated and intentionally skipped. The repo has no `LICENSE` file (the MIT badge in the README is not a license grant), and its `skills/*.md` content is API-client documentation for the `ai4trade.ai` SaaS rather than self-contained intraday-trading logic. We use it only as an external structural reference (YAML-frontmatter routing-skill pattern) and copy nothing from it. If the maintainers add an explicit license, we can revisit.

## Rules for adding more

1. Verify a permissive license (MIT, Apache-2.0, BSD) before copying.
2. Copy the original `LICENSE` file alongside the borrowed source.
3. Write a `NOTICE.md` that lists exactly which files were taken, any modifications, and where they map into our codebase.
4. Keep borrowed files unmodified in `references/`. Any adaptations live in our own tree (and reference the source path in code comments where relevant).
