# trade_outcome: joining `trade_context` to realized outcomes (#783)

The #783 issue asked for a `trade_outcome` VIEW joining `trade_context` to the FIFO-paired
journal round trips. **That view cannot exist as SQL on this cluster**, so this recipe replaces
it. Why:

- `trade_context` lives in the **`dashboard`** database (written by the orchestrator's recorder
  as `trade_context_writer`; BFF migration `V13__trade_context.sql`).
- Realized fills live in the **per-broker journal databases** (`exec_alpaca_live`,
  `exec_alpaca_paper` — table `order_intent_journal`).
- Exit reasons live in the **`orchestrator`** database (`audit_log`, kind `PositionClosed`).

These are separate Postgres **databases** (not schemas) on the same `postgres-0` instance, and
Postgres cannot query across databases without `postgres_fdw`/`dblink`. Neither extension is
provisioned, and wiring the browser-adjacent dashboard DB to the live trading journal via FDW is
a needless coupling for an analysis workload. The recorder likewise never reads the journal —
broker-side stores are off-limits to it by the #783/#779 invariant — which is why
`trade_context.realized_pnl`, `exit_reason`, `alert_to_fill_latency_ms` and
`slippage_vs_alert_pct` are **null at write time** and computed here instead.

All queries are READ-ONLY. Run them on the homelab (`ssh ridopark@192.168.10.123`, pod
`postgres-0`), then join client-side (pandas / DuckDB / a spreadsheet — the volumes are hundreds
of rows per quarter).

## Query A — decision-time context (dashboard DB)

```sql
-- psql -d dashboard
SELECT signal_id, tenant_id, strategy_id, workflow_id, contract_symbol,
       entry_at, entry_premium, entry_qty, entry_bid, entry_ask, entry_spread,
       entry_iv, entry_delta, entry_gamma, entry_theta, entry_vega,
       underlying_spot, dte, moneyness, capital_weight, entry_quote_state,
       mfe_premium, mae_premium, exit_bid, exit_iv, hold_minutes, status, closed_at
FROM trade_context
ORDER BY first_observed_at;
```

## Query B — realized round trip per signal (journal DB; pick the broker DB for the tenant)

The entry BTO's journal rows carry `signal_id` = `trade_context.signal_id`. Exit rows carry the
EXIT signal's id instead, but every exit `intent_key` is prefixed by the owning position
workflow id (`<workflow_id>:exit:...`), and `trade_context.workflow_id` holds exactly that id.
ALWAYS group by `tenant_id` — prod-kipark and prod_real share one journal DB.

```sql
-- psql -d exec_alpaca_live   (or exec_alpaca_paper)
SELECT tenant_id,
       signal_id                                   AS entry_signal_id,
       min(filled_at) FILTER (WHERE side = 'BUY')  AS entry_filled_at,
       sum(filled_qty * avg_fill_price) FILTER (WHERE side = 'BUY')  AS entry_cost,
       sum(filled_qty) FILTER (WHERE side = 'BUY') AS entry_filled_qty
FROM order_intent_journal
WHERE state = 'FILLED' AND side = 'BUY'
GROUP BY tenant_id, signal_id;

-- Exit legs for one trade_context row (parameterize :workflow_id from Query A):
SELECT tenant_id, intent_key, filled_at, filled_qty, avg_fill_price
FROM order_intent_journal
WHERE state = 'FILLED' AND side = 'SELL'
  AND intent_key LIKE :workflow_id || ':exit:%';
```

Realized P&L per contract-multiplier convention:
`realized_pnl = (Σ sell filled_qty*avg_fill_price − Σ buy filled_qty*avg_fill_price) * 100`
(over the legs matched above; partial exits are just multiple SELL legs). This is the E3 FIFO
pairing from `docs/plans/PLAN-2026-08-21-position-context-correlator.md`, made repeatable.

## Query C — exit reason (orchestrator DB)

```sql
-- psql -d orchestrator
SELECT workflow_id, occurred_at,
       subject->>'close_reason' AS exit_reason,
       subject
FROM audit_log
WHERE kind = 'PositionClosed';
```

Join key: `audit_log.workflow_id = trade_context.workflow_id` (fall back to matching the
`/pos/<occ>/<signal_id>` tail when recon adoption re-minted the workflow — the signal id segment
is stable).

## Query D — the author's alert (dashboard DB, no snapshot needed)

`options_chat_message` is in the SAME database as `trade_context`, so alert-time context joins
in-DB by time window:

```sql
-- psql -d dashboard
SELECT m.message_id, m.posted_at, m.author_name, m.content
FROM trade_context t
JOIN options_chat_message m
  ON m.posted_at BETWEEN COALESCE(t.entry_at, t.first_observed_at) - interval '10 minutes'
                     AND COALESCE(t.entry_at, t.first_observed_at)
WHERE t.signal_id = :signal_id;
```

Derived at analysis time from A+B+D:

- `alert_to_fill_latency_ms` = `entry_filled_at` (B) − alert `posted_at` (D)
- `slippage_vs_alert_pct` = (`entry_premium` (A) − author's alert price parsed from D) /
  author's alert price

## Client-side join

One line per `signal_id, tenant_id`: A LEFT JOIN B on `(entry_signal_id, tenant_id)` LEFT JOIN C
on `workflow_id` LEFT JOIN D by the time window. Rows with `entry_quote_state = 'unknown'` have
a recorded-but-unquoted entry (market-data was down at first observation) — keep them, flag them.
