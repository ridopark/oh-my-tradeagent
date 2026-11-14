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
-- client_name is captured at consent time from the DCR metadata or CIMD document; nullable because
-- CIMD clients are not stored in mcp_oauth_client, so the name must be persisted here at grant
-- creation to be available in the Connected-AI-apps listing (PR 4) without a network round-trip.
-- Revoked_at is set by the member (PR 4 dashboard revoke) or by refresh-token reuse detection.
CREATE TABLE mcp_oauth_grant (
  id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  client_id   TEXT        NOT NULL,
  client_name TEXT,                              -- captured at consent from DCR/CIMD; nullable
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
-- dashboard_readonly (the Next.js pg pool): column-level SELECT on the two listing tables for the
-- "Connected AI apps" page (PR 4). Column-level grants exclude every *_hash / secret column so a
-- Next.js query compromise cannot read client_secret_hash. No access to token tables. Never a write.
--
-- mcp_oauth_grant: all non-secret columns the listing page projects or filters on (including
-- client_name captured at consent time, and provider/subject for the per-identity WHERE).
GRANT SELECT (id, client_id, client_name, provider, subject, tenants, created_at, last_used_at, revoked_at)
  ON mcp_oauth_grant
  TO dashboard_readonly;

-- mcp_oauth_client: metadata for extended client info; excludes client_secret_hash and expires_at.
GRANT SELECT (client_id, metadata, created_at, last_used_at)
  ON mcp_oauth_client
  TO dashboard_readonly;

-- dashboard_writer (the BFF's write DSL): the PR 4 revoke path runs
--   UPDATE mcp_oauth_grant SET revoked_at = now() WHERE id = ? AND provider = ? AND subject = ?
--     AND revoked_at IS NULL
-- Column-level SELECT on the WHERE columns (PG16: each predicate column needs SELECT), and
-- column-level UPDATE on revoked_at only. INSERT is the gateway's job; DELETE is never needed
-- (the row is kept for the audit log). No privileges on the token or code tables.
GRANT SELECT (id, provider, subject, revoked_at) ON mcp_oauth_grant TO dashboard_writer;
GRANT UPDATE (revoked_at) ON mcp_oauth_grant TO dashboard_writer;
