-- Options flow orders and per-ticker daily summaries for the dashboard's /flow page.
-- Plan: docs/plans/PLAN-2026-10-29-flow-page.md (Step 2: BFF half, amended 2026-10-31).
--
-- The collector groups OPRA trades into orders (same contract + same side within FLOW_ORDER_WINDOW_MS)
-- and posts orders >= FLOW_STORE_MIN_PREMIUM plus per-ticker daily summaries every 60 s.
-- Nothing here sits on a trading path.
--
-- IDENTITY, NOT BIGSERIAL, for the same reason V9/V15 spelled out: a non-owner INSERT into a
-- BIGSERIAL column also needs USAGE on the backing sequence, a grant that is easy to omit and fails
-- only at runtime as dashboard_writer. GENERATED ALWAYS AS IDENTITY attaches the sequence to the
-- column so the table INSERT grant is sufficient.

-- One row per option order the collector assembled.  Immutable: the collector assigns a deterministic
-- order_id ({contract}:{first_ts_ns}:{side}) so a re-post after a restart dedupes silently.
-- "right" is a non-reserved keyword in PostgreSQL and can be used as a column name without quoting.
CREATE TABLE flow_order (
  id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_id                TEXT          NOT NULL,           -- deterministic dedup key
  ticker                  TEXT          NOT NULL,
  contract                TEXT          NOT NULL,           -- full OCC symbol
  expiry                  DATE          NOT NULL,
  strike                  NUMERIC(12,4) NOT NULL,
  right                   CHAR(1)       NOT NULL CHECK (right IN ('C', 'P')),
  first_ts                TIMESTAMPTZ   NOT NULL,
  last_ts                 TIMESTAMPTZ   NOT NULL,
  trade_count             INT           NOT NULL,
  exchange_count          INT           NOT NULL,
  size                    INT           NOT NULL,
  premium                 NUMERIC(16,2) NOT NULL,           -- total premium in dollars (price*size*100)
  vwap                    NUMERIC(12,4) NOT NULL,
  side                    TEXT          NOT NULL CHECK (side IN ('buy', 'sell', 'mid', 'unknown')),
  side_source             TEXT          NOT NULL CHECK (side_source IN ('quote', 'none')),
  bid                     NUMERIC(12,4),                    -- quote at order close, if resolved
  ask                     NUMERIC(12,4),
  quote_ts                TIMESTAMPTZ,
  sweep                   BOOLEAN       NOT NULL DEFAULT false,
  multi_leg               BOOLEAN       NOT NULL DEFAULT false,
  auction                 BOOLEAN       NOT NULL DEFAULT false,
  cross                   BOOLEAN       NOT NULL DEFAULT false,
  block                   BOOLEAN       NOT NULL DEFAULT false,
  unusual                 BOOLEAN,                          -- null when REST lookup unavailable
  day_volume              BIGINT,
  open_interest           INT,
  underlying_price        NUMERIC(12,4),
  moneyness_pct           NUMERIC(8,4),
  dte                     INT           NOT NULL,
  conditions              TEXT,                             -- raw OPRA condition codes, space-joined
  block_premium_threshold NUMERIC(16,2) NOT NULL,
  block_size_threshold    INT           NOT NULL,
  order_window_ms         INT           NOT NULL,
  quote_max_age_ms        INT           NOT NULL,
  UNIQUE (order_id)
);

-- The two paging shapes: all orders newest-first, and per-ticker newest-first. The second also
-- serves the retention sweep (WHERE first_ts < ? ORDER BY first_ts LIMIT ?). Named so the
-- structure tests can assert them.
CREATE INDEX flow_order_first_ts_idx        ON flow_order (first_ts DESC);
CREATE INDEX flow_order_ticker_first_ts_idx ON flow_order (ticker, first_ts DESC);

-- Per-(ticker, session_date) daily summary. Upserted as the collector re-posts dirty buckets every
-- 60 s: INSERT ... ON CONFLICT (ticker, session_date) DO UPDATE.
CREATE TABLE flow_ticker_day (
  ticker                TEXT          NOT NULL,
  session_date          DATE          NOT NULL,
  call_premium          NUMERIC(16,2) NOT NULL DEFAULT 0,
  put_premium           NUMERIC(16,2) NOT NULL DEFAULT 0,
  bought_premium        NUMERIC(16,2) NOT NULL DEFAULT 0,
  sold_premium          NUMERIC(16,2) NOT NULL DEFAULT 0,
  mid_premium           NUMERIC(16,2) NOT NULL DEFAULT 0,
  unknown_premium       NUMERIC(16,2) NOT NULL DEFAULT 0,
  trade_count           INT           NOT NULL DEFAULT 0,
  order_count           INT           NOT NULL DEFAULT 0,
  quoted_premium_share  NUMERIC(8,4),                       -- null when no >= floor orders
  PRIMARY KEY (ticker, session_date)
);

-- The retention sweep filters on session_date alone; the PK leads with ticker and cannot serve it
-- without a full index scan at scale.
CREATE INDEX flow_ticker_day_session_date_idx ON flow_ticker_day (session_date);

-- GRANTS.
--
-- dashboard_writer is the BFF's only dashboard-DB DSL, so it runs the ingest, the page reads and
-- the retention sweeps. Each privilege below is one the shipped FlowRepository issues:
--   * SELECT  — the page reads, AND the write path: on PG16 `INSERT ... ON CONFLICT` reads the
--               conflict-target columns, so a writer without SELECT fails with 42501 (the rule
--               V7/V9/V15 document). The same applies to the DELETE WHERE in retention.
--   * INSERT  — the ingest.
--   * UPDATE  — ticker_days ONLY: a day is re-posted as it fills (ON CONFLICT DO UPDATE). An order
--               is immutable, so the writer never gets UPDATE on flow_order.
--   * DELETE  — the nightly retention (orders at 90 days, ticker-days at 730 days).
GRANT SELECT, INSERT, DELETE         ON flow_order      TO dashboard_writer;
GRANT SELECT, INSERT, UPDATE, DELETE ON flow_ticker_day TO dashboard_writer;

-- dashboard_readonly (the Next.js pg pool) may READ. These rows are our own market-data aggregates
-- with nothing sensitive, and SELECT is the least privilege that lets a server-side page render them.
GRANT SELECT ON flow_order, flow_ticker_day TO dashboard_readonly;
