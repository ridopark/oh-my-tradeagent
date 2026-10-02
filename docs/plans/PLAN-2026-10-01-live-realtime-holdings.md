# /live real-time holdings + pipeline connection strip (2026-10-01)

## Goal

The operator's `/live` page shows holdings that move with the market and proves the whole pipeline
is connected:

1. **Holdings update in place, ~1s.** Premium, Underlying, Value, P&L (today), P&L (total) refresh
   every second from server-side caches, marked at the option **bid** (labelled "bid"), with a
   brief flash when a value changes.
2. **The rest of the page refreshes every ~15s** (Qty, all-in, activity strips, account header).
3. **A connection strip** at the top shows, each with green/amber/red and an age:
   Server (dashboard↔BFF), Stock stream, Option prices, Broker (fill stream), Discord watcher.
4. **At least 10 concurrent `/live` viewers** (scope addition, approved 2026-10-01): broker/Temporal
   snapshot work per tenant is independent of the number of viewers.

## Why (current state, verified 2026-10-01)

- `/live` is server-rendered once (`app/live/page.tsx:71` `force-dynamic`); holdings marks come
  from one Alpaca `GET /v2/positions` per page load (`PortfolioService` → `BrokerPositionsClient` →
  `PositionSnapshotWorkflow`). Nothing on the page refreshes except the 1D chart (15s),
  `TrailLiveness` (4s) and `FloorBreach` (30s).
- The server already has fresher prices the page never uses: market-data polls option snapshots
  every **500ms** (`ALPACA_PREMIUM_POLL_INTERVAL_MS`), but only for contracts with an armed trail,
  and holds an Alpaca SIP stock WebSocket (`ALPACA_STOCK_FEED=sip`).
- Health signals exist but are invisible to the dashboard: market-data `/actuator/feedhealth`
  (already proxied via BFF `/api/proximity`), exec `FillListenerMetrics`
  (`fill_listener.subscription_confirmed`, `last_event_age_seconds`, Prometheus only), and the
  Discord sidecar's heartbeat file `/app/state/heartbeat` (k8s probe only).

## Hard constraints (apply to every phase)

- **prod_real is a live-money tenant. No change to order, exit, sizing, trail or kill-switch
  behavior.** Files under `services/orchestrator/**/workflows/`, exec order placement
  (`AlpacaPaperBroker` order methods, `FillDispatcherImpl`, `OrderIntentJournal`), and the trail
  subscription path (`SubscribePremiumActivityImpl`, `PremiumSubscriptionRecovery`) must not change
  behavior. Additive reads only.
- **Display polling is isolated from trail liveness.** `/api/trail-liveness` judges a trail
  "orphaned" from premium subscriptions (`subscribers`, `last_poll_ok_at`). A display-only interest
  must NOT appear as a subscriber, must NOT emit ticks to trail consumers, and must NOT make an
  orphaned trail read as live. Proven by test.
- **The 1s poll never reaches the broker trading API.** Fast paths read caches only. Alpaca
  trading-API calls stay where they are (page render / 15s refresh).
- **Bounded server work.** The client never supplies a tenant id or an OCC list; the BFF derives
  the OCC set from the tenant's open positions. market-data caps concurrent display OCCs at 25 and
  stops polling an OCC 30s after the last request for it (so polling only runs while someone is
  watching).
- **Degrade, never blank, never false-green.** Every new read is fail-soft and shown as
  "unknown"/stale, and dashboard reads of new endpoints sit outside the existing `Promise.all`
  (services roll independently; a 404 from a not-yet-rolled BFF must not render `LiveUnavailable`).
- Spotless before commit (Java). Determinism guardrails untouched (no workflow code changes).

## Phases (one PR, one commit per phase; each degrades safely until the next service rolls)

### P1 — market-data: display marks endpoint (java-architect)

- `GET /md/marks?occ=<OCC>&occ=<OCC>…` registers/refreshes display interest per OCC (TTL 30s since
  last request) and returns the **cached** latest values:
  `{now, marks:[{occ, bid, mid, ask, quote_at, underlying:{ticker, price, at}, warming}]}`.
  Null fields when unknown; `warming=true` on first sight before the first poll lands.
- Option quotes: if the OCC is already polled for a trail, reuse that cached snapshot (no second
  poll). Otherwise run a display-only poll at the existing `premium-poll-interval-ms`. Display
  interest is tracked separately from trail subscribers (see hard constraint).
- Underlying: last trade from the SIP stock stream when it is connected (display-subscribe the
  ticker with the same TTL); otherwise a cached snapshot refreshed at most once per second per
  ticker.
- Cap 25 concurrent display OCCs; extras return `capped=true` with null prices.
- Tests: isolation (subscriber counts and trail tick emission unchanged with display interest on the
  same and a different OCC; `premium-subscriptions` output unchanged), TTL expiry stops the poll,
  cap enforced, trail-poll reuse issues no extra snapshot call.

### P2 — status sources: broker fill stream + Discord heartbeat (java-architect + general-purpose)

- exec: read-only `GET /status/fill-listener` →
  `{tenants:[{tenant_id, connected, subscription_confirmed, last_event_age_s, reconnects}]}` from the
  state `FillListenerMetrics` already tracks. No change to the listener itself.
  *Amended 2026-10-01 (user decision):* the BFF must NOT reach exec directly — a NetworkPolicy is
  port-level, so admitting the BFF would also expose the live exec pod's
  `/internal/broker-credentials` route to it, and `infra/k8s/52*-exec-*.yaml` stay untouched by this
  plan. The BFF reads this status through a read-only api-gateway route
  (`GET /internal/live/fill-listener-status?tenant=`) that reuses api-gateway's existing
  tenant → broker_target → exec routing and service-token auth; unreachable → Broker "unknown".
