# mobile/ — native app (P0 spike)

> **STATUS: P0 SPIKE — throwaway.** Epic #449, issue #450. This is the *client half* of the
> "native PKCE → tenant" spike, paired with the api-gateway `POST /m/auth/exchange` endpoint and
> the runbook in `docs/plans/P0-spike-mobile-auth.md`. It is intentionally minimal: one screen,
> Google PKCE, post the IdP `id_token`, show the resolved tenant or a denial. It is **not** the
> production app (that's P2, #452) and carries none of the P1 hardening.

## Run
1. Provide config (see `config.ts`) — Google **native** client IDs + the api-gateway base URL.
   These come from operator prerequisite **A** in the runbook; the web client IDs will NOT work.
2. `npm install`
3. `npx expo start` → open in an iOS simulator / Android emulator (Expo Go or a dev client).
4. Tap **Sign in with Google**. A provisioned identity (a `dashboard_user` row) returns a
   first-party token + `tenantIds`; an unprovisioned Google account returns **401 denied**
   (parity with the web dashboard's `DENIED_LOGIN`).

## What this proves (de-risks the epic)
The riskiest unknown: that a native client can complete OAuth Authorization Code + **PKCE** against
the *same* Google IdP the web app uses, and that our backend can validate the result and map it to a
tenant by reusing `dashboard_user` — **without** the web app's "server injects a verified
X-Tenant-Id behind network isolation" trick. Facebook is the symmetric second provider (wired the
same way in P1).

## Files
- `App.tsx` — the single sign-in screen + PKCE request (expo-auth-session).
- `lib/auth.ts` — exchange the IdP `id_token` at `/m/auth/exchange`; store the first-party token in
  the device keystore (expo-secure-store).
- `config.ts` — client IDs + backend URL (placeholders; fill from prereq A).
- `app.json` — Expo config + the custom URL `scheme` used as the PKCE redirect.
