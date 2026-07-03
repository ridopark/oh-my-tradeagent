-- The operator admin tenants page (dashboard) shows, per tenant, the bound member emails
-- (dashboard_user — already SELECT-granted to dashboard_readonly in V1/login flow) AND the pending
-- invite emails (dashboard_user_invite). The invite table was writer-only until now; grant the
-- SELECT-only dashboard_readonly role read access so the operator listing can surface open invites.
--
-- Read-only and additive: no write privilege is granted, no existing grant is widened, and the
-- least-privilege dashboard_writer role (V5) is untouched. dashboard_readonly stays strictly
-- SELECT-only. This is the ONLY migration that grants dashboard_readonly anything on the invite table.
GRANT SELECT ON dashboard_user_invite TO dashboard_readonly;
