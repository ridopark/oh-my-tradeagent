-- Allow ONE social identity to be provisioned for MULTIPLE tenants, so an operator can switch the
-- active tenant in the dashboard. The Next.js app picks the active tenant from the identity's allowed
-- set and injects it as the single X-Tenant-Id the BFF trusts — the BFF's trust model is unchanged.
--
-- V1 keyed dashboard_user on (provider, subject) = exactly one tenant per identity. Widen the PK to
-- (provider, subject, tenant_id) so each (identity, tenant) grant is its own row. Onboarding a user
-- to an additional tenant stays a single INSERT. The dashboard_user_tenant_id_idx index and the
-- dashboard_readonly SELECT grant (V2) are unaffected; the lookup the web app runs
-- (WHERE provider=? AND subject=?) now returns one row per provisioned tenant.
ALTER TABLE dashboard_user DROP CONSTRAINT dashboard_user_pkey;
ALTER TABLE dashboard_user ADD CONSTRAINT dashboard_user_pkey PRIMARY KEY (provider, subject, tenant_id);
