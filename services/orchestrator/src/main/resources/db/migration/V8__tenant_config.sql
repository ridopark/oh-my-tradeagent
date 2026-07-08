-- account-loss-cap-db epic (Phase 1): stand up a DB-backed store for the tenant-level account
-- daily-loss cap (account_daily_loss_threshold / account_daily_loss_pct). ADDITIVE +
-- behavior-neutral — nothing reads this table while tenant.config.source=yaml (the default). A
-- boot seed reconciler back-fills rows from the mounted tenants/ tree (insert-if-absent,
-- idempotent) so the DB is warm before an operator flips the read source to db. Mirrors the V5
-- strategy_config precedent.
--
-- The cap is two nullable NUMERIC columns rather than a JSONB blob: unlike StrategyConfig (~50
-- evolving fields) the tenant cap is a fixed, tiny shape. A null column disables that cap
-- dimension (absolute or pct), matching TenantConfig's "null threshold => cap inert" opt-out.
-- `version` is a BIGINT optimistic-concurrency counter starting at 1 (the Phase 3 write path
-- increments it). `updated_by` records the last writer (seed:boot for the reconciler).
CREATE TABLE tenant_config (
  tenant_id                     VARCHAR(64)  PRIMARY KEY,
  account_daily_loss_threshold  NUMERIC,
  account_daily_loss_pct        NUMERIC,
  version                       BIGINT       NOT NULL DEFAULT 1,
  updated_at                    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_by                    VARCHAR(128) NOT NULL
);

-- The orchestrator runtime path connects as the constrained `orchestrator_runtime` role (V4). The
-- boot seed reconciler INSERTs and DbTenantRegistry SELECTs on that connection, so grant explicit
-- per-table SELECT/INSERT (principle of least privilege — not GRANT ALL; matches V4/V5 style).
-- UPDATE is deliberately WITHHELD in Phase 1 (no in-place mutation path exists yet — the Phase 3
-- tenant-editable write path adds the UPDATE grant when it lands, exactly as V5→V6 did for
-- strategy_config).
GRANT SELECT, INSERT ON tenant_config TO orchestrator_runtime;
