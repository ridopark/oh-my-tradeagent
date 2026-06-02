-- Local-dev seed for the `dashboard` DB. Runs once on a fresh volume (docker-entrypoint-initdb.d),
-- connected to POSTGRES_DB=dashboard. Mirrors the BFF's Flyway migrations so the Next.js app can
-- connect WITHOUT the BFF running:
--   V1__dashboard_user.sql          -> dashboard_user table + tenant index
--   V2__dashboard_readonly_role.sql -> SELECT-only dashboard_readonly login role
-- Keep in sync with services/tenant-dashboard-bff/src/main/resources/db/dashboard/*.sql. Unlike V2,
-- the password here is a literal dev value (no Flyway placeholder) and MUST match
-- DASHBOARD_READONLY_PASSWORD in dashboard/.env.local — both default to 'dashboard_readonly_dev'.

-- V1: identity -> tenant mapping (keyed on the stable OAuth (provider, subject)).
CREATE TABLE dashboard_user (
  provider   TEXT        NOT NULL,   -- 'google' | 'facebook'
  subject    TEXT        NOT NULL,   -- OAuth 'sub'
  email      TEXT,                   -- informational only
  tenant_id  TEXT        NOT NULL,   -- -> tenants/<id>/tenant.yaml
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (provider, subject)
);
CREATE INDEX dashboard_user_tenant_id_idx ON dashboard_user (tenant_id);

-- V2: least-privilege role the web app connects as. SELECT only — a compromised Next.js process
-- can read the mapping table and nothing else (no DDL, no writes).
CREATE ROLE dashboard_readonly LOGIN INHERIT PASSWORD 'dashboard_readonly_dev';
GRANT CONNECT ON DATABASE dashboard TO dashboard_readonly;
GRANT USAGE  ON SCHEMA public       TO dashboard_readonly;
GRANT SELECT ON dashboard_user      TO dashboard_readonly;
