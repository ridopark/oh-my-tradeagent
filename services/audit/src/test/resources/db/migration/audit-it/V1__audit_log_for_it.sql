-- Minimal audit_log schema mirroring services/orchestrator V2+V3 migrations, used only by
-- AuditCompletenessVerifierIT (Testcontainers). audit-svc is a read-only consumer in
-- production; this migration exists so the IT can populate fixture rows without depending on the
-- orchestrator module's migration classpath. Kept hand-aligned with the orchestrator's V2/V3
-- when those change.

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
  subject         JSONB NOT NULL,
  prev_hash       BYTEA NULL,
  row_hash        BYTEA NULL
);

CREATE INDEX audit_log_tenant_strategy_occurred_idx
  ON audit_log (tenant_id, strategy_id, occurred_at DESC);
