-- Issue #22: enforce audit_log append-only at the Postgres grant layer (not just by
-- convention) and stage the per-row hash-chain columns.
--
-- Why nullable hash columns: the chain writer + daily Merkle root job (see
-- docs/ops/audit-retention.md) is a staged follow-up; rows written before it lands carry
-- NULL. Schema-first is explicitly permitted by the issue body.
--
-- Why we do NOT revoke from the superuser / table owner: end-of-retention disposal at
-- year 7+ must remain executable via a dual-control privileged path
-- (docs/ops/audit-retention.md §5).

ALTER TABLE audit_log
  ADD COLUMN prev_hash BYTEA NULL,
  ADD COLUMN row_hash  BYTEA NULL;

COMMENT ON COLUMN audit_log.prev_hash IS
  'SHA-256 of the previous row in the per-(tenant_id, strategy_id) hash chain. NULL until the chain writer is enabled (issue #22 follow-up).';
COMMENT ON COLUMN audit_log.row_hash IS
  'SHA-256 of this row''s canonical serialization including prev_hash. NULL until the chain writer is enabled (issue #22 follow-up).';

-- NOLOGIN: deployed login roles (e.g. `temporal`, per application.yml) get membership
-- via GRANT in deployment, not in this migration. CREATE ROLE IF NOT EXISTS doesn't
-- exist before PG 17, so use a DO-block guard for idempotency.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'orchestrator_app') THEN
    CREATE ROLE orchestrator_app NOLOGIN;
  END IF;
END
$$;

REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM orchestrator_app;
GRANT  SELECT, INSERT                ON audit_log TO   orchestrator_app;
