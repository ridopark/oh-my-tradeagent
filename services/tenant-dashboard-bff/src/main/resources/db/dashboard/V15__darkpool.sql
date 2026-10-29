-- Dark-pool (off-exchange) prints and 5-minute aggregates for the dashboard's /dark-pool page.
-- Plan: docs/plans/PLAN-2026-10-29-darkpool-prints.md (Phase 1: store + BFF ingest/read).
--
-- Phase 1 ships the STORE and the BFF write/read path only; nothing produces rows yet (the
-- REST-polling collector is Phase 2). The page is a data product, not a signal: no strategy reads
-- these tables, and nothing here sits on a trading path.
--
-- IDENTITY, NOT BIGSERIAL, for the same reason V9 spelled out: a non-owner INSERT into a BIGSERIAL
-- column also needs USAGE on the backing sequence, a grant that is easy to omit and fails only at
-- runtime as dashboard_writer. GENERATED ALWAYS AS IDENTITY attaches the sequence to the column so
-- the table INSERT grant is sufficient.

-- One row per off-exchange print the collector kept, stamped with the NBBO at the print. The
-- collector re-posts whatever it still holds after a restart, so the ingest is
-- INSERT ... ON CONFLICT (symbol, ts, trade_id) DO NOTHING — the unique key is the SIP trade
-- identity (Alpaca's `i` is unique per symbol per day; `ts` makes it unique across days).
CREATE TABLE darkpool_print (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  trade_id    BIGINT        NOT NULL,                 -- Alpaca `i`
  symbol      TEXT          NOT NULL,
  ts          TIMESTAMPTZ   NOT NULL,                 -- Alpaca `t`, the SIP timestamp
  price       NUMERIC(12,4) NOT NULL,
  size        INT           NOT NULL,
  notional    NUMERIC(16,2) NOT NULL,                 -- price * size, computed by the collector
  exchange    CHAR(1),                                -- Alpaca `x`; 'D' = FINRA ADF/TRF
  conditions  TEXT,                                   -- Alpaca `c`, space-joined
  tape        CHAR(1),                                -- Alpaca `z`
  bid         NUMERIC(12,4),                          -- NBBO just before the print, if resolved
  ask         NUMERIC(12,4),
  quote_ts    TIMESTAMPTZ,
  side        TEXT          NOT NULL CHECK (side IN ('buy', 'sell', 'mid', 'unknown')),
  ingested_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
  UNIQUE (symbol, ts, trade_id)
);

-- The two read shapes of GET /api/darkpool/prints: one symbol newest-first, and every symbol
-- newest-first. The second also serves the retention sweep (`WHERE ts < ? ORDER BY ts LIMIT ?`;
-- a btree is walked in either direction). Named, because the structure tests assert index names.
CREATE INDEX darkpool_print_symbol_ts_idx ON darkpool_print (symbol, ts DESC);
CREATE INDEX darkpool_print_ts_idx        ON darkpool_print (ts DESC);

-- Per-(symbol, 5-minute bucket) aggregates the collector builds from the FULL tape (off-exchange
-- share needs the total). A bucket is re-aggregated and re-posted as it fills, so this is an
-- upsert: INSERT ... ON CONFLICT (symbol, bucket) DO UPDATE.
CREATE TABLE darkpool_bar_5m (
  symbol       TEXT          NOT NULL,
  bucket       TIMESTAMPTZ   NOT NULL,                -- bucket START
  dp_volume    BIGINT        NOT NULL,
  dp_trades    INT           NOT NULL,
  total_volume BIGINT        NOT NULL,
  total_trades INT           NOT NULL,
  buy_volume   BIGINT        NOT NULL,                -- off-exchange volume classified buy
  sell_volume  BIGINT        NOT NULL,
  large_count  INT           NOT NULL,                -- prints at/above the collector's threshold
  large_volume BIGINT        NOT NULL,
  dp_vwap      NUMERIC(12,4),                         -- NULL when dp_volume = 0
  PRIMARY KEY (symbol, bucket)
);

-- The 400-day bar retention filters and orders on bucket alone; the PK leads with symbol and
-- cannot serve that. Without this the sweep is a sequential scan per batch, and it degrades
-- exactly as the backlog it exists to clear grows (the V12 lesson).
CREATE INDEX darkpool_bar_5m_bucket_idx ON darkpool_bar_5m (bucket);

-- GRANTS.
--
-- dashboard_writer is the BFF's only dashboard-DB DSL, so it runs the ingest, the page reads and
-- the retention sweeps. Each privilege below is one the shipped DarkpoolRepository issues:
--   * SELECT  — the page reads, AND the write path: on PG16 `INSERT ... ON CONFLICT` reads the
--               conflict-target columns, so a writer without SELECT fails with 42501 (the rule
--               V7/V9 document). The same applies to the DELETE's WHERE.
--   * INSERT  — the ingest.
--   * UPDATE  — bars ONLY: a bucket is re-posted as it fills (ON CONFLICT DO UPDATE). A print is
--               immutable, so the writer never gets UPDATE on darkpool_print.
--   * DELETE  — the nightly retention (prints at 90 days, bars at 400).
GRANT SELECT, INSERT, DELETE         ON darkpool_print  TO dashboard_writer;
GRANT SELECT, INSERT, UPDATE, DELETE ON darkpool_bar_5m TO dashboard_writer;

-- dashboard_readonly (the Next.js pg pool) may READ. Unlike the options-chat mirror (untrusted
-- third-party text, V9 withheld this on purpose) these rows are our own market-data aggregates
-- with nothing sensitive in them, and SELECT is the least privilege that lets a server-side page
-- render them. Never a write.
GRANT SELECT ON darkpool_print, darkpool_bar_5m TO dashboard_readonly;
