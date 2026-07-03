-- Operator-initiated tenant-user invites: an operator grants a person login access to a tenant by
-- EMAIL before that person has ever signed in. The dashboard login key is the OAuth identity
-- (provider, subject) — NOT email (see V1: email is informational/mutable) — so an email cannot be
-- provisioned directly into dashboard_user. Instead an OPEN invite (email, tenant_id) is recorded
-- here; on the person's first Google/Facebook sign-in the dashboard matches their PROVIDER-VERIFIED
-- email to an open invite, binds their (provider, subject) into dashboard_user, and consumes the
-- invite. Single-use (consumed_at) + time-boxed (expires_at). An invited row is a member of exactly
-- one tenant and can NEVER confer operator (operator stays gated by OPERATOR_EMAILS, a separate axis).
--
-- Written ONLY by the least-privilege dashboard_writer role (V5); dashboard_readonly never touches it.
CREATE TABLE dashboard_user_invite (
  id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),  -- gen_random_uuid: core in PG13+
  email             TEXT        NOT NULL,   -- invited person's email (one-time matcher, case-folded)
  tenant_id         TEXT        NOT NULL,   -- the single tenant this invite grants -> tenants/<id>
  created_by        TEXT        NOT NULL,   -- operator id that created the invite (audit)
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at        TIMESTAMPTZ NOT NULL,   -- open invites past this instant are ignored at bind time
  consumed_at       TIMESTAMPTZ,            -- set once bound; NULL => still open
  consumed_provider TEXT,                   -- OAuth provider that consumed it ('google' | 'facebook')
  consumed_subject  TEXT                    -- OAuth 'sub' that consumed it (the durable identity)
);

-- At most ONE open (unconsumed) invite per (email, tenant): a repeat operator invite refreshes the
-- existing open row (Phase 2 ON CONFLICT) instead of stacking duplicates. Consumed rows fall out of
-- the partial index, so history is retained and a re-invite after consumption is permitted. Indexed
-- on lower(email) so the uniqueness (and the login match) is case-insensitive.
CREATE UNIQUE INDEX dashboard_user_invite_open_uidx
  ON dashboard_user_invite (lower(email), tenant_id)
  WHERE consumed_at IS NULL;

-- Login-time lookup: signIn matches the verified email (case-folded) to find an open invite.
CREATE INDEX dashboard_user_invite_email_idx
  ON dashboard_user_invite (lower(email));
