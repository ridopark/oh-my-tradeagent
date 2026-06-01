-- Least-privilege login role for the Next.js dashboard web app's identity->tenant lookup.
--
-- The web app (reachable via Ingress) only ever runs `SELECT ... FROM dashboard_user` (lib/db.ts).
-- It must NOT connect as the elevated `temporal`/postgres role: a compromise of the Next.js process
-- would otherwise carry full DDL rights on the `dashboard` DB. This role can do nothing but read the
-- one mapping table. The BFF's own Flyway connection keeps using the elevated postgres-credentials
-- (DASHBOARD_DB_USER) — only this migration owns DDL here.
--
-- Mirrors the orchestrator_runtime pattern (V4__orchestrator_runtime_role.sql): the role is created
-- with a placeholder password and the operator injects the real one at deploy time via
--   ALTER ROLE dashboard_readonly PASSWORD '<value>';
-- (migrations are in git; there is no clean Vault seam for Flyway). The web app's
-- DASHBOARD_DATABASE_URL then connects as dashboard_readonly.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dashboard_readonly') THEN
    CREATE ROLE dashboard_readonly LOGIN INHERIT PASSWORD '__SET_BY_OPERATOR__';
  END IF;
END
$$;

-- DB-level connect + schema usage. Dynamic SQL so this is portable across production (DB name
-- "dashboard"), Testcontainers (random DB name), and local dev.
DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO dashboard_readonly', current_database());
END
$$;
GRANT USAGE  ON SCHEMA public        TO dashboard_readonly;

-- SELECT ONLY on the mapping table — no INSERT/UPDATE/DELETE anywhere. Onboarding a user (an INSERT)
-- is an operator action via the elevated role, never the web app.
GRANT SELECT ON dashboard_user TO dashboard_readonly;
