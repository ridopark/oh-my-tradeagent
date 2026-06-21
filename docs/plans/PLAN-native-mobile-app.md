# EPIC — Native mobile app (iOS/Android) for the tenant dashboard

**Goal:** ship a real native app (iOS + Android) that gives a tenant the same read surface as the
web dashboard — portfolio, positions, orders, trades, status — plus push alerts and (later) the
sensitive write surfaces (settings, broker credentials, tenant switch).

**The hard part is NOT the UI.** The current dashboard's security rests on one trick: the browser
**never** talks to the BFF; the Next.js *server* holds `BFF_SHARED_TOKEN`, and after Auth.js
verifies the identity it injects a **verified** `X-Tenant-Id` to a **network-isolated** BFF that
trusts the header *precisely because only that server can reach it* (`dashboard/lib/bff.ts`). A
native client is a public, untrusted client on the open internet — it cannot be handed that token,
and it cannot be trusted to assert its own tenant. So the epic is dominated by **building an
auth-enforcing, internet-facing API edge** that re-derives tenant *server-side* from a verified
token — everything else (Expo screens, push) is comparatively routine.

## Locked decisions (operator-approved)
1. **Distribution:** TestFlight (iOS) + Play **internal testing** (Android) — private, fits the
   small known tenant set, skips public App Review friction. Promote to public stores later only if
   needed.
2. **Native auth:** reuse the existing **Google + Facebook** IdPs via OAuth 2.0 Authorization Code +
   **PKCE**, exchanged at the edge for a first-party token — so the `dashboard_user` provisioning DB
   is reused verbatim. No new IdP.
3. **API edge placement:** extend the existing **`api-gateway`** with `/m/*` routes (auth + read).
   No new `mobile-bff` service. The gateway already fronts the cluster and holds
   `API_GATEWAY_SHARED_TOKEN`; it becomes the only internet-facing, end-user-authenticated surface.
4. **v1 surface:** **read-only** — portfolio/positions/orders/trades/status + push. Sensitive write
   surfaces (broker credentials, settings) are **deferred to v2** (P3 below).

**v1 = P0 → P2 + P4 + P5 + P6. P3 (writes) is explicitly post-v1.**

## Scope / non-goals
- **In scope (v1):** native iOS + Android (one Expo/React Native codebase); a token-auth API edge on
  `api-gateway`; read screens; push notifications; secure on-device storage; TestFlight/internal
  distribution pipeline.
- **Out of scope (v1):** broker-credential / settings **writes** (v2); offline mode beyond
  last-fetched cache; tablet/desktop-native; public app stores; replacing the web dashboard (it
  stays — the app is additive); any change to trading/exec logic.
- **Explicit non-goal:** do NOT expose the existing network-isolated BFF directly to the internet.
  The BFF's "trust `X-Tenant-Id`" contract is only safe behind network isolation; the `api-gateway`
  `/m/*` edge sits in front and is the only thing that talks to the BFF.

