-- Issue #84: provision a dedicated non-superuser login role for the orchestrator's
-- application-runtime path so the V3 REVOKE UPDATE,DELETE,TRUNCATE on `audit_log`
-- actually binds. Postgres superusers (the default `temporal` role provisioned by
-- Temporal auto-setup) bypass all object-privilege checks unconditionally — see
-- docs/ops/audit-retention.md §4.
--
-- Role posture:
--   - LOGIN INHERIT, no SUPERUSER / CREATEDB / CREATEROLE / BYPASSRLS
--   - NOT the schema owner — `temporal` retains ownership for DDL/disposal
--   - Member of `orchestrator_app` (V3) so the REVOKE on `audit_log` inherits
--
-- Operator credential injection: this migration creates the role with a placeholder
-- password (`__SET_BY_OPERATOR__`) and ships an `ALTER ROLE ... PASSWORD '<value>'`
-- step in the PR body. Why a placeholder, not a real password baked in: migrations
-- are checked into git and Flyway has no clean Vault interpolation seam. The
-- deploy-time ALTER ROLE step is the only credential-injection point.
--
-- Flyway runs as the elevated `temporal` role via spring.flyway.user (see
-- services/orchestrator/src/main/resources/application.yml). The application
-- DataSource connects as `orchestrator_runtime` via ORCHESTRATOR_DB_USER /
-- ORCHESTRATOR_DB_PASS. This split satisfies the issue's acceptance criterion 3:
-- migrations run as a privileged role; the runtime path runs as a constrained role.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'orchestrator_runtime') THEN
    CREATE ROLE orchestrator_runtime LOGIN INHERIT PASSWORD '__SET_BY_OPERATOR__';
  END IF;
END
$$;

-- Inherit the V3 audit_log grants: SELECT, INSERT on audit_log + the REVOKE of
-- UPDATE, DELETE, TRUNCATE. Membership is the binding mechanism — once the
-- runtime path connects as this role, Postgres refuses the forbidden ops.
GRANT orchestrator_app TO orchestrator_runtime;

-- DB-level connect + schema-level usage so the role can resolve tables.
-- Use dynamic SQL so this migration is portable across production (DB name "orchestrator"),
-- Testcontainers (random DB name), and local dev environments.
DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO orchestrator_runtime', current_database());
END
$$;
GRANT USAGE   ON SCHEMA   public       TO orchestrator_runtime;

-- option_symbol_cache (V1) is mutable — ContractActivitiesImpl performs
-- on-conflict-do-nothing INSERTs into it. Grant explicit per-table privileges
-- (not GRANT ALL — principle of least privilege per issue #84). UPDATE and
-- DELETE are granted defensively for future cache-eviction logic; the V3
-- REVOKE on audit_log is unaffected because audit_log is REVOKEd at the
-- `orchestrator_app` group level and group membership cannot grant back what
-- was explicitly revoked.
GRANT SELECT, INSERT, UPDATE, DELETE ON option_symbol_cache TO orchestrator_runtime;

-- audit_log SELECT/INSERT is already inherited from `orchestrator_app` (V3).
-- The sequence grant for audit_log_id_seq is likewise inherited via V3.
-- option_symbol_cache uses a composite PK (V1) — no sequence to grant.
