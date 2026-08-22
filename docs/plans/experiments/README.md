# Baseline experiment (2026-08-21)

Reproduces E1/E2 recorded in `../PLAN-2026-08-21-position-context-correlator.md`.

**Inputs** (fetched read-only via the market-data pod's credentials — they never leave the pod):
- `chat.tsv` — `dashboard.options_chat_message`, channel `786109983065505792` (option-chat),
  columns: `message_id, epoch_seconds, author, content`
- `bars.ndjson` — Alpaca `/v2/stocks/bars?timeframe=1Min&feed=sip`, one JSON object per line:
  `{"s":sym,"t":iso,"c":close,"o":open,"v":vol}`

**Re-run condition:** once >=3 months of collected history exists. Same horizons (5/15/30/60m),
same `|t|>=2` bar, same 8 signals. Compare against the 2026-08-21 numbers in the plan — do NOT
re-derive with a different method.

**What must be added before any result is called tradeable:** spread cost and delta leverage.
The 2026-08-21 run found effects of ~0.02% against an options spread of 9.4% on a held contract.
