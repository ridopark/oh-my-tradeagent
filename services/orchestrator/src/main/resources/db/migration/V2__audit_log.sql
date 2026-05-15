-- Phase 5 audit_log table. Append-only append-from-activity persistence for every audit event
-- emitted by orchestrator workflows. Subject is JSONB so different event kinds can carry
-- divergent payloads without schema migrations on every audit kind addition.
--
-- The (tenant_id, strategy_id, occurred_at DESC) index supports recent-events queries and the
-- DailyPnlActivities realized-PnL composition (filter by tenant/strategy + occurred_at date).
-- The (tenant_id, kind, occurred_at DESC) index supports kind-scoped lookups (e.g. the
-- DailyPnl query filters by kind IN ('EntryFilled','PartialExitFilled')).
CREATE TABLE audit_log (
  id              BIGSERIAL PRIMARY KEY,
  schema_version  INT NOT NULL,
  tenant_id       VARCHAR(64) NOT NULL,
  strategy_id     VARCHAR(64) NOT NULL,
  event_id        UUID NOT NULL UNIQUE,
  occurred_at     TIMESTAMPTZ NOT NULL,
  kind            VARCHAR(64) NOT NULL,
  actor           VARCHAR(128),
  workflow_id     VARCHAR(256),
  correlation_id  VARCHAR(96),
  subject         JSONB NOT NULL
);

CREATE INDEX audit_log_tenant_strategy_occurred_idx
  ON audit_log (tenant_id, strategy_id, occurred_at DESC);

CREATE INDEX audit_log_tenant_kind_occurred_idx
  ON audit_log (tenant_id, kind, occurred_at DESC);
