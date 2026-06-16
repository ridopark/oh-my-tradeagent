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

## Local development

This covers running the **dashboard** locally. To run the **full trading pipeline** locally against a
sandboxed Alpaca account + test Discord channels (so the dashboard shows real, non-seeded data), see
[`docs/ops/local-dev.md`](../docs/ops/local-dev.md).

### Fastest: one command (Dev login, no OAuth)

From the repo root:

```bash
make dashboard-dev
```

This brings up the compose infra (postgres + temporal), runs the BFF (`mvn spring-boot:run`, which
Flyway-creates `dashboard_user` + the `dashboard_readonly` role) and the Next.js dev server, all
wired together with a passwordless **Dev login** — open <http://localhost:3000> and click
**"Dev login (local only)"**. Ctrl-C stops the BFF + web (the compose infra is left up;
`docker compose -f infra/docker-compose.yml down` to stop it). See
`scripts/dev/dashboard-dev.sh` for the exact wiring.

> **Sample data is seeded automatically.** `make dashboard-dev` runs `scripts/dev/seed.sql` (via
> `make dashboard-seed`), which creates the `audit_log` + `order_intent_journal` read tables — absent
> here because orchestrator-svc / exec-svc aren't running — and inserts sample fills/orders for
> tenant `dev`, so **Trades, Orders, and Portfolio realized-PnL show data**. **Positions** stay empty
> (they come from live Temporal `PositionWorkflow`s) and the Portfolio page waits ~8s for the
> account-snapshot workflow to time out (no orchestrator worker) before rendering. Re-seed anytime
> with `make dashboard-seed` (idempotent).

### Editing strategy config locally (the /config page)

`make dashboard-dev` is **read-only** — the `/config` page renders but **Save** has no write backend.
To exercise the full edit-and-save flow locally (the local equivalent of the homelab config-edit
flow), use:

```bash
make config-edit-dev
```

This is a **superset** of `dashboard-dev`: on top of the compose infra it also brings up **redis**
(the orchestrator requires it), the **orchestrator** (`mvn spring-boot:run`, which Flyway-creates +
seeds the `strategy_config` table from the `tenants/` tree and hosts the
`StrategyConfigUpdateWorkflow` worker on the `orchestrator-core` queue), the **api-gateway** :8082
(the write forward, with `STRATEGY_CONFIG_WRITE_ENABLED=true`), the **BFF** :8083 (reads
`strategy_config`), and the **Next.js** dev server :3000. Open <http://localhost:3000>, click
**"Dev login (local only)"**, go to **/config**, edit a field, and **Save**. Ctrl-C stops the three
JVMs + the web server (compose infra is left up). See `scripts/dev/config-edit-dev.sh` for the exact
wiring.

> The write path enforces the same guardrail as production: the orchestrator's
> `StrategyConfigWriter` **hard-blocks** any risk-increasing / live-routing change (e.g. flipping
> `broker_target`) → **403**, regardless of the write flag. A safe (tighten-only or no-op) change →
> **200** with a bumped `new_version`. The api-gateway and orchestrator MUST share
> `TEMPORAL_NAMESPACE=default` and the `orchestrator-core` task queue (the script sets both) — else
> the workflow start has no live worker and times out → 503.

### Dev login — how it's gated

The Dev login provider is **double-gated** so it can never reach production (`auth.config.ts`):
it is added only when `AUTH_DEV_LOGIN === "true"` **and** `NODE_ENV === "development"` — a positive
signal that fails closed (`next dev` sets `development`; `next build`/`next start` set `production`;
an unset value is also rejected). It maps straight to `AUTH_DEV_TENANT` (default `dev`) with no
`dashboard_user` lookup. Never set `AUTH_DEV_LOGIN` in a deployed environment.

### Manual run (à la carte)

```bash
docker compose -f infra/docker-compose.yml up -d postgres temporal
# BFF (separate terminal). DASHBOARD_READONLY_PASSWORD has no default — set it:
DASHBOARD_READONLY_PASSWORD=dashboard_readonly_dev BFF_SHARED_TOKEN=dev-shared-token \
  mvn -pl services/tenant-dashboard-bff -am spring-boot:run
# Web (separate terminal):
cd dashboard && cp .env.example .env.local && npm install && npm run dev   # http://localhost:3000
```

### Frontend-only (no Java BFF)

To iterate on the UI without running the BFF/Temporal, stand up just the `dashboard` identity DB and
run the web server. `make dashboard-dev` is still the way to exercise the real data path.

```bash
docker compose -f dashboard/docker-compose.yml up -d   # standalone `dashboard` DB (port 5432)
cd dashboard && npm run dev
```

It binds the same `:5432` as the full `infra/docker-compose.yml`, so run **one or the other**, not
both. The data pages still call the BFF, so they'll error until a BFF is reachable — this mode is for
the sign-in/layout/styling, not the data path.

### Real Google/Facebook login (instead of Dev login)

Set `AUTH_DEV_LOGIN=false` (or unset it) in `.env.local` and fill `AUTH_GOOGLE_ID/SECRET` (and/or
`AUTH_FACEBOOK_*`). Create an OAuth client in the Google Cloud / Meta console with the redirect URI
`http://localhost:3000/api/auth/callback/{google,facebook}` (localhost `http://` is allowed in dev).
Then seed a `dashboard_user` row for your verified identity so login resolves to a tenant (otherwise
it's denied):

```sql
INSERT INTO dashboard_user (provider, subject, email, tenant_id)
VALUES ('google', '<your-google-sub>', 'you@example.com', 'dev');
```

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
