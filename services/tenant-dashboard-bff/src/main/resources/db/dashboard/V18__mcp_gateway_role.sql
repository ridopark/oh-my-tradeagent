-- Least-privilege role for the remote MCP gateway (PLAN-2026-11-13-mcp-remote-oauth, Step 2).
--
-- D1 (orchestrator, 2026-11-13): the role is created as NOLOGIN — no password, no Flyway placeholder.
-- Rationale: a password-placeholder with no default would crash-loop the BFF if PR 2 merged before
-- the secret was present in dashboard-secrets (dashboard + all operator buttons down). The operator
-- sets the password at enablement time (ALTER ROLE mcp_gateway LOGIN PASSWORD '<secret>'; see the PR 5
-- runbook). The grants are applied now so the role is fully provisioned the moment the operator enables it.
--
-- Idempotent: IF NOT EXISTS means a later password rotation or the initial LOGIN enablement is an
-- explicit ALTER ROLE, not a re-run of this migration.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mcp_gateway') THEN
    CREATE ROLE mcp_gateway NOLOGIN;
  END IF;
END
$$;

-- DB-level connect + schema usage. Dynamic SQL so this is portable across production (DB name
-- "dashboard"), Testcontainers (random DB name), and local dev.
DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO mcp_gateway', current_database());
END
$$;
GRANT USAGE ON SCHEMA public TO mcp_gateway;

-- Column-level SELECT on dashboard_user: EXACTLY the three columns the gateway needs.
--   * provider, subject  — the WHERE predicate of the membership query
--                          (SELECT provider, subject, tenant_id FROM dashboard_user
--                           WHERE provider = ? AND subject = ?)
--                          PG16: WHERE column needs SELECT on that column.
--   * tenant_id          — the projected result.
-- NOT granted: email and any other column (PII, unneeded by the gateway).
-- NOT granted: dashboard_user_invite or any other table.
GRANT SELECT (provider, subject, tenant_id) ON dashboard_user TO mcp_gateway;

-- Full CRUD on the six OAuth tables. The gateway creates, reads, updates and expires all of them:
--   * SELECT  — membership re-check per request (JOIN with grant + revoked_at), token lookup, listing.
--               Also required by ON CONFLICT and UPDATE WHERE (PG16 rule; table-level SELECT covers
--               all columns used in those predicates without enumerating them).
--   * INSERT  — mint codes, refresh tokens, access tokens; store pending auth; register clients; create grants.
--   * UPDATE  — rotate refresh tokens, revoke grants, bump last_used_at, mark codes consumed.
--   * DELETE  — sweep expired-and-never-used clients.
GRANT SELECT, INSERT, UPDATE, DELETE ON
  mcp_oauth_client,
  mcp_oauth_pending_auth,
  mcp_oauth_grant,
  mcp_oauth_code,
  mcp_oauth_refresh_token,
  mcp_oauth_access_token
TO mcp_gateway;
