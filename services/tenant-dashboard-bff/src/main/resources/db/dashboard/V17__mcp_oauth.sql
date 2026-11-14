-- Six tables for the MCP remote-OAuth gateway (PLAN-2026-11-13-mcp-remote-oauth, Step 2).
-- Written by the mcp_gateway role (V18) and read-listed by dashboard_readonly/dashboard_writer
-- for the "Connected AI apps" panel (PR 4). All token-like values are stored as sha256 hex, never raw.

-- DCR / CIMD clients. expires_at = created_at + 30 d, pushed forward on every use; a registration
-- sweep deletes expired-and-never-used rows.
CREATE TABLE mcp_oauth_client (
  client_id    TEXT        PRIMARY KEY,
  metadata     JSONB       NOT NULL,              -- full DCR registration (client_name, redirects, …)
  client_secret_hash TEXT,                        -- sha256 of the secret; NULL for PKCE-only clients
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_used_at TIMESTAMPTZ,
  expires_at   TIMESTAMPTZ NOT NULL
);

-- One authorize flow in flight (10 min). state_hash = sha256 of the random 256-bit state.
-- csrf_hash/provider/subject/tenants are filled AFTER the Google callback; consumed by the consent POST.
CREATE TABLE mcp_oauth_pending_auth (
  state_hash TEXT        PRIMARY KEY,
  client_id  TEXT        NOT NULL,
  params     JSONB       NOT NULL,                -- PKCE code_challenge, redirect_uri, scope, …
  csrf_hash  TEXT,                                -- sha256 of the single-use CSRF token
  provider   TEXT,                                -- 'google' once the callback has run
  subject    TEXT,                                -- Google `sub` once verified
  tenants    TEXT[],                              -- member's tenants from dashboard_user
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL
);

-- One row per connected-app grant: a (client, identity, tenant-set) binding that all tokens trace to.
-- Revoked_at is set by the member (PR 4 dashboard revoke) or by refresh-token reuse detection.
CREATE TABLE mcp_oauth_grant (
  id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  client_id   TEXT        NOT NULL,
  provider    TEXT        NOT NULL,
  subject     TEXT        NOT NULL,
  tenants     TEXT[]      NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_used_at TIMESTAMPTZ,
  revoked_at  TIMESTAMPTZ
);

-- The gateway looks up grants by identity; the dashboard listing for a member also filters here.
CREATE INDEX mcp_oauth_grant_provider_subject_idx ON mcp_oauth_grant (provider, subject);

-- Short-lived (5 min) single-use code produced by the consent POST. consumed_at marks it used.
CREATE TABLE mcp_oauth_code (
  code_hash                   TEXT        PRIMARY KEY,
  grant_id                    UUID        NOT NULL REFERENCES mcp_oauth_grant,
  client_id                   TEXT        NOT NULL,
  code_challenge              TEXT        NOT NULL,
  redirect_uri                TEXT        NOT NULL,
  redirect_uri_provided_explicitly BOOLEAN NOT NULL,
  resource                    TEXT,
  scopes                      TEXT[],
  expires_at                  TIMESTAMPTZ NOT NULL,
  consumed_at                 TIMESTAMPTZ
);

-- 30-day rotating refresh tokens. rotated_at + replaced_by_hash are set when the token is rotated;
-- presenting a token with rotated_at already set triggers reuse detection (revokes the whole grant).
CREATE TABLE mcp_oauth_refresh_token (
  token_hash       TEXT        PRIMARY KEY,
  grant_id         UUID        NOT NULL REFERENCES mcp_oauth_grant,
  expires_at       TIMESTAMPTZ NOT NULL,
  rotated_at       TIMESTAMPTZ,
  replaced_by_hash TEXT
);

-- 1-hour access tokens. Invalidated when the grant is revoked (the token lookup JOINs revoked_at).
CREATE TABLE mcp_oauth_access_token (
  token_hash TEXT        PRIMARY KEY,
  grant_id   UUID        NOT NULL REFERENCES mcp_oauth_grant,
  expires_at TIMESTAMPTZ NOT NULL
);

-- GRANTS.
--
-- dashboard_readonly (the Next.js pg pool): read-only access to grants and clients for the
-- "Connected AI apps" listing page (PR 4). Never a write. No access to the token tables.
GRANT SELECT ON mcp_oauth_grant, mcp_oauth_client TO dashboard_readonly;

-- dashboard_writer (the BFF's write DSL): the PR 4 revoke path runs
--   UPDATE mcp_oauth_grant SET revoked_at = now() WHERE id = ? AND provider = ? AND subject = ?
-- Table-level SELECT is needed here: the WHERE predicate reads provider and subject (PG16 rule),
-- and V17 grants table-level SELECT so a future widening that reads more columns stays in this
-- grant's scope without an additive migration. UPDATE is the only write operation the BFF performs
-- on this table (INSERT is the gateway's job, DELETE is never needed — the row is kept for the audit log).
-- No privileges on the token or code tables — the BFF never touches those directly.
GRANT SELECT, UPDATE ON mcp_oauth_grant TO dashboard_writer;
