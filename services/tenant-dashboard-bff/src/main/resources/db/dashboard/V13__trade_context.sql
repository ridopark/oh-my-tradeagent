-- #783 trade_context: one row per copy trade, keyed by the (signal_id, tenant_id) that already
-- flows end-to-end (chat message -> entry fill -> exit fills -> realized P&L). Written by the
-- ORCHESTRATOR's trade-context recorder (the #779 floor-breach poller extended to persist what it
-- already fetches), connecting as the least-privilege trade_context_writer role below — the BFF
-- itself never writes this table; it only owns the migration because it owns this database.
--
-- Entry snapshot columns capture the fields that are UNRECOVERABLE if not captured live (IV +
-- greeks have no historical API); mfe/mae_premium are per-poll monotonic ratchets of the premium
-- path; exit columns are appended when the position disappears from Temporal Visibility.
--
-- REALIZED-OUTCOME FIELDS STAY NULL AT WRITE TIME. realized_pnl, exit_reason,
-- alert_to_fill_latency_ms and slippage_vs_alert_pct live in OTHER DATABASES (the per-broker
-- journal DBs and the orchestrator audit_log — separate Postgres DATABASES, not schemas, so no
-- in-database join exists and the recorder deliberately never reads broker-side stores). They are
-- computed at query time by the client-side join documented in docs/ops/trade-outcome-join.md.
-- The columns exist so a later backfill job MAY durably materialize them without a schema change.
--
-- Retention is indefinite by design (measured: hundreds of rows/quarter) — hence no DELETE grant.

CREATE TABLE trade_context (
  signal_id         TEXT        NOT NULL,           -- entry signal id (embedded in the workflow id)
  tenant_id         TEXT        NOT NULL,
  strategy_id       TEXT,
  workflow_id       TEXT        NOT NULL,           -- CURRENT owning PositionWorkflow; recon
                                                    -- adoption re-mints it, same signal_id
  contract_symbol   TEXT        NOT NULL,           -- padded OCC

  -- Entry snapshot (first observation of a new position on the ~1-min poll)
  entry_at          TIMESTAMPTZ,                    -- workflow's entry-fill instant (null: legacy)
  first_observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  entry_premium     NUMERIC,                        -- broker-fill cost basis per contract
  entry_qty         BIGINT,                         -- remaining qty at first observation
  entry_bid         NUMERIC,
  entry_ask         NUMERIC,
  entry_spread      NUMERIC,                        -- ask - bid at first observation
  entry_iv          NUMERIC,
  entry_delta       NUMERIC,
  entry_gamma       NUMERIC,
  entry_theta       NUMERIC,
  entry_vega        NUMERIC,
  underlying_spot   NUMERIC,
  dte               INT,                            -- days to expiry at first observation
  moneyness         NUMERIC,                        -- spot / strike (calls ITM > 1, puts ITM < 1)
  equity            NUMERIC,                        -- account equity; null (not reachable here)
  capital_weight    NUMERIC,                        -- strategy_config sizing input at entry
  entry_quote_state TEXT        NOT NULL DEFAULT 'unknown',  -- 'ok' | 'unknown' (missing quote)

  -- Running premium-path excursion (per poll; monotonic, so restarts can never reset them)
  mfe_premium       NUMERIC,                        -- max favorable: highest bid observed
  mae_premium       NUMERIC,                        -- max adverse: lowest bid observed

  -- Exit append (position gone from Visibility)
  exit_bid          NUMERIC,
  exit_iv           NUMERIC,
  realized_pnl      NUMERIC,                        -- null at write time; see header
  exit_reason       TEXT,                           -- null at write time; see header
  hold_minutes      BIGINT,
  alert_to_fill_latency_ms BIGINT,                  -- null at write time; see header
  slippage_vs_alert_pct    NUMERIC,                 -- null at write time; see header

  status            TEXT        NOT NULL DEFAULT 'open' CHECK (status IN ('open','closed')),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at         TIMESTAMPTZ,

  PRIMARY KEY (signal_id, tenant_id)
);

-- Least-privilege writer role for the orchestrator's recorder, mirroring V5's dashboard_writer
-- contract: the real password arrives at MIGRATION time via the Flyway placeholder
-- `${trade_context_writer_password}` (bound from spring.flyway.placeholders <- the
-- TRADE_CONTEXT_WRITER_PASSWORD env / k8s Secret) — no repo-readable literal, and an unset
-- password fails placeholder resolution BEFORE the role is created. The orchestrator's
-- trade-context datasource reads the SAME secret to connect. Idempotent: rotation is an explicit
-- `ALTER ROLE trade_context_writer PASSWORD ...`, not a re-run of this migration.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'trade_context_writer') THEN
    CREATE ROLE trade_context_writer LOGIN INHERIT PASSWORD '${trade_context_writer_password}';
  END IF;
END
$$;

-- DB-level connect + schema usage. Dynamic SQL so this is portable across production (DB name
-- "dashboard"), Testcontainers (random DB name), and local dev.
DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO trade_context_writer', current_database());
END
$$;
GRANT USAGE ON SCHEMA public TO trade_context_writer;

-- EXACTLY what the recorder issues, and no more:
--   * SELECT — the `INSERT ... ON CONFLICT (signal_id, tenant_id) DO NOTHING` arbiter probe reads
--     the PK index (the PG16 rule V9 documents: 42501 without it), and the close-vanished pass
--     reads back the open rows to close.
--   * INSERT — the entry row.
--   * UPDATE — the per-poll MFE/MAE ratchet and the exit append.
-- No DELETE: retention is indefinite, and a compromised recorder must not be able to destroy the
-- corpus. The other dashboard roles (readonly/writer) get NOTHING here — analysis reads happen as
-- the operator, not through the browser-facing pool.
GRANT SELECT, INSERT, UPDATE ON trade_context TO trade_context_writer;