- signal-source-discord: make the watcher's heartbeat readable by the BFF with the least new
  infrastructure (consult: tiny HTTP `/healthz` on the existing pod behind its Service, or a
  heartbeat key via an endpoint the sidecar already calls). The publish must be fire-and-forget with
  a ≤1s timeout and can never block or crash the watch loop. Python tests cover a failing publish.
- k8s manifests/NetworkPolicies updated if a new port or route is needed (kubeconform must pass;
  check `infra/k8s` NetworkPolicies — the BFF→X path must be allowed explicitly).

### P3 — BFF: marks + connection endpoints (dashboard-dev; Temporal-touching code via java-architect)

- `GET /api/live/marks` (tenant from session): OCC set = tenant's open positions, cached ≤15s
  (reuse the existing positions read; no extra Temporal fan-out per 1s request). Calls market-data
  `/md/marks`. **No broker call.** Test proves it (broker client mock never invoked).
- `GET /api/live/connection`: `{server_time, market_data:{equity, option}, broker, discord}` where
  each part is independently fail-soft (`status:"unknown"` + reason) with ≤2s timeouts, run
  concurrently.
- Portfolio rows gain an additive `lastday_price` field (already known inside
  `BrokerPositionsClient`) so the dashboard can compute today's P&L at bid.
- *Scope addition (approved 2026-10-01) — 10 concurrent viewers:* cache each tenant's full portfolio
  read (positions + Alpaca marks + account equity, the work behind `GET /api/portfolio`) for ~10s,
  keyed by tenant, single-flight (concurrent requests for the same tenant share one in-flight load).
  Force-exit / trim / stop-loss (and manual-entry) success paths invalidate that tenant's cache so the
  operator sees the result immediately. Alpaca trading-API calls per tenant are then independent of
  viewer count (≤ 2 per 10s window).

### P4 — dashboard /live (dashboard-dev)

- Proxy routes `app/api/live-marks/route.ts`, `app/api/live-connection/route.ts` (shape of
  `app/api/trail-liveness/route.ts`).
- Client provider polls marks every 1s and connection every 5s; both pause when the tab is hidden.
- Holdings cells (table and mobile cards): Premium = bid (label "bid"), Underlying, Value = bid ×
  qty × 100, P&L today = (bid − base) × qty × 100 where base = entry premium if entered today else
  `lastday_price`, P&L total and all-in recomputed from bid + the row's realized amount. Pure math in
  `dashboard/lib/liveMarks.ts`. When a mark is missing, warming, capped or older than 10s, fall back
  to the server-rendered Alpaca value and show it as stale (muted + age), never as live.
- Flash on change (green up / red down, ~600ms), respecting `prefers-reduced-motion`.
- Full refresh: `router.refresh()` every 15s, paused while hidden **and while any action panel
  (Trim / Stop-loss / Force-exit / manual entry) is open** — `page.tsx:237` documents that a
  revalidate remounts panels and kills their polls.
- `ConnectionStrip` at the top of `/live`: Server (age of last successful poll; red after 3
  consecutive failures), Stock stream, Option prices, Broker, Discord. Outside regular trading hours
  the two market feeds show grey "market closed" rather than red.

## Success criteria

1. `mvn -q -pl services/market-data,services/exec,services/tenant-dashboard-bff -am verify` passes;
   signal-source-discord `uv run pytest -q` passes; `cd dashboard && npm run typecheck && npm run
   lint && npm run build` passes; kubeconform passes.
2. P1 isolation tests exist and pass, and were watched failing against a deliberately non-isolated
   implementation (falsification evidence in the PR).
3. P3 test proves `/api/live/marks` performs no broker call; P1 test proves trail-poll reuse issues
   no extra snapshot call.
4. `git diff origin/main --stat` touches none of the hard-constraint files listed above (paste the
   stat into the PR).
5. Rendered evidence: screenshot of `/live` with the connection strip and live-marked holdings
   (local run against stub/sandbox data is acceptable), plus a screenshot of the degraded state
   (BFF marks endpoint failing → cells stale, Server red within 5s).
6. *(Scope addition, approved 2026-10-01)* Load check: 10 concurrent viewers of the same tenant for
   60s (1s marks poll, 5s connection poll, 15s full refresh each) against the BFF with the broker
   client mocked/counted. Pass = broker/Temporal snapshot calls ≤ 1 load per 10s window for that
   tenant (not ×10), no request errors, and p95 latency of `/api/live/marks` < 200ms locally. The
   script lives under `scripts/` or the BFF test tree; its output is pasted into the PR evidence.

## Halt conditions

- Display polling cannot be isolated from trail liveness without changing the trail subscription
  path → halt and report; do not modify the trail path.
- Any phase requires changing an order/exit/sizing/kill-switch path → halt.
- The Discord heartbeat cannot be exposed without a change to the watch loop's control flow → ship
  P1/P3/P4 with Discord shown as "unknown", note it in the PR, do not block on it.

## Ordering / deploy

All phases ship in one PR; the dashboard degrades until each service rolls. Merge **outside market
hours**: rolling market-data restarts premium polling for armed trails (recovered by
`PremiumSubscriptionRecovery`, RTH-gated). Merging deploys automatically (`deploy.yml` chains off
the image build). The PR is opened, not merged — the operator merges.

## Out of scope

SSE/WebSocket push to the browser; changing the trail poll cadence; Alpaca options WebSocket;
order/exit behavior.
