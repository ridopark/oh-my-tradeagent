-- Least-privilege WRITE role for the operator tenant-user-invite feature (V4). The BFF's writer
-- datasource connects as this role to (a) INSERT a pending invite and (b) on a person's first sign-in
-- INSERT the bound identity into dashboard_user + mark the matching invite consumed. It can do NOTHING
-- else: no UPDATE/DELETE/SELECT on dashboard_user (the bind is INSERT ... ON CONFLICT DO NOTHING and
-- never reads back), no DELETE on invites, and no access to any other table. dashboard_readonly (V2)
-- is untouched and stays strictly SELECT-only.
--
-- The real password is injected at MIGRATION time via the Flyway placeholder
-- `${dashboard_writer_password}` (bound from spring.flyway.placeholders.dashboard_writer_password
-- <- the DASHBOARD_WRITER_PASSWORD env / k8s Secret), exactly like V2's dashboard_readonly: no
-- repo-readable literal, and an unset password fails placeholder resolution at boot BEFORE the role
-- is ever created. The BFF's writer datasource reads the SAME DASHBOARD_WRITER_PASSWORD to connect as
-- dashboard_writer. Flyway checksums the raw (pre-substitution) text, so the migration is stable
-- across environments. Idempotent: IF NOT EXISTS means a later password rotation is an explicit
-- `ALTER ROLE dashboard_writer PASSWORD ...`, not a re-run of this migration.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dashboard_writer') THEN
    CREATE ROLE dashboard_writer LOGIN INHERIT PASSWORD '${dashboard_writer_password}';
  END IF;
END
$$;

-- DB-level connect + schema usage. Dynamic SQL so this is portable across production (DB name
-- "dashboard"), Testcontainers (random DB name), and local dev.
DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO dashboard_writer', current_database());
END
$$;
GRANT USAGE ON SCHEMA public TO dashboard_writer;

-- dashboard_user: INSERT ONLY (provisioning a bound identity). No UPDATE/DELETE/SELECT — the bind is
-- an INSERT ... ON CONFLICT DO NOTHING and never reads the table back.
GRANT INSERT ON dashboard_user TO dashboard_writer;

-- dashboard_user_invite: create + read-back + consume (mark consumed_at). No DELETE — revoking an
-- unconsumed invite is a deliberate out-of-scope fast-follow.
GRANT SELECT, INSERT, UPDATE ON dashboard_user_invite TO dashboard_writer;
