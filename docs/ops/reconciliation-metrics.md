# Reconciliation metrics: Phase 7 live-promotion gate queries

This is the canonical reference for the two quantitative signals the Phase 7
live-promotion gate operator reads daily (criteria (c) and (d) — `docs/plans/PLAN.md`):

- **journal-broker discrepancy rate** < 0.1% of intents reconciled, daily window
- **reconciliation lag p99** < 60s, daily window

Both must hold green for N >= 20 trading days before a tenant + strategy can be
promoted to a `*-live` broker adapter. This doc gives the exact PromQL the gate
operator pastes into Prometheus / Grafana, plus an audit-log SQL fallback that
produces the same daily numbers when Prometheus scraping isn't wired yet
(homelab pre-prod, smoke tests, replay environments).

## Meters

Emitted by `ReconciliationMetricsActivities` (one call per reconciliation cycle,
issued from `ReconciliationWorkflowImpl` just before it returns its summary).
Scraped from the orchestrator's `/actuator/prometheus`.

| Meter | Type | Description |
|---|---|---|
| `reconciliation_lag_seconds` | Timer with `publishPercentileHistogram(true)` | Cycle wall-clock duration. Prometheus auto-publishes `_bucket`, `_count`, `_sum` series; p99 is computed via `histogram_quantile` against `_bucket`. |
| `journal_broker_discrepancies_total` | Counter | Sum of journal + broker orphans observed in the cycle. |
| `journal_broker_intents_reconciled_total` | Counter | Intents reconciled in the cycle (= `journal_entries_checked` on the workflow's `ReconciliationSummary`). |

All three carry the same tags: `tenant`, `strategy`, `broker_target`
(`<provider>-<env>`, e.g. `alpaca-paper`). Tag cardinality is bounded — no
per-cycle or per-correlation labels.

## PromQL — daily-window queries

Run these against the orchestrator's Prometheus scrape. The `[1d]` window matches
the gate's daily-evaluation cadence; widen / narrow with the usual care for
`rate` / `increase` semantics.

**Discrepancy rate (criterion c)**

```promql
sum(increase(journal_broker_discrepancies_total[1d]))
  /
sum(increase(journal_broker_intents_reconciled_total[1d]))
```

Threshold: < `0.001` (0.1%). Returns NaN if the denominator is 0 (no cycles ran in
the window) — the gate operator treats NaN as "no signal, do not advance" rather
than passing.

**Lag p99 (criterion d)**

```promql
histogram_quantile(
  0.99,
  sum by (le) (rate(reconciliation_lag_seconds_bucket[1d]))
)
```

Threshold: < `60` (seconds). Aggregating with `sum by (le)` collapses per-tenant
/ per-strategy series into a single fleet-wide quantile estimate; if the gate
operator wants per-tenant view, replace `sum by (le)` with `sum by (le, tenant,
strategy)`.

## Audit-log SQL fallback

When Prometheus isn't scraping the orchestrator yet (Phase 6 infra issue gates
that wiring), the same two numbers are derivable from `audit_log` rows. The
workflow emits `ReconciliationStarted` and `ReconciliationCompleted` events per
cycle, with the cycle's journal+broker counts in the completed event's
`subject` JSONB column.

**Discrepancy rate (audit fallback)**

```sql
WITH window_events AS (
  SELECT
    occurred_at,
    (subject->>'journal_orphans')::bigint   AS journal_orphans,
    (subject->>'broker_orphans')::bigint    AS broker_orphans,
    (subject->>'journal_entries_checked')::bigint AS intents
  FROM audit_log
  WHERE kind = 'ReconciliationCompleted'
    AND occurred_at >= now() - INTERVAL '1 day'
)
SELECT
  SUM(journal_orphans + broker_orphans)::numeric
    / NULLIF(SUM(intents), 0)::numeric AS discrepancy_rate
FROM window_events;
```

**Lag p99 (audit fallback)**

The workflow does not currently materialize per-cycle lag into the audit
subject — the Prometheus timer is the primary source. The fallback derives lag
by pairing `ReconciliationStarted` and `ReconciliationCompleted` rows on
`workflow_id` and taking the `occurred_at` delta:

```sql
WITH pairs AS (
  SELECT
    started.workflow_id,
    EXTRACT(EPOCH FROM (completed.occurred_at - started.occurred_at)) AS lag_seconds
  FROM audit_log started
  JOIN audit_log completed
    ON  completed.workflow_id  = started.workflow_id
    AND completed.kind = 'ReconciliationCompleted'
  WHERE started.kind = 'ReconciliationStarted'
    AND started.occurred_at  >= now() - INTERVAL '1 day'
    AND completed.occurred_at >= now() - INTERVAL '1 day'
)
SELECT percentile_cont(0.99) WITHIN GROUP (ORDER BY lag_seconds) AS lag_p99_seconds
FROM pairs;
```

`percentile_cont` is a continuous-percentile estimator on the raw samples — it
agrees with Prometheus's bucket-interpolated `histogram_quantile` to within the
timer's bucket resolution.

## Scrape wiring

`infra/prometheus.yml` does **not** need a new scrape target — the orchestrator's
`/actuator/prometheus` endpoint will be picked up under the existing additional-
services pattern once Phase 6 wires the scrape. The metrics are emitted from
day-one; ops should not expect Phase 7 gate dashboards to populate from
`docker-compose up` today.

## Observed failure modes

- **`ReconciliationMetricsRecordFailed`** audit event with non-null
  `error_class` / `error_message` — the metrics Activity threw and was
  swallowed. The reconciliation cycle still completed (the
  `ReconciliationCompleted` event was emitted just before the metrics call), so
  the audit-log SQL fallback is the authoritative source for the gate operator
  on that cycle. Investigate the underlying error class but do **not** treat
  this as a gate-failing event on its own.
- **Histogram p99 jitters** in low-traffic windows (few cycles, sparse buckets)
  — widen the rate window to `[7d]` and re-check, or fall back to the SQL
  `percentile_cont` on the raw lag samples.
- **Discrepancy rate = NaN** — no `ReconciliationCompleted` events in the
  window. The scheduler is not running the workflow; check Temporal
  `DescribeWorkflowExecution` on the `reconciliation-*` workflow IDs before
  blaming the gate.
