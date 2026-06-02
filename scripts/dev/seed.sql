-- Local-dev seed for the tenant dashboard. Run against the `make dashboard-dev` Postgres. It does
-- two things the lightweight local stack otherwise lacks:
--   1. CREATEs the read tables the BFF queries — audit_log (orchestrator DB) and order_intent_journal
--      (exec_alpaca_paper DB). In prod these are owned by orchestrator-svc / exec-svc Flyway, which
--      aren't running here; without them /api/trades and /api/orders would 500.
--   2. INSERTs a few sample fills/orders for tenant `dev` / strategy `copytrade-v1` so Trades, Orders,
--      and the realized-PnL on Portfolio render real data. (Positions come from live Temporal
--      PositionWorkflows — still empty locally.)
-- Idempotent: tables use IF NOT EXISTS; rows are guarded by a sentinel correlation_id so re-running
-- (or `make dashboard-seed`) does not duplicate. Schema mirrors the real migrations — keep roughly
-- in sync with services/{orchestrator,exec}/.../db; only the columns the BFF reads are created.

-- ============================ orchestrator DB: audit_log ============================
\connect orchestrator

CREATE TABLE IF NOT EXISTS audit_log (
  id             BIGSERIAL PRIMARY KEY,
  tenant_id      VARCHAR(64)  NOT NULL,
  strategy_id    VARCHAR(64)  NOT NULL,
  event_id       UUID         NOT NULL UNIQUE,
  occurred_at    TIMESTAMPTZ  NOT NULL,
  kind           VARCHAR(64)  NOT NULL,
  actor          VARCHAR(128),
  workflow_id    VARCHAR(256),
  correlation_id VARCHAR(96),
  subject        JSONB        NOT NULL
);

INSERT INTO audit_log (tenant_id, strategy_id, event_id, occurred_at, kind, correlation_id, subject)
SELECT * FROM (VALUES
  ('dev','copytrade-v1', gen_random_uuid(), now() - interval '4 hours', 'EntryFilled', 'seed-dev',
   '{"option_symbol":"NVDA  260516C00140000","avg_fill_price":"2.30","filled_qty":3}'::jsonb),
  ('dev','copytrade-v1', gen_random_uuid(), now() - interval '2 hours', 'PartialExitFilled', 'seed-dev',
   '{"option_symbol":"NVDA  260516C00140000","avg_fill_price":"3.10","qty_filled":2}'::jsonb),
  ('dev','copytrade-v1', gen_random_uuid(), now() - interval '90 minutes', 'EntryFilled', 'seed-dev',
   '{"option_symbol":"CRWV  260516C00040000","avg_fill_price":"5.00","filled_qty":1}'::jsonb),
  ('dev','copytrade-v1', gen_random_uuid(), now() - interval '30 minutes', 'PartialExitFilled', 'seed-dev',
   '{"option_symbol":"CRWV  260516C00040000","avg_fill_price":"6.25","qty_filled":1}'::jsonb)
) AS s(tenant_id, strategy_id, event_id, occurred_at, kind, correlation_id, subject)
WHERE NOT EXISTS (SELECT 1 FROM audit_log WHERE correlation_id = 'seed-dev');

-- ===================== exec_alpaca_paper DB: order_intent_journal =====================
\connect exec_alpaca_paper

CREATE TABLE IF NOT EXISTS order_intent_journal (
  intent_key      VARCHAR(192) PRIMARY KEY,
  signal_id       VARCHAR(96),
  tenant_id       VARCHAR(64)  NOT NULL,
  strategy_id     VARCHAR(64)  NOT NULL,
  broker_target   VARCHAR(32),
  option_symbol   VARCHAR(32),
  side            VARCHAR(4),
  qty             BIGINT,
  limit_price     NUMERIC(18,4),
  state           VARCHAR(16),
  broker_order_id VARCHAR(96),
  recorded_at     TIMESTAMPTZ  NOT NULL,
  submitted_at    TIMESTAMPTZ,
  filled_qty      BIGINT,
  avg_fill_price  NUMERIC(18,4),
  filled_at       TIMESTAMPTZ,
  last_error      TEXT
);

INSERT INTO order_intent_journal
  (intent_key, signal_id, tenant_id, strategy_id, broker_target, option_symbol, side, qty,
   limit_price, state, broker_order_id, recorded_at, submitted_at, filled_qty, avg_fill_price, filled_at)
SELECT * FROM (VALUES
  ('seed-dev-1','seed-sig-1','dev','copytrade-v1','alpaca-paper','NVDA  260516C00140000','BUY',3,
   2.35::numeric, 'FILLED','brk-1', now() - interval '4 hours', now() - interval '4 hours', 3, 2.30::numeric, now() - interval '4 hours'),
  ('seed-dev-2','seed-sig-2','dev','copytrade-v1','alpaca-paper','NVDA  260516C00140000','SELL',2,
   3.05::numeric, 'FILLED','brk-2', now() - interval '2 hours', now() - interval '2 hours', 2, 3.10::numeric, now() - interval '2 hours'),
  ('seed-dev-3','seed-sig-3','dev','copytrade-v1','alpaca-paper','CRWV  260516C00040000','BUY',1,
   5.10::numeric, 'FILLED','brk-3', now() - interval '90 minutes', now() - interval '90 minutes', 1, 5.00::numeric, now() - interval '90 minutes'),
  ('seed-dev-4','seed-sig-4','dev','copytrade-v1','alpaca-paper','CRWV  260516C00040000','SELL',1,
   6.20::numeric, 'CANCELLED', NULL, now() - interval '20 minutes', NULL, NULL, NULL, NULL)
) AS s(intent_key, signal_id, tenant_id, strategy_id, broker_target, option_symbol, side, qty,
       limit_price, state, broker_order_id, recorded_at, submitted_at, filled_qty, avg_fill_price, filled_at)
WHERE NOT EXISTS (SELECT 1 FROM order_intent_journal WHERE intent_key = 'seed-dev-1');
