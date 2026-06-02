# Tenant Dashboard (Next.js)

Read-only tenant-facing web app: social login (Google/Facebook) → view your positions, trades,
order history, and portfolio. The browser talks ONLY to this app; the server-side fetches from the
off-ingress `tenant-dashboard-bff` over in-cluster HTTP behind a shared service token.

## Architecture

- `auth.config.ts` — edge-safe base Auth.js config (providers, JWT session). No DB import (the
  middleware bundles this into the Edge runtime).
- `auth.ts` — full Node-runtime Auth.js. The `signIn` callback looks up `dashboard_user` and
  **denies login when no row exists**; `jwt`/`session` stamp `tenant_id`.
- `middleware.ts` — requires a session on every route (Edge runtime, no DB).
- `lib/db.ts` — `pg` pool for the `dashboard` DB (identity → tenant lookup). Server-only.
- `lib/bff.ts` — **server-only** BFF client; injects `X-Tenant-Id` (from the session) + the shared
  service token. Never import from a client component.
- `app/{portfolio,positions,trades,orders}/page.tsx` — server components that fetch via `lib/bff.ts`.

## Local dev

```bash
cp .env.example .env.local   # fill AUTH_*, DASHBOARD_DB_*/DASHBOARD_READONLY_PASSWORD, BFF_SHARED_TOKEN
npm install
npm run dev                  # http://localhost:3000
```

Seed a `dashboard_user` row mapping your Google identity to a tenant so login succeeds:

```sql
INSERT INTO dashboard_user (provider, subject, email, tenant_id)
VALUES ('google', '<your-google-sub>', 'you@example.com', 'dev');
```

The BFF must be running (`mvn -pl services/tenant-dashboard-bff spring-boot:run`) and reachable at
`BFF_INTERNAL_URL`.

## Auth.js version (next-auth v5-beta)

We pin `next-auth@5.0.0-beta.20` (exact, no caret — beta releases can carry breaking changes, so
the lockfile + exact pin freeze it). This whole app is built on the v5 App-Router API: the
`NextAuth({...}) → { handlers, auth, signIn, signOut }` export, the `auth.config.ts` edge/Node split
that lets `middleware.ts` run in the Edge runtime, and the `auth()` session accessor used by
`lib/bff.ts`. Stable **v4 has no first-class App-Router support** (it centres on `getServerSession` +
`pages/api`), so moving to v4 would be a backward rewrite, not a de-risking. Auth.js v5 is widely run
in production despite the `beta` label. Decision: **stay on v5, exact-pinned**; revisit when v5 GAs
(bump the pin) — tracked as a watch item, not a blocker.

## TLS

Over `http://localhost` dev works. Any non-localhost exposure requires HTTPS (OAuth redirect URIs +
the `Secure` session cookie) — see plan §G and the TLS warning in `infra/k8s/59-dashboard.yaml`.
