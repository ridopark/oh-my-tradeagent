-- P0a (multi-tenant-broker-credentials epic, Part A): stand up a DB-backed store for
-- per-(tenant, strategy) StrategyConfig. ADDITIVE + behavior-neutral — nothing on the live
-- signal path reads this table in P0a; the YAML StrategyRegistry stays the active bean. A boot
-- seed reconciler back-fills rows from the mounted tenants/ tree (insert-if-absent, idempotent).
-- P0b flips the readers to DB.
--
-- The full StrategyConfig contract object (~50 fields) is stored as a single JSONB blob so the
-- store absorbs contract evolution without a per-field migration on every StrategyConfig change
-- (mirrors the audit_log.subject JSONB choice in V2). `schema_version` is a real column so a
-- reader can fail-closed on a newer-than-build row (DbStrategyRegistry). `version` is a BIGINT
-- optimistic-concurrency counter that starts at 1 (the P0c write path increments it).
CREATE TABLE strategy_config (
  tenant_id      VARCHAR(64)  NOT NULL,
  strategy_id    VARCHAR(64)  NOT NULL,
  schema_version INTEGER      NOT NULL,
  config         JSONB        NOT NULL,
  version        BIGINT       NOT NULL DEFAULT 1,
  updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_by     VARCHAR(128) NOT NULL,
  PRIMARY KEY (tenant_id, strategy_id)
);

-- The orchestrator runtime path connects as the constrained `orchestrator_runtime` role (V4).
-- The boot seed reconciler INSERTs into strategy_config on that connection and DbStrategyRegistry
-- SELECTs from it, so grant explicit per-table SELECT/INSERT (principle of least privilege — not
-- GRANT ALL; matches V4's explicit option_symbol_cache grant style). UPDATE/DELETE are withheld in
-- P0a (no in-place mutation path exists yet — the P0c write path adds the UPDATE grant when it
-- lands).
GRANT SELECT, INSERT ON strategy_config TO orchestrator_runtime;
