-- Tenant-dashboard identity binding. The ONLY schema this BFF owns (Flyway runs against the
-- dedicated `dashboard` DB; the orchestrator/exec datasources are read-only and never migrated
-- here). Maps a verified social identity (provider + OAuth subject) to a tenant_id.
--
-- The Next.js server does this lookup inside the Auth.js signIn callback AFTER Google/Facebook
-- verifies the identity; no matching row => signIn denied => no session minted => the BFF is never
-- reached. The BFF itself never reads this table — it trusts the X-Tenant-Id the Next.js server
-- injects behind the shared service token.
--
-- Keyed on (provider, subject), NOT email: the OAuth `sub` is the stable, provider-verified
-- identifier; emails are mutable/reassignable and only informational here.
--
-- Onboarding a tenant user is a single INSERT (no repo edit + tenants ConfigMap sync + redeploy).
CREATE TABLE dashboard_user (
  provider   TEXT        NOT NULL,   -- 'google' | 'facebook'
  subject    TEXT        NOT NULL,   -- OAuth 'sub' (stable verified identity)
  email      TEXT,                   -- informational only
  tenant_id  TEXT        NOT NULL,   -- -> tenants/<id>/tenant.yaml
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (provider, subject)
);

-- Fast reverse lookup of all dashboard logins provisioned for a tenant (operator/audit use).
CREATE INDEX dashboard_user_tenant_id_idx ON dashboard_user (tenant_id);