## Verified current-state (code-confirmed)
- **Frontend:** Next.js 14 App-Router, Auth.js v5-beta (`next-auth@5.0.0-beta.20`, exact-pinned;
  GA tracked in #345). Routes: `portfolio`, `positions`, `orders`, `trades`, `status`, `settings`,
  `config`, `signin`.
- **Login:** Google + Facebook OAuth; `session.strategy: "jwt"`. A local-only passwordless
  `dev-login` is double-gated (`AUTH_DEV_LOGIN=true` AND `NODE_ENV=development`) and must never be
  reachable from the app.
- **Identity → tenant binding (the reusable asset):** `auth.ts` `signIn` callback maps the verified
  OAuth subject (`account.provider` + `account.providerAccountId`, i.e. OIDC `sub`) to a provisioned
  `dashboard_user` row. Unprovisioned identity → `DENIED_LOGIN`, no session, BFF never reached. The
  `jwt` callback stamps the active `tenantId` (+ `tenantIds[]` for multi-tenant users) onto the
  token; active tenant is switchable via session update. `session` callback surfaces
  `session.tenantId` / `session.tenantIds`. **This server-side mapping is exactly what the mobile
  API edge must reuse — do not reinvent it.**
- **Data path:** `dashboard/lib/bff.ts` is `server-only`; calls `BFF_INTERNAL_URL` (off-ingress,
  default `:8083`) with `Authorization: Bearer BFF_SHARED_TOKEN` + injected `X-Tenant-Id`; 12s cap;
  `cache: "no-store"` (reads are always live).
- **Public exposure:** Cloudflare Tunnel + Access at `https://tradeagent.ridopark.com`
  (`AUTH_TRUST_HOST` serves both LAN nip.io + public host). **Access is browser-oriented** — its
  cookie/redirect SSO flow does not fit a native client cleanly; the app needs its own token flow.
- **Sensitive surfaces already in the web app:** `settings`/`config` host the broker-credential
  entry form (UI-P2) that writes an envelope-encrypted Postgres column via `DbBrokerCredentialSource`,
  behind a credential-write hardening gate. Any native equivalent inherits that threat model.
- **Backend:** Java microservices (`tenant-dashboard-bff`, `api-gateway` w/
  `API_GATEWAY_SHARED_TOKEN`). Alert fan-out already exists (per-tenant Discord webhooks
  `ALERT_DISCORD_WEBHOOK_URLS`, watchlist digest) — a natural source to mirror into push.

---

## P0 — Auth spike (de-risk before building anything)
Decisions are locked (above); P0 is now just the throwaway spike that proves the single riskiest
unknown: a native client completing the PKCE flow and getting back a token the backend accepts and
resolves to a tenant.
- **SPIKE:** Expo dev client completes Google + Facebook PKCE → an `api-gateway` `/m/auth/exchange`
  stub validates the IdP token, looks up `dashboard_user`, returns `{access, refresh, tenantIds}`.
  → verify: app receives a token; an unprovisioned Google/Facebook account is rejected exactly like
  web `DENIED_LOGIN`. Token validation + the `dashboard_user` lookup are the only things that must
  be real in the spike; data reads can be stubbed.

## P1 — Backend: `api-gateway` `/m/*` auth edge + authenticated read API (the core of the epic)
This is where most of the work and all of the risk live. All routes live under `/m/*` on the
existing `api-gateway`.
- **Mobile token service.** `POST /m/auth/exchange` (IdP code/token → first-party access+refresh),
  `POST /m/auth/refresh`, `POST /m/auth/logout` (refresh-token revocation). Re-implement the
  `auth.ts` provider-subject → `dashboard_user` → tenant mapping server-side in the gateway (single
  source it so it can't drift from Auth.js); **never trust a client-asserted tenant**. Issue a
  signed access JWT carrying `sub` + the *resolved* `tenantIds` and an active-tenant claim;
  short TTL (e.g. 10 min) with refresh rotation + reuse-detection.
- **Authenticated read API.** Expose the existing BFF read surfaces (portfolio/positions/orders/
  trades/status) through the edge with **per-request JWT validation** and **tenant derived from the
  verified token, not the request**. The edge is the only thing that holds `BFF_SHARED_TOKEN` and
  sets `X-Tenant-Id` — i.e. it plays the exact role the Next server plays today. The BFF stays
  network-isolated and unchanged.
- **Tenant switch.** `tenantIds` with >1 entry → an active-tenant select; switching re-issues a
  token scoped to the new tenant (mirrors web `unstable_update`).
- **Fail-closed posture:** invalid/expired/unprovisioned → 401, no data; same denial semantics as
  web. Rate-limit the auth + read endpoints (now internet-facing, unlike the BFF).
- → verify: integration tests for token issue/refresh/revoke; tenant-isolation test (tenant A's
  token can never read tenant B); unprovisioned-identity denial test; the BFF remains unreachable
  except via the edge.

## P2 — Mobile app shell + read screens
- Expo (React Native) project; navigation; data fetching (e.g. React Query) against the P1 API;
  **secure token storage** (`expo-secure-store` → iOS Keychain / Android Keystore); **biometric
  app-lock** (real-money app — Face ID/biometric on launch + on resume).
- Rebuild the read screens: portfolio, positions, status (with the live marks + total account value
  + unrealized P&L shipped in #436/#437), positions, orders, trades. Keep the Yahoo-Finance contract
  links. Read-only first.
- → verify: a provisioned tenant logs in, sees live data matching the web dashboard for the same
  account; token refresh is transparent; biometric lock works; logout revokes server-side.

## P3 — Sensitive write surfaces (DEFERRED to v2 — not in v1)
- Settings, **broker-credential entry**, tenant switch. Broker credentials inherit the UI-P2 threat
  model (envelope-encrypted column, credential-write hardening gate) — plus mobile-specific care:
  no secrets in logs/crash reports, no clipboard leakage, biometric re-auth before write, TLS to the
  edge only.
- → verify: credential write round-trips to the encrypted column via the edge; write path re-prompts
  biometric; nothing sensitive in device logs or analytics.

## P4 — Push notifications
- Device-token registration endpoint (per tenant/user) on the edge; **fan-out from the existing
  alert pipeline** (the same events feeding per-tenant Discord — order rejections, watchlist digest,
  kill-switch trips) to APNs/FCM via **Expo push**. Start by mirroring existing alert events; no new
  alert semantics.
- → verify: a test rejection/kill-switch event delivers a push to the right tenant's device only;
  unregister on logout.

## P5 — Distribution + release ops
- Apple Developer Program ($99/yr) + Google Play ($25 one-time); code signing; **EAS Build** +
  submit pipeline; OTA updates (Expo) for JS-only changes; versioning. Per P0: start on
  **TestFlight + Play internal track**; promote to public review only if required (real-money
  finance apps draw extra App Review scrutiny — privacy labels, account-deletion, no misleading
  financial claims).
- CI: build/sign on tag; keep store credentials out of the repo (secrets, like
  `KUBECONFIG_DEPLOY`/`ALERT_*` today).
- → verify: a signed build installs on a real device via TestFlight/internal track; OTA pushes a
  JS-only fix without a store round-trip.

## P6 — Parity, hardening review, cutover decision
- Adversarial security review of the new internet-facing edge (the new attack surface) — authz,
  tenant isolation, token replay/refresh-reuse, rate limits, secret handling.
- Decide web↔app relationship: app is **additive** (web stays the source of truth); no
  decommission. Document the second auth path so it's maintained alongside Auth.js.

---

## Cross-cutting / risks
- **New attack surface.** Today the only authenticated-data path is the network-isolated BFF behind
  a single server + Cloudflare Access. P1 adds a *public, internet-facing, end-user-authenticated*
  API. This is the dominant risk and the reason P1 + P6 carry the security weight.
- **Two auth systems to maintain.** Auth.js (web cookies/JWT) + the mobile token service (PKCE +
  refresh) both map IdP subject → `dashboard_user` → tenant. Keep that mapping single-sourced
  server-side so they can't drift.
- **Real-money posture is non-negotiable:** fail-closed denials, biometric lock, no secrets on
  device/logs, tenant isolation proven by test — same bar as the exec/trading code.
- **Effort:** weeks-to-months, front-loaded into P1 (auth edge) and P5/P6 (store + security). P2/P4
  (Expo UI + push) are the routine part. A PWA would reach ~90% of "an app on my phone" for a
  fraction of this — native is justified only by app-store distribution + native integrations
  (biometric, push) + a first-class mobile UX.

## Decisions — RESOLVED (see "Locked decisions" up top)
1. ~~Distribution~~ → **TestFlight + Play internal testing.**
2. ~~Native IdP~~ → **reuse Google + Facebook PKCE** (reuses the `dashboard_user` provisioning DB).
3. ~~Edge placement~~ → **extend `api-gateway` with `/m/*` routes.**
4. ~~v1 surface~~ → **read-only first**; broker-credential/settings writes deferred to v2 (P3).

No open blockers remain — P0 (auth spike) can start.
