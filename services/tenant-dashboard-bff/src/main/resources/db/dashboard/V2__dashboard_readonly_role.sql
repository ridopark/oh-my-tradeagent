-- Least-privilege login role for the Next.js dashboard web app's identity->tenant lookup.
--
-- The web app (reachable via Ingress) only ever runs `SELECT ... FROM dashboard_user` (lib/db.ts).
-- It must NOT connect as the elevated `temporal`/postgres role: a compromise of the Next.js process
-- would otherwise carry full DDL rights on the `dashboard` DB. This role can do nothing but read the
-- one mapping table. The BFF's own Flyway connection keeps using the elevated postgres-credentials
-- (DASHBOARD_DB_USER) — only this migration owns DDL here.
--
-- The real password is injected at MIGRATION time via the Flyway placeholder
-- `${dashboard_readonly_password}` (bound from spring.flyway.placeholders.dashboard_readonly_password
-- ← the DASHBOARD_READONLY_PASSWORD env / k8s Secret). This avoids the orchestrator_runtime pattern's
-- window where the role briefly exists with a repo-readable literal until an operator runs ALTER
-- ROLE — easy to miss on a cluster reset. The web app's DASHBOARD_DATABASE_URL must connect as
-- dashboard_readonly with the SAME password. Flyway checksums the raw (pre-substitution) text, so the
-- migration is stable across environments. Idempotent: IF NOT EXISTS means a later password rotation
-- is an explicit `ALTER ROLE dashboard_readonly PASSWORD ...`, not a re-run of this migration.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dashboard_readonly') THEN
    CREATE ROLE dashboard_readonly LOGIN INHERIT PASSWORD '${dashboard_readonly_password}';
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
