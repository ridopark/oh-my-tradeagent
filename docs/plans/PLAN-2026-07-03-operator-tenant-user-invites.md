# PLAN — 2026-07-03 Operator tenant-user invites (email → login on first sign-in)

**Goal.** Add an **email field** to the operator onboard form so the operator can grant a person
login access to a newly-created tenant. Because the dashboard login key is the OAuth identity
`(provider, subject)` — NOT email (`dashboard/lib/db.ts:41`; `email` is "informational, mutable, not
the join key") — this is an **invite + bind-on-first-login** flow, decided with the operator:

```
operator types email on the onboard form  →  a pending INVITE row (email, tenant_id) is created
person signs in with Google/Facebook for the first time
   → signIn sees they're unprovisioned, finds a pending invite matching their PROVIDER-VERIFIED email
   → binds their (provider, subject) into dashboard_user for that tenant, consumes the invite
   → login is allowed, scoped to that ONE tenant (a regular member, NOT an operator)
```

**Decisions (fixed 2026-07-03):** invite + bind-on-first-login (keeps the immutable `sub` as the
durable key; email is only the one-time matcher); **tenant-scoped member** (operator/admin stays gated
by `OPERATOR_EMAILS`, entirely separate — an invited row can NEVER confer operator).

**Reuses** the write-path machinery designed in `docs/plans/PLAN-2026-06-28-self-registration.md`
(`dashboard_writer` least-privilege role, a BFF write surface, signIn provisioning) — this plan is a
**controlled, operator-initiated subset** (no open self-signup, no `role` column needed).

Source of anchors: code survey 2026-07-03 (auth model + provisioning), all `file:line` re-read at
authoring time.

---

## Key facts (read first)

- **No write path to `dashboard_user` exists today.** The dashboard connects as the SELECT-only
  `dashboard_readonly` role (`dashboard/lib/db.ts:15-24`); the BFF touches the `dashboard` DB only via
  Flyway at migration time. Provisioning is manual SQL (`dashboard/README.md:158`). Any provisioning
  feature MUST add a least-privilege writer + a write surface — the single biggest cost here.
- **The login gate** is `dashboard/auth.ts` signIn (`:20-48`) → `findTenantsForIdentity(provider,
  subject)` (`dashboard/lib/db.ts:41-44`); empty result → `return false` (denied, logs `DENIED_LOGIN`).
- **Tenant-scoping** is by `session.tenantId` / `tenantIds` (`auth.ts:80-104`); a plain `dashboard_user`
  row (`provider, subject, email, tenant_id`) grants exactly one tenant's dashboard. **Operator** is a
  SEPARATE axis (`OPERATOR_EMAILS` env → `isOperatorEmail(token.email)`, `dashboard/lib/operator.ts`),
  so an invited row is inherently member-only. **No `role` column is needed for this feature.**
- **Provider-verified email only.** Google + Facebook return a verified email on the OAuth profile; the
  bind MUST match against that verified email (from the trusted server-side OAuth profile in signIn),
  never a user-supplied string.

---

## P0 — Operator preconditions (no code; live-cluster)

- **`dashboard_writer` Secret + role** are a live `kubectl apply` + Flyway run on the `dashboard` DB
  (shared manifests aren't applied by `deploy.yml`). The new role's password is a new Secret key,
  mirrored into the BFF's writer datasource env (like `DASHBOARD_READONLY_PASSWORD`).
- **Feature stays dark** until the operator sets the enable flags (below), same discipline as the rest.

---

## Cross-cutting constraints (per phase)

- **Least privilege:** `dashboard_writer` gets INSERT on `dashboard_user` and INSERT/SELECT/UPDATE on the
  invites table ONLY — no UPDATE/DELETE on `dashboard_user`, no other tables. `dashboard_readonly`
  stays SELECT-only.
- **Security invariants (risk-review these):** invite-create is operator-allowlisted; bind can grant
  ONLY the invite's `tenant_id` and NEVER operator; email match is against the OAuth-verified email;
  invites are single-use (consumed) and time-boxed; the bind endpoint is service-token-gated and takes
  the identity from the dashboard's trusted server-side OAuth (never from a client).
- **Flyway** migrations are additive + backward-compatible; the drift/CI checks apply. Spotless per Java
  module. Dashboard has no test harness → `tsc --noEmit` + `next build`.

---

## Phase 1 — Writer role + invites table (`tenant-dashboard-bff` Flyway + a writer datasource)

**Goal:** the durable storage + least-privilege write access the feature needs.

**Changes** (anchors):
- `services/tenant-dashboard-bff/.../db/dashboard/V4__dashboard_user_invite.sql` (NEW) — create
  `dashboard_user_invite(id uuid pk default gen_random_uuid(), email text not null, tenant_id text not
  null, created_by text not null, created_at timestamptz not null default now(), expires_at timestamptz
  not null, consumed_at timestamptz, consumed_subject text, consumed_provider text)`; a UNIQUE partial
  index on `(lower(email), tenant_id) where consumed_at is null` (one open invite per (email,tenant));
  index on `lower(email)` for the login lookup.
- `services/tenant-dashboard-bff/.../db/dashboard/V5__dashboard_writer_role.sql` (NEW) — create the
  `dashboard_writer` role (password via `${dashboard_writer_password}` placeholder, same pattern as
  `V2` readonly): GRANT INSERT ON `dashboard_user`; GRANT SELECT, INSERT, UPDATE ON
  `dashboard_user_invite`. Nothing else.
- BFF: a new writer `DSLContext`/datasource bean (mirrors the readonly wiring but as `dashboard_writer`),
  env `DASHBOARD_WRITER_*` / `DASHBOARD_WRITER_PASSWORD`. Gated so it only comes up when the feature flag
  is on (dark by default).

**Tests:** Flyway migration applies on the BFF test DB; `dashboard_writer` can INSERT dashboard_user +
CRUD invites but NOT UPDATE/DELETE dashboard_user; `dashboard_readonly` still cannot write.

**Verify:** `mvn -pl services/tenant-dashboard-bff -am spotless:apply` + module tests. Additive Flyway,
drift-safe.

---

## Phase 2 — BFF write surface: create-invite + bind-on-login (`tenant-dashboard-bff`)

**Goal:** two service endpoints — one for the operator to create an invite, one for the dashboard's
signIn to bind an identity.

**Changes** (anchors):
- **Create invite** — `POST /api/admin/tenant-invites` (operator-scoped: `ctx.requireAllowlistedOperator`,
  `X-Operator-Id`, bearer via the BFF `ServiceTokenFilter`; dark-flagged `operator.tenant-invite.enabled`
  added to the filter's coverage). Body `{email, tenant_id}` → validate the tenant exists (a
  `strategy_config`/registry check), INSERT an invite (`created_by`=operator, `expires_at`=now+N days,
  ON CONFLICT on the open-invite unique index → refresh/return existing). Returns the invite id/expiry
  (no secret). Mirrors the existing admin controller pattern (`AdminTenantsController`).
- **Bind on login** — `POST /internal/provisioning/bind` (service-token gated; NOT operator/tenant
  scoped). Body `{provider, subject, email}` (all from the dashboard's trusted server-side OAuth). Logic:
  if a NON-consumed, non-expired invite matches `lower(email)`, then in ONE transaction INSERT
  `dashboard_user(provider, subject, email, tenant_id)` (ON CONFLICT do nothing — idempotent) and mark
  the invite `consumed_at`/`consumed_subject`/`consumed_provider`. Return the granted `tenant_id`(s) (or
  empty). NEVER returns or grants operator; only the invite's tenant.

**Tests (TDD):** create-invite operator-allowlisted (403 non-allowlist, 400 missing, dark 404); bind
grants exactly the invite's tenant, consumes it (second bind for same invite → no-op), no match → empty,
expired/consumed → empty, wrong email → empty; idempotent on `(provider,subject,tenant)`; service-token
gated.

**Verify:** `mvn -pl services/tenant-dashboard-bff -am spotless:apply` + tests. Behavioral: an operator
creates an invite; a bind with the matching verified email grants the tenant once and only once.

---

## Phase 3 — Dashboard signIn binds via the invite (`dashboard`)

**Goal:** an unprovisioned identity that has a matching invite is ADMITTED (bound), instead of denied.

**Changes** (anchors):
- `dashboard/auth.ts:20-48` — in signIn, when `findTenantsForIdentity` returns empty, call the BFF
  `POST /internal/provisioning/bind` with `{provider: account.provider, subject:
  account.providerAccountId, email: <verified profile email>}` (server-side, bearer). If it returns a
  granted tenant → `return true` (allow); else keep today's `DENIED_LOGIN` + `return false`. Use the
  verified email from the OAuth profile only; if the provider didn't return a verified email, do NOT
  bind.
- `dashboard/lib/db.ts` / a new `lib/provisioning.ts` — the server-side bind client (base url + service
  token from env, like the existing BFF clients). Dark-flagged (`AUTH_INVITE_BIND_ENABLED`) so default
  behavior is unchanged (pure deny) until enabled.
- jwt/session (`auth.ts:80-104`) already stamp `tenantIds` from `findTenantsForIdentity` on first
  sign-in — after a successful bind, the row exists, so the existing stamping picks it up (bind before
  the tenant lookup, or re-query post-bind).

**Tests:** dashboard `tsc --noEmit` + `next build`. Behavioral (documented): unprovisioned + matching
invite → admitted, scoped to the invited tenant; unprovisioned + no invite → still denied; the bind
never yields operator.

---

## Phase 4 — Onboard form: email invite field (`dashboard`)

**Goal:** the operator enters an email on the onboard form to create the invite.

**Changes** (anchors):
- `dashboard/components/OnboardForm.tsx` + `dashboard/app/admin/onboard/page.tsx` — add an optional
  **"Invite user (email)"** field/step: an email input + button, `X-Operator-Id`-scoped server action
  → `POST /api/admin/tenant-invites` for the current tenant. Dark-flagged `OPERATOR_TENANT_INVITE_ENABLED`
  (mirrors the other onboard-step flags). Shows the created invite's expiry + a "they can now sign in
  with this email" confirmation. Optional and independent of the create/verify/enable steps.
- `dashboard/lib/adminOnboarding.ts` — add an `inviteUser(tenant, email)` server action mirroring the
  existing operator POST helpers (bearer + `X-Operator-Id`).

**Tests:** `tsc --noEmit` + `next build`. The field is optional; disabled/hidden when the flag is unset.

**Verify:** end-to-end (documented): operator onboards a tenant, enters an email → invite created; that
person signs in → lands in the tenant as a member.

---

## Ship order & gating

```
Phase 1 (writer role + invites table)   ── DB foundation; dark (writer datasource off by default)
   └─> Phase 2 (BFF create-invite + bind endpoints)   ── needs P1
          └─> Phase 3 (dashboard signIn binds)          ── needs P2 deployed + reachable
                 └─> Phase 4 (onboard form email field)  ── needs P2 (create-invite) live
```

Each phase = one single-concern PR, TDD-first, spotless per module, operator merge gate. Everything ships
DARK (flags default off; the writer datasource + bind path don't activate until the operator flips them
and runs the P0 `dashboard_writer` role migration). Repo default login behavior is unchanged (deny
unprovisioned) until `AUTH_INVITE_BIND_ENABLED`.

## Out of scope
- Open self-registration / demo signup (that's the separate self-registration epic; this is
  operator-initiated invites only).
- A `role` column / operator-role-in-DB (operator stays `OPERATOR_EMAILS`; invited users are members).
- Invite email delivery (no emails are sent; the operator tells the person to sign in). Revoking an
  unconsumed invite (a delete surface) — a fast-follow if needed.
