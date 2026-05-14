-- Phase 2b OrderIntentJournal. Source of truth for the second of three
-- idempotency layers (plan line 362): Temporal workflow_id REJECT_DUPLICATE →
-- OrderIntentJournal intent_key → broker client_order_id.
--
-- Each row is the durable record of one broker order attempt; state machine is
-- enforced by the application + Postgres CHECK (avoids the pain of altering a
-- true Postgres ENUM later).
CREATE TABLE order_intent_journal (
  intent_key          VARCHAR(192) PRIMARY KEY,
  signal_id           VARCHAR(96)  NOT NULL,
  tenant_id           VARCHAR(64)  NOT NULL,
  strategy_id         VARCHAR(64)  NOT NULL,
  broker_target       VARCHAR(8)   NOT NULL CHECK (broker_target IN ('paper','live')),
  client_order_id     VARCHAR(192) NOT NULL,
  option_symbol       VARCHAR(32)  NOT NULL,
  side                VARCHAR(4)   NOT NULL CHECK (side IN ('BUY','SELL')),
  qty                 BIGINT       NOT NULL CHECK (qty > 0),
  limit_price         NUMERIC(18,4),
  state               VARCHAR(16)  NOT NULL
                      CHECK (state IN ('RECORDED','SUBMITTED','FILLED','CANCELLED','EXPIRED','ERRORED')),
  broker_order_id     VARCHAR(96),
  recorded_at         TIMESTAMPTZ  NOT NULL,
  submitted_at        TIMESTAMPTZ,
  last_state_at       TIMESTAMPTZ  NOT NULL,
  cancel_attempted_at TIMESTAMPTZ,
  last_error          TEXT,
  version             BIGINT       NOT NULL DEFAULT 0
);

-- For reconciliation queries (Phase 5) and operator dashboards: find all rows
-- in a non-terminal state for a (tenant, strategy).
CREATE INDEX order_intent_journal_tenant_strategy_state_idx
  ON order_intent_journal (tenant_id, strategy_id, state);

-- For "what is the journal state for this broker order?" reverse lookups.
CREATE INDEX order_intent_journal_broker_order_id_idx
  ON order_intent_journal (broker_order_id)
  WHERE broker_order_id IS NOT NULL;
