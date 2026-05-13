# Agent prompts

Each `*.md` file in this directory is the **system prompt** for one agent. Prompts are the source of truth for agent behavior; the matching `*.py` is a thin wrapper that loads the markdown, declares tools, and pins the output schema.

```python
# Example loader (agents/loader.py)
def load_agent(name: str) -> str:
    return (AGENTS_DIR / f"{name}.md").read_text()

technical_agent = Agent(
    name="technical_analyst",
    instructions=load_agent("technical_analyst"),
    tools=[get_bars, compute_indicators],
    output_type=TechnicalReport,
)
```

## Files

| Agent | Prompt | Tools (Activities) | Output schema |
|---|---|---|---|
| Technical Analyst | `technical_analyst.md` | `get_bars`, `compute_indicators` | `TechnicalReport` |
| Sentiment | `sentiment.md` | `fetch_news`, `fetch_sec_filing`, `fetch_social_pulse`, `score_sentiment` | `SentimentReport` |
| Strategy (PM) | `strategy.md` | Technical & Sentiment (as tools); `get_position`, `get_balance` | `TradeProposal` |
| Risk Manager | `risk_manager.md` | `get_position`, `get_balance`, `get_quote`, `read_kill_switch`, `cancel_pending_orders` | `RiskDecision` |
| Execution | `execution.md` | `place_order`, `cancel_order`, `get_order_status` | `ExecutionReport` |
| Reflection | `reflection.md` | `read_workflow_history`, `read_daily_pnl`, `append_trading_memory` | `DailyReflection` |

## Editing rules

- These are **system prompts**, not docs. Every sentence should change agent behavior; if removing a line wouldn't change outputs, delete it.
- Schema names referenced here (e.g. `TechnicalReport`) must match the Pydantic models in `contract/python/`. Update both together.
- Versioning: keep prompt edits small and review-able. For larger reworks, create a `*.v2.md` and A/B in code rather than rewriting in place.
- Never put trade-critical numbers (prices, quantities, account balances) into the prompt itself. Those come from tools at runtime.
