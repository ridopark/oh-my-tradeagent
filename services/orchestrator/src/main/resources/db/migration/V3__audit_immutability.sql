-- Issue #22: harden audit_log against tampering by (a) extending the schema with per-row
-- hash-chaining columns and (b) revoking UPDATE/DELETE/TRUNCATE from the orchestrator app
-- role. Append-only is now enforced by Postgres grants, not just by convention.
--
-- Scope of this migration:
--   1. Add `prev_hash BYTEA NULL` and `row_hash BYTEA NULL` to `audit_log`. Nullable on
--      purpose: the runtime population of these columns (hash-chain writer + daily Merkle
--      root job) is staged in a follow-up; existing rows and any rows written before that
--      lands will carry NULL. The schema is the contract; the writer is the implementation.
--   2. Create an `orchestrator_app` role (idempotent, NOLOGIN) and revoke UPDATE/DELETE/
--      TRUNCATE on `audit_log` from it. The role exists in the migration so this file is
--      self-contained against a fresh Postgres (Testcontainers, dev). In deployed
--      environments the login role used by the orchestrator service must be GRANTed
--      membership in `orchestrator_app` — see docs/ops/audit-retention.md §"DB role
--      posture" for the runbook.
--   3. Grant SELECT and INSERT to `orchestrator_app` so the orchestrator can still append
--      audit events and read them back for the operator query path.
--
-- What this migration does NOT do (and why):
--   - It does not implement the hash-chain writer or the daily Merkle root job. Those are
--     application-layer concerns staged behind the schema per the issue body, which
--     explicitly permits "runtime hashing/Merkle implementation can be staged behind the
--     spec via TODOs as long as the schema, role grants, and policy doc land".
--   - It does not REVOKE from the superuser or table owner. End-of-retention disposal
--     (year 7+) must remain executable by a privileged dual-control path; see the ops doc
--     for the documented procedure.

ALTER TABLE audit_log
  ADD COLUMN prev_hash BYTEA NULL,
  ADD COLUMN row_hash  BYTEA NULL;

COMMENT ON COLUMN audit_log.prev_hash IS
  'SHA-256 of the previous row in the per-(tenant_id, strategy_id) hash chain. NULL until the chain writer is enabled (issue #22 follow-up).';
COMMENT ON COLUMN audit_log.row_hash IS
  'SHA-256 of this row''s canonical serialization including prev_hash. NULL until the chain writer is enabled (issue #22 follow-up).';

-- Create the application role idempotently. NOLOGIN: real login roles (e.g. `temporal`,
-- per application.yml) get membership via GRANT in deployment, not in this migration.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'orchestrator_app') THEN
    CREATE ROLE orchestrator_app NOLOGIN;
  END IF;
END
$$;

REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM orchestrator_app;
GRANT  SELECT, INSERT                ON audit_log TO   orchestrator_app;
