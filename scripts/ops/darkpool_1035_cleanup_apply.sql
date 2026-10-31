-- darkpool_1035_cleanup_apply.sql — guarded cleanup for #1035 (darkpool per-Trade keying fix).
--
-- Runs in one transaction.  Aborts (ROLLBACK) if the counts differ from what the
-- dry run showed — re-run the dry run and pass the updated numbers.
--
-- NEVER run this file via homelab_psql.sh (which is SELECT-by-convention).
-- Run as the `temporal` superuser:
--
--   ssh ridopark@192.168.10.123 \
--     "kubectl -n copytrade exec -i postgres-0 -- psql -U temporal -d dashboard \
--       -v ON_ERROR_STOP=1 -v expected_delete=1831 -v expected_relabel=2 -f -" \
--     < scripts/ops/darkpool_1035_cleanup_apply.sql
--
-- Prerequisites:
--   1. darkpool_1035_cleanup_dryrun.sql has been run and its totals recorded.
--   2. The collector image rolled with fix #1035 (so no new bad rows are ingested).
--   3. This file has NOT been modified since the dry run; edit it only to correct
--      the predicates, then re-run the dry run to get fresh expected counts.
--
-- Alternative to running this file: leave history as-is and let the 90-day retention
-- sweep remove the rows by 2027-01-28.  The /dark-pool page will show inflated numbers
-- until then; bar buy/sell volumes are unaffected (the plan confirms this).

BEGIN;

-- ---------------------------------------------------------------------------
-- Temp table to hold the operator-supplied expected counts.
-- psql variable substitution (:expected_delete, :expected_relabel) happens
-- here, outside PL/pgSQL, so the values are substituted as SQL literals.
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE _cleanup_params (
    expected_delete  BIGINT NOT NULL,
    expected_relabel BIGINT NOT NULL
) ON COMMIT DROP;

INSERT INTO _cleanup_params VALUES (:expected_delete, :expected_relabel);

-- ---------------------------------------------------------------------------
-- Guard 1: abort if the DELETE count changed since the dry run.
-- The predicate below is IDENTICAL to the dry run's Section 1c predicate.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    actual   BIGINT;
    expected BIGINT;
BEGIN
    SELECT expected_delete INTO expected FROM _cleanup_params;

    SELECT count(*) INTO actual
    FROM   darkpool_print dp
    WHERE  (dp.exchange IS DISTINCT FROM 'D'
            OR (dp.exchange = 'D' AND dp.notional < 200000 AND dp.size < 10000))
      AND  EXISTS (
               SELECT 1 FROM darkpool_print dp2
               WHERE  dp2.symbol   = dp.symbol
                 AND  dp2.trade_id = dp.trade_id
                 AND  dp2.id       != dp.id
           );

    IF actual != expected THEN
        RAISE EXCEPTION
            'DELETE guard: actual count % != expected %; '
            'dry-run counts changed — re-run the dry run and pass updated values',
            actual, expected;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- Guard 2: abort if the RELABEL count changed since the dry run.
-- The predicate below is IDENTICAL to the dry run's Section 2b predicate.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    actual   BIGINT;
    expected BIGINT;
BEGIN
    SELECT expected_relabel INTO expected FROM _cleanup_params;

    SELECT count(*) INTO actual
    FROM   darkpool_print
    WHERE  exchange = 'D'
      AND  (notional >= 200000 OR size >= 10000)
      AND  quote_ts IS NOT NULL
      AND  (quote_ts > ts OR ts - quote_ts > interval '5 seconds');

    IF actual != expected THEN
        RAISE EXCEPTION
            'RELABEL guard: actual count % != expected %; '
            'dry-run counts changed — re-run the dry run and pass updated values',
            actual, expected;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- Step 1: DELETE lit-exchange and below-threshold D rows that have a twin.
-- Same predicate as dry run Section 1c.
-- ---------------------------------------------------------------------------
WITH twins AS (
    SELECT symbol, trade_id
    FROM   darkpool_print
    GROUP  BY symbol, trade_id
    HAVING count(*) > 1
)
DELETE FROM darkpool_print dp
USING  twins
WHERE  dp.symbol   = twins.symbol
  AND  dp.trade_id = twins.trade_id
  AND  (dp.exchange IS DISTINCT FROM 'D'
        OR (dp.exchange = 'D' AND dp.notional < 200000 AND dp.size < 10000));

-- ---------------------------------------------------------------------------
-- Step 2: Capture bar-adjustment deltas for relabelled rows that were buy/sell.
-- Must run BEFORE the UPDATE so old side values are still readable.
-- Same predicate as dry run Section 3a (restricted to buy/sell only).
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE _bar_adj (
    symbol     TEXT        NOT NULL,
    bucket     TIMESTAMPTZ NOT NULL,
    adj_buy    BIGINT      NOT NULL DEFAULT 0,
    adj_sell   BIGINT      NOT NULL DEFAULT 0
) ON COMMIT DROP;

INSERT INTO _bar_adj (symbol, bucket, adj_buy, adj_sell)
SELECT
    dp.symbol,
    (
        date_trunc('minutes', dp.ts AT TIME ZONE 'America/New_York')
        - (extract(minute FROM dp.ts AT TIME ZONE 'America/New_York')::int % 5) * interval '1 minute'
    ) AT TIME ZONE 'America/New_York'                                    AS bucket,
    sum(CASE WHEN dp.side = 'buy'  THEN dp.size ELSE 0 END)             AS adj_buy,
    sum(CASE WHEN dp.side = 'sell' THEN dp.size ELSE 0 END)             AS adj_sell
FROM   darkpool_print dp
WHERE  dp.exchange = 'D'
  AND  (dp.notional >= 200000 OR dp.size >= 10000)
  AND  dp.quote_ts IS NOT NULL
  AND  (dp.quote_ts > dp.ts OR dp.ts - dp.quote_ts > interval '5 seconds')
  AND  dp.side IN ('buy', 'sell')
GROUP  BY 1, 2;

-- ---------------------------------------------------------------------------
-- Step 3: RELABEL kept D rows with stale/future quote.
-- Same predicate as dry run Section 2a/2b.
-- ---------------------------------------------------------------------------
UPDATE darkpool_print
SET    bid      = NULL,
       ask      = NULL,
       quote_ts = NULL,
       side     = 'unknown'
WHERE  exchange = 'D'
  AND  (notional >= 200000 OR size >= 10000)
  AND  quote_ts IS NOT NULL
  AND  (quote_ts > ts OR ts - quote_ts > interval '5 seconds');

-- ---------------------------------------------------------------------------
-- Step 4: Adjust bars for any relabelled rows that were buy/sell.
-- Expected 0 rows affected today; included so the rule is explicit.
-- ---------------------------------------------------------------------------
UPDATE darkpool_bar_5m b
SET    buy_volume  = b.buy_volume  - a.adj_buy,
       sell_volume = b.sell_volume - a.adj_sell
FROM   _bar_adj a
WHERE  b.symbol = a.symbol
  AND  b.bucket = a.bucket;

COMMIT;
