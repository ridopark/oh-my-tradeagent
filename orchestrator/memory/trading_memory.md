<!--
trading_memory.md — append-only daily reflection log

Format adapted from TauricResearch/TradingAgents (Apache-2.0). See:
  references/TauricResearch-TradingAgents/memory.py

Each entry below is one trading day. Entries are separated by the
HTML-comment delimiter <!-- ENTRY_END --> which cannot occur inside
LLM prose output, so the file can be split safely.

Append-only: never rewrite a prior entry. Old resolved entries may be
rotated out by the workflow when the file grows beyond its size cap.
-->

<!-- EXAMPLE ENTRY — delete once real reflections start appending -->

[2026-05-11 | aggregate | day_summary | +0.42% | n/a | n/a]

DECISION:
3 entries taken (NVDA, AAPL, AMD). Theme: ORB breakouts on tech gappers.
Regime: trend day, low realized vol after 10:30.

REFLECTION:
The 09:35 ORB framework worked on names with > 1.5x relative volume; AAPL
broke ORB but had below-average volume and faded — kept us in too long.
Specific lesson: require relative volume ≥ 1.5x in the first 5 minutes
before taking ORB entries.

<!-- ENTRY_END -->
