-- darkpool_1035_cleanup_dryrun.sql — SELECT-only audit for #1035 (darkpool per-Trade keying fix).
--
-- Shows exactly what darkpool_1035_cleanup_apply.sql would delete, relabel, and adjust.
-- Contains NO INSERT / UPDATE / DELETE / ALTER statements; safe to run against production.
--
-- Run:
--   HOMELAB_PSQL_ALIGNED=1 scripts/data/homelab_psql.sh dashboard \
--       < scripts/ops/darkpool_1035_cleanup_dryrun.sql
--
-- Or directly:
--   ssh ridopark@192.168.10.123 \
--     "kubectl -n copytrade exec -i postgres-0 -- psql -U temporal -d dashboard -P pager=off -f -" \
--     < scripts/ops/darkpool_1035_cleanup_dryrun.sql
--
-- Expected output on 2026-10-30 data (confirmed 2026-10-31):
--   Section 1a  1,799 rows by lit-exchange, grouped by (ET date, exchange)
--   Section 1b     32 rows below-threshold D, all 2026-10-30
--   Section 1c  total 1,831
--   Section 2a       2 rows to relabel (kept D, stale/future quote_ts)
--   Section 2b  total 2
--   Section 3a       0 rows requiring bar adjustment (relabelled buy/sell rows)
--   Section 3b  total 0
--
-- Thresholds are hardcoded to the live-environment values (DARKPOOL_MIN_NOTIONAL=200000,
-- DARKPOOL_MIN_SIZE=10000) because the apply file is also hardcoded to them; a config
-- change that changes the thresholds would produce different counts and the apply guard
-- would trip correctly.

-- =============================================================================
-- 1.  DELETE candidates: lit-exchange rows and below-threshold D rows that have
--     a same-(symbol, trade_id) twin.  Only rows that have a twin are deleted;
--     the twin condition ensures we never touch a row that the bug did not create.
-- =============================================================================

-- 1a. DELETE candidates — lit exchange (exchange IS DISTINCT FROM 'D'), by ET date and exchange.
WITH twins AS (
    SELECT symbol, trade_id
    FROM   darkpool_print
    GROUP  BY symbol, trade_id
    HAVING count(*) > 1
)
SELECT
    (dp.ts AT TIME ZONE 'America/New_York')::date AS et_date,
    dp.exchange,
    count(*)                                      AS rows_to_delete
FROM   darkpool_print dp
JOIN   twins USING (symbol, trade_id)
WHERE  dp.exchange IS DISTINCT FROM 'D'
GROUP  BY 1, 2
ORDER  BY 1, 2;

-- 1b. DELETE candidates — below-threshold D rows (notional < 200000 AND size < 10000),
--     grouped by ET date (exchange is always 'D' here).
WITH twins AS (
    SELECT symbol, trade_id
    FROM   darkpool_print
    GROUP  BY symbol, trade_id
    HAVING count(*) > 1
)
SELECT
    (dp.ts AT TIME ZONE 'America/New_York')::date AS et_date,
    dp.exchange,
    count(*)                                      AS rows_to_delete
FROM   darkpool_print dp
JOIN   twins USING (symbol, trade_id)
WHERE  dp.exchange = 'D'
  AND  dp.notional < 200000
  AND  dp.size     < 10000
GROUP  BY 1, 2
ORDER  BY 1, 2;

-- 1c. Total rows to DELETE (must equal :expected_delete passed to the apply script).
WITH twins AS (
    SELECT symbol, trade_id
    FROM   darkpool_print
    GROUP  BY symbol, trade_id
    HAVING count(*) > 1
)
SELECT count(*) AS total_to_delete
FROM   darkpool_print dp
JOIN   twins USING (symbol, trade_id)
WHERE  dp.exchange IS DISTINCT FROM 'D'
   OR  (dp.exchange = 'D' AND dp.notional < 200000 AND dp.size < 10000);

-- =============================================================================
-- 2.  RELABEL candidates: kept D rows (at or above at least one threshold) whose
--     quote_ts is after the print or more than 5 s before it.  The apply script
--     sets bid/ask/quote_ts to NULL and side to 'unknown'.
-- =============================================================================

-- 2a. Individual rows to relabel (detail).
SELECT id, symbol, ts, quote_ts, side, size, notional
FROM   darkpool_print
WHERE  exchange = 'D'
  AND  (notional >= 200000 OR size >= 10000)
  AND  quote_ts IS NOT NULL
  AND  (quote_ts > ts OR ts - quote_ts > interval '5 seconds')
ORDER  BY ts;

-- 2b. Total rows to RELABEL (must equal :expected_relabel passed to the apply script).
SELECT count(*) AS total_to_relabel
FROM   darkpool_print
WHERE  exchange = 'D'
  AND  (notional >= 200000 OR size >= 10000)
  AND  quote_ts IS NOT NULL
  AND  (quote_ts > ts OR ts - quote_ts > interval '5 seconds');

-- =============================================================================
-- 3.  BAR adjustment candidates: relabelled rows whose stored side was 'buy' or
--     'sell'.  The apply script subtracts their size from darkpool_bar_5m.
--     Expected 0 today: the 2 relabelled kept D rows are labelled 'mid'/'unknown'.
-- =============================================================================

-- 3a. Individual rows requiring bar adjustment (detail).
SELECT dp.id, dp.symbol, dp.ts, dp.side, dp.size,
       (
           date_trunc('minutes', dp.ts AT TIME ZONE 'America/New_York')
           - (extract(minute FROM dp.ts AT TIME ZONE 'America/New_York')::int % 5) * interval '1 minute'
       ) AT TIME ZONE 'America/New_York' AS bar_bucket
FROM   darkpool_print dp
WHERE  dp.exchange = 'D'
  AND  (dp.notional >= 200000 OR dp.size >= 10000)
  AND  dp.quote_ts IS NOT NULL
  AND  (dp.quote_ts > dp.ts OR dp.ts - dp.quote_ts > interval '5 seconds')
  AND  dp.side IN ('buy', 'sell')
ORDER  BY dp.ts;

-- 3b. Total bar adjustments.
SELECT count(*) AS total_bar_adjustments
FROM   darkpool_print dp
WHERE  dp.exchange = 'D'
  AND  (dp.notional >= 200000 OR dp.size >= 10000)
  AND  dp.quote_ts IS NOT NULL
  AND  (dp.quote_ts > dp.ts OR dp.ts - dp.quote_ts > interval '5 seconds')
  AND  dp.side IN ('buy', 'sell');
