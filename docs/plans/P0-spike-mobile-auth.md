# P0 spike — native PKCE → tenant (runbook)

Throwaway spike for epic #449 / issue #450. **Goal:** prove the single riskiest unknown end-to-end —
a native client completes Google/Facebook PKCE, the backend validates the resulting IdP token, maps
it to a tenant via the *existing* provisioning, and returns a first-party token. Unprovisioned
identity is denied exactly like the web (`DENIED_LOGIN`).

This is **not** production code. It deliberately skips refresh-token rotation, rate limiting,
multi-datasource hardening, and the real `/m/*` surface — those are P1 (#451).

## What the code-map established (so we don't reinvent)
- **Identity → tenant rule (reuse verbatim):** `SELECT tenant_id FROM dashboard_user WHERE
  provider=? AND subject=? ORDER BY tenant_id` → `string[]`; **empty ⇒ deny**. `provider ∈
  {google, facebook}`, `subject` = OAuth `sub` (`account.providerAccountId`). Source of truth:
  `dashboard/lib/db.ts` `findTenantsForIdentity` + `dashboard/auth.ts`.
- **`dashboard_user` lives in the `dashboard` DB**, not `orchestrator`. api-gateway today has ONE
  datasource → `orchestrator`. The spike needs a read path to the `dashboard` DB (see prereq B).
- **No Java OAuth/JWT anywhere.** api-gateway has no JOSE/Google/Spring-Security lib. The spike adds
  the first one (nimbus-jose-jwt for JWKS verification + first-party JWT mint).
- **Patterns to model on:** `/broker-credentials` `ServiceTokenFilter` (dark-gate via
  `@ConditionalOnProperty`), `AuditController` (raw-jOOQ `DSL.table("...")`), BFF
  `application.yml` (hand-wired second datasource).

## Endpoint contract — `POST /m/auth/exchange` (spike)
Dark-gated behind `mobile.auth.spike.enabled=true` (default false), same posture as
`/broker-credentials`.

Request:
```json
{ "provider": "google", "id_token": "<IdP-issued OIDC ID token (JWT)>" }
```
Backend steps:
1. Validate `id_token` against the provider's JWKS (issuer + audience = our native client_id +
   signature + exp). → on failure: **401**.
2. Extract `sub`.
3. `findTenantsForIdentity(provider, sub)` against the `dashboard` DB.
   - empty ⇒ **401** (`DENIED_LOGIN` parity).
4. Mint a short-lived first-party access JWT (`sub`, `tenantIds`, active `tenant = tenantIds[0]`).

Response (200):
```json
{ "access": "<first-party JWT>", "tenantIds": ["dev"], "tenant": "dev" }
```
> Spike scope: no refresh token, no rotation. P1 (#451) adds `/m/auth/refresh` + `/m/auth/logout`,
> rotation, reuse-detection, rate limiting, and the read passthrough.

## Operator prerequisites to run the spike LIVE (only you can provision these)
- **A — OAuth *native* client IDs.** Register an **iOS** (and Android) OAuth client for the app in
  **Google Cloud Console** and a **Facebook** app (Facebook Login) — each with the app's custom URL
  scheme as the redirect. The web Google/Facebook clients won't work for a native PKCE redirect.
  Provide: Google iOS client_id (+ Android), Facebook app_id, and the chosen URL scheme.
- **B — api-gateway → `dashboard` DB read.** A reachable `dashboard` DB and a role with `SELECT ON
  dashboard_user` (the existing `dashboard_readonly` role fits). For the spike this can be local
  (docker `dashboard` DB seeded with one `dashboard_user` row). Provide host/port/db/user/password
  env for a second datasource.
- **C — A simulator/device.** Xcode iOS simulator or an Android emulator with Expo Go / a dev
  client, to actually drive the PKCE screen.

## How to run (once A–C exist)
1. Seed a `dashboard_user` row for your test Google identity:
   `INSERT INTO dashboard_user(provider,subject,email,tenant_id) VALUES('google','<your-sub>','you@x','dev');`
2. Start the api-gateway spike with `mobile.auth.spike.enabled=true` + the dashboard-DB datasource env.
3. `cd mobile && npm install && npx expo start`; open on the simulator; tap **Sign in with Google**.
4. Expect: provisioned identity → token + `tenantIds:["dev"]`; a Google account with **no**
   `dashboard_user` row → 401 denied. Flip the row away and confirm the deny path.

## Exit criteria (closes #450)
- [ ] Native PKCE completes; app receives an IdP `id_token`.
- [ ] Backend validates it via JWKS and extracts `sub`.
- [ ] Provisioned identity → first-party token with correct `tenantIds`.
- [ ] Unprovisioned identity → 401, parity with web `DENIED_LOGIN`.
- [ ] Findings written up → feed the P1 (#451) endpoint design.
