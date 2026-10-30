# PLAN — 2026-10-29 flow-page

Source: GitHub issue #1018 (PRD: real-time options flow at `/flow` + trade-tape archive). The issue's
**Decisions (grilling with the operator, 2026-10-29)** are binding and are not reopened here. Build order
is the operator's: one PR per step, in the order below. Prior art copied throughout: the dark-pool feature
(`docs/plans/PLAN-2026-10-29-darkpool-prints.md`, shipped as #1005/#1006/#1008/#1012).

## Goal

The operator (and every signed-in tenant user) opens `/flow` and sees, within seconds of the trade, the
day's large option orders — side, premium, sweep/block/spread labels — on an Options tab next to the
existing Dark pool tab, with a per-ticker view combining both; and every OPRA trade of every session is
archived on disk for later research. Nothing trades on it.

**Done when:**
- A dry-run session has produced a measurement report (trades/s, quotes/s, peak RSS, orders/day at
  $25k/$50k/$100k, archive bytes) and the operator has confirmed the 300 Mi cap and the storage floor.
- `curl -H "Authorization: Bearer $BFF_SHARED_TOKEN" -H "X-Tenant-Id: staging_paper" \
  $BFF/api/flow/orders?min_premium=100000` returns today's orders ≥ $100k, newest first, each with
  `side`, `side_source`, `sweep`, `block`, `multi_leg`, `premium`, and the threshold values used.
- `GET /flow` renders the Options tab with the default ≥ $100k filter; `GET /flow/dark-pool` renders what
  `/dark-pool` renders today; `GET /dark-pool` responds 308 to `/flow/dark-pool`; the nav shows one "Flow"
  entry and no "Dark pool" entry.
- `GET /flow/ticker/AAPL` shows AAPL's call-vs-put and bought-vs-sold premium, its largest orders, and its
  dark-pool levels and large prints; `GET /flow/ticker/ZZZZ` (a name outside the dark-pool universe) shows
  options only with a note.
- On the homelab, `<archive>/<YYYY-MM-DD>.trades.ndjson.gz` + `<YYYY-MM-DD>.manifest.json` exist for each
  session since the collector's first apply, and `flow_order` holds nothing older than 90 days.

## Non-goals

- Any trading use of flow, any signal, any Discord post about flow (collector-health alerts only).
- Storing every quote; quoting more than the measured cap; a second stock stream; any change to
  `services/market-data`.
- Widening the dark-pool universe (59 names) or changing the dark-pool collector.
- Overnight / index sessions outside 09:25–16:20 ET; index-option symbol mapping (SPX vs XSP).
- The monthly archive offload itself (an operator `rsync` + delete; documented, not automated).
- Mobile-specific views; the operator "Admin" surface.
- The pre-registered evaluation (a research task, after the data exists).

## Spec

### Problem Statement

The account already pays for real-time OPRA, yet the operator watches options flow on delayed third-party
free tiers, and every flow study in this repo (round 17: 14k unsigned signals, Holm p = 1.0) lacked the
one field that matters — the side of each trade — because option quotes cannot be backfilled.

### Solution

A read-only collector pod holds the account's single OPRA options-stream slot 09:25–16:20 ET, subscribes
to all option trades (wildcard) plus live quotes for ≤ 950 chosen contracts, labels each trade, groups
trades into orders, archives every trade to daily files, and posts orders ≥ $25k premium plus per-ticker
daily summaries to the dashboard BFF. The dashboard gains `/flow` with an Options tab (new), a Dark pool
tab (today's `/dark-pool`, moved) and a combined per-ticker view.

### User Stories

The 39 stories in #1018 stand. The ones each step must satisfy are cited by number in the steps.
Stories 25–28, 30, 33–34, 36–37 land in Step 1; 29, 31–32, 35 in Step 2; 1–18, 20–21, 24, 38 in Step 3;
19 in Step 4. Story 22–23 (watchlist quotes, rotation) land in Step 1 with a static universe and gain the
day's watchlist in Step 2. Story 39 is a research deliverable, out of this plan.

### Operator Decisions (recorded 2026-10-30)

- **Q1 (tick-rule fallback):** DROPPED — decided by operator 2026-10-30. `side_source ∈ {quote, none}`;
  side=unknown without a live quote. The tick-rule fallback is out of scope (see Non-goals).
- **Q5 (memory limit for dry-run):** decided by operator 2026-10-30. The dry-run session runs with a
  400 Mi memory limit (so `rss_peak_mb` is measured, not OOM-killed). Step 2 lowers the limit to 300 Mi
  after the measurement confirms `rss_peak_mb ≤ 240 MB`.
- **Q2, Q3, Q4, Q6, Q7, Q8:** accepted as the planner assumed (decided by operator 2026-10-30).

### Implementation Decisions

**Verified facts relied on (2026-10-29):**
- Homelab node: 15.3 Gi allocatable, 85 % used, ~1.7 Gi available and swapping; CPU 20 % used. One 218 G
  disk shared with the trading Postgres, 93 G free. Existing PVC pattern: `storageClassName: local-path`
  (`infra/k8s/55-signal-source-discord.yaml`, `10-postgres.yaml`); local-path does not enforce capacity, so
  the collector's own disk guard is the real cap.
- Alpaca: one WebSocket per endpoint per account; stock (`/v2`) and options (`/v1beta1`) endpoints are
  separate (verified 2026-06-20, `services/market-data/src/main/resources/application.yml:63-71`). Nothing
  holds the options endpoint today: market-data's `data-ws-url` is "retained but UNUSED" (same file, line
  21; premium is a REST poll). A second connection to the same endpoint is refused with 406 and the first
  survives — so the collector is **one replica with `strategy: Recreate`**, and its reconnect loop treats
  a 406 as "the previous pod still holds the slot": back off and retry, never crash-loop.
- Options stream is msgpack-only, in-band `{"action":"auth"}` message auth, wildcard trades allowed,
  wildcard quotes not allowed, quote subscription cap measured between 1,000 and 2,000
  (`scripts/alpaca-options-ws-probe.py`; #1018 probe: ~221 trades/s midday, 2,063 contracts in 20 s).
- OPRA trade condition codes (Alpaca conditions endpoint, per #1018): `S` ISO sweep; `a`/`c` single-leg
  auction / cross; `b`/`d` single-leg auction / cross with ISO; `f g h i j l` multi-leg. Mapping used:
  `sweep = c ∈ {S,b,d}`, `auction = c ∈ {a,b}`, `cross = c ∈ {c,d}`, `multi_leg = c ∈ {f,g,h,i,j,l}`; the
  raw codes are always recorded so a re-label is possible. The builder re-fetches the conditions endpoint
  once and pins the mapping in a test fixture.
- Measured OPRA quote cadence (project memory): liquid weeklies ~60 ms p50 per contract. 950 liquid
  contracts can therefore push 5–15k quote frames/s — the quote set, not the trade tape, is the CPU and
  memory risk the dry run must measure.

**Image: the collector ships in the existing sidecar image** (`ghcr.io/ridopark/oh-my-tradeagent-signal-source-discord`),
as `ohmytradeagent_sidecar.flow_main`, exactly like `darkpool_main`. Why: the 300 MB cap is a property of
the process RSS, not of the image — a `python -m` entrypoint never loads Chromium, and `darkpool-collector`
already runs in this image at a 256 Mi limit. An own image would buy only a decoupled rollout, at the cost
of a new Dockerfile, a build-matrix row, a cache scope and a new `deploy.yml` service case; and rollout
timing is already solved the other way: every sidecar merge also rolls `signal-source-discord`, which the
deploy agent only approves while the market is closed, and `flow-collector` is classified the same. Two
new runtime dependencies (`msgpack`, `websockets`) go into the sidecar `pyproject.toml` + `uv.lock`.
Revisit only if the dry run shows the interpreter baseline in this image is itself the problem (it is
not expected to be: `darkpool-collector` is the same interpreter).

**Memory discipline (the 300 MB cap):** no per-contract state for the whole tape. The tape sees ~0.5 M
distinct contracts a day, and a Python dict entry per contract costs ~200 B, so any "last price / day
volume per contract" map is ~100 MB on its own. Bounded state only: (a) open order windows (contracts with
a trade in the last ~1 s), (b) last quote for the ≤ 950 quoted contracts, (c) a sparse rolling-premium map
for quote-set ranking (only contracts with ≥ $10k premium in the current 5-min bucket; pruned every 5 min),
(d) per-ticker daily accumulators (~5k tickers). Consequences: the side fallback by tick test is dropped
(`side_source ∈ {quote, none}`; see Open questions); day volume and open interest come from REST at order
time (Step 2), not from in-memory tape state.

**Collector modules (`services/signal-source-discord/ohmytradeagent_sidecar/`):**
- `flow_collector.py` — pure, unit-tested: msgpack frame → `Trade`/`Quote` records; `classify(trade,
  quote)` → side ∈ {buy, sell, mid, unknown}, `side_source` ∈ {quote, none} (a quote counts only if
  `quote.ts <= trade.ts` and `trade.ts - quote.ts <= FLOW_QUOTE_MAX_AGE` (3 s); one-sided or crossed
  books classify nothing — same rule as `darkpool_collector.side`), condition-code labels, premium
  (`price × size × 100`), DTE on the ET date, OCC parse (ticker, expiry, strike, right);
  `OrderAggregator.feed(trade, labels) -> list[Order]`: same contract + same side, each trade within
  `FLOW_ORDER_WINDOW_MS` (1000) of the previous one, across exchanges; `flush(now)` emits windows idle for
  longer than the window; an order carries summed size and premium, VWAP, trade and exchange counts, the
  first trade's quote, `block = premium ≥ FLOW_BLOCK_PREMIUM (250000) OR size ≥ FLOW_BLOCK_SIZE (1000)`,
  the threshold values used, and a deterministic `order_id = "{contract}:{first_ts_ns}:{side}"` so a
  re-post after a restart dedupes; `QuoteSet.choose(universe_contracts, rolling_premium, cap) ->
  (chosen, subscribe, unsubscribe)`: universe near-money (±5 % of spot) contracts expiring ≤ 14 days
  first, interleaved across names and ordered by |moneyness| then DTE, filling up to
  `FLOW_QUOTE_UNIVERSE_SHARE` (0.7) of the cap, then the top rolling-premium contracts of the last 15 min;
  cap `FLOW_QUOTE_CAP` (950); re-chosen every 5 min; `TapeArchiver`: one gzip member per flush appended to
  `<dir>/<YYYY-MM-DD>.trades.ndjson.gz` (concatenated members are a valid gzip), rows
  `{t,S,p,s,x,c,bp,ap,bs,as,qt}` with quote fields null when no live quote; at session end a
  `<date>.manifest.json` `{date, file, bytes, sha256, trades, first_t, last_t, gaps:[{from,to,reason}],
  window, build}`; the disk guard stops archiving (page keeps working) when the archive dir exceeds
  `FLOW_ARCHIVE_CAP_BYTES` (20 GB) or the volume's free space falls under `FLOW_ARCHIVE_FREE_FLOOR_BYTES`
  (15 GB), records the gap, and alerts (Step 2 wires the webhook; Step 1 logs).
- `flow_main.py` — the stream client (connect, in-band auth, `{"trades":["*"]}` + the quote-set diff,
  reconnect with backoff recording each gap, 406 = slot held → back off), the 09:25–16:20 ET weekday
  window (idle outside, heartbeat touched always), a 1-s flush tick, the quote-set rotation (REST:
  `GET /v2/stocks/trades/latest?symbols=` for spot, `GET /v2/options/contracts` per universe name cached
  per day), the metrics line every 60 s, dry-run (`FLOW_DRY_RUN`, only the literal `false` posts — same
  parser as `darkpool_main.parse_dry_run`), and in Step 2 the ingest client and reference-data lookups.
  REST use is hard-capped (`FLOW_MAX_REST_PER_MIN`, 300) with the same `X-Ratelimit-Remaining` floor
  (2000) as the dark-pool client, because the 10k/min budget is shared with market-data's fail-closed
  kill-switch path.
- Metrics line (every 60 s, and an end-of-session summary): `trades/s` (mean, 1-s peak), `quotes/s`
  (mean, peak), `loop_lag_max_ms` (event-loop lag), `orders/min` total and `≥25k/≥50k/≥100k`,
  `quoted_premium_share` (share of ≥ $25k order premium with `side_source=quote`), `rss_mb` (VmRSS from
  `/proc/self/status`) and `rss_peak_mb`, state sizes (open windows, quoted contracts, rolling map,
  distinct contracts seen), `archive_bytes_today`, `gaps`, `reconnects`, `rest_calls_min`.

**Storage (Step 2, `services/tenant-dashboard-bff`):** `V16__flow.sql` creates `flow_order` (one row per
order ≥ $25k; `UNIQUE (order_id)`; indexes `(first_ts DESC)` and `(ticker, first_ts DESC)`; columns =
the wire contract below; `GENERATED ALWAYS AS IDENTITY` id, as V15 explains) and `flow_ticker_day`
(`PRIMARY KEY (ticker, session_date)`; call/put/bought/sold/mid/unknown premium, trade count, order count
≥ floor, quoted premium share; upserted) with grants: writer `SELECT, INSERT, DELETE` on `flow_order` (no
UPDATE: an order is immutable), `SELECT, INSERT, UPDATE, DELETE` on `flow_ticker_day`, readonly `SELECT`
on both; SELECT on the writer is load-bearing (PG16 `ON CONFLICT` reads the arbiter columns). Retention:
`FlowRetention` deletes orders older than 90 days and ticker-days older than 730 days, batched, never
throws (copy `DarkpoolRetention`, including its load-bearing `@Autowired`). The collector posts orders ≥
`FLOW_STORE_MIN_PREMIUM` (25000) as they close and the dirty ticker-days every 60 s, chunked ≤ 5000 rows.

**BFF API (Step 2):** `POST /internal/flow/ingest {orders:[], ticker_days:[]}` behind the ingest token
(`/internal/flow/` added to `ServiceTokenFilter.INGEST_PREFIXES`; the traversal guard untouched);
`GET /internal/flow/watchlist?date=` (same prefix, same token) returns today's watchlist tickers via the
existing `WatchlistDailyReader` so the collector can quote the day's names without a tenant credential;
`GET /api/flow/orders?date=&ticker=&min_premium=&labels=sweep,block,multi_leg,unusual&right=C|P&side=&dte_min=&dte_max=&before=&limit=`
(session date in ET bounds `[09:25, 16:20)`, newest first, limit default 200 max 500, `before` = the last
row's `first_ts`) and `GET /api/flow/summary?date=&ticker=` (ticker-day rows). All gated on
`flow.enabled` (`FLOW_ENABLED`, default false) AND `dashboard.writer.enabled`, like dark pool; the live
BFF env is operator-patched, so enabling is an operator `kubectl set env`.

**Wire contract (binding between collector and BFF; snake_case = column names; times RFC-3339):**
order: `order_id, ticker, contract, expiry, strike, right, first_ts, last_ts, trade_count,
exchange_count, size, premium, vwap, side, side_source, bid, ask, quote_ts, sweep, multi_leg, auction,
cross, block, unusual, day_volume, open_interest, underlying_price, moneyness_pct, dte, conditions
(space-joined), block_premium_threshold, block_size_threshold, order_window_ms, quote_max_age_ms`.
ticker_day: `ticker, session_date, call_premium, put_premium, bought_premium, sold_premium, mid_premium,
unknown_premium, trade_count, order_count, quoted_premium_share`. `unusual`, `day_volume`,
`open_interest`, `underlying_price`, `moneyness_pct` are null when the lookup was unavailable (Step 1
always null).

**Reference data (Step 2, collector side, at order flush, cached per contract per day, under the REST
cap):** open interest from `GET /v2/options/contracts/{contract}`; day volume from
`GET /v1beta1/options/snapshots?symbols=` (`dailyBar.v`, batched); underlying price from
`GET /v2/stocks/trades/latest?symbols=` (batched per flush); `unusual = day_volume > open_interest` when
both are known, else null; `moneyness_pct = (strike − spot)/spot` signed by right. The daily OI files
written by `scripts/data/option_oi_collector.py` live outside the cluster and are not used.

**Dashboard (Steps 3–4):** route group `app/flow/` with `layout.tsx` (Nav + a two-tab bar: Options →
`/flow`, Dark pool → `/flow/dark-pool`), `page.tsx` (Options), `dark-pool/page.tsx` (the body of today's
`app/dark-pool/page.tsx`, moved verbatim), `ticker/[symbol]/page.tsx` (Step 4). `app/dark-pool/page.tsx`
becomes `permanentRedirect("/flow/dark-pool")`. `Nav.LINKS`: the `/dark-pool` entry becomes
`{ href: "/flow", label: "Flow" }` in the same position (5th, so it lands in the mobile "More" drawer and
needs no icon; `MobileBottomNav.isActive` uses `startsWith`, so sub-routes highlight). Options tab: a
client island polling `/api/flow/orders` every 5 s inside 09:25–16:20 ET via the existing generic
`useDarkpoolRead`/`useNow`/`FreshnessNote` (`components/DarkpoolRead.tsx`; they are url-generic and the
flow proxies return the same `{disabled:true}` shape), a filter bar (ticker, min premium default $100,000
with a floor of $25,000, labels, C/P, side, DTE range), a date picker bounded to the 90-day retention, and
empty/stale states naming `flow-collector`, `FLOW_DRY_RUN` and `FLOW_ENABLED`. Next proxies
`app/api/flow/orders/route.ts` and `app/api/flow/summary/route.ts` mirror `app/api/darkpool/*` (forward
only known params, clamp limit, 404 → `{disabled:true}`); `lib/bff.ts` gains `getFlowOrders` /
`getFlowSummary` next to the dark-pool pair; `lib/flow.ts` holds the wire types and pure helpers with
`lib/flow.test.mjs` (`npm run test:lib`). The combined ticker view reads `/api/flow/summary?ticker=`,
`/api/flow/orders?ticker=&sort=premium_desc&limit=20` (Step 4 adds `sort`), `/api/darkpool/summary` (row
for the symbol; absent → "no off-exchange data for X today: outside the dark-pool universe or no prints
yet") and `/api/darkpool/prints?symbol=&min_notional=200000`; the ticker cells of the Options table and
the dark-pool summary table link to it.

**Ops:** `infra/k8s/67-flow-collector.yaml` = PVC `flow-archive` (local-path, 25 Gi, documentation only)
+ Deployment `flow-collector` (`command:` override — NEVER `args:`, the darkpool manifest's highest-
consequence comment is copied; `strategy: Recreate`; `envFrom` the existing `alpaca-credentials-data`
Secret; `OPTIONS_CHAT_INGEST_TOKEN` from `dashboard-secrets`; the `darkpool-universe` ConfigMap mounted
as the universe; heartbeat liveness; dry run: requests 256 Mi / limit 400 Mi so the peak is measured
rather than OOM-killed, Step 2 sets limit 300 Mi) + NetworkPolicy `tenant-dashboard-bff-allow-flow-
collector` (copy of the darkpool one). First create is a manual `kubectl apply`; `deploy.yml`'s
bootstrap guard is generalised from the single `darkpool-collector` literal to a two-entry set, and the
routing test's guard cases cover both. Collector-health alerts (stream down > 5 min inside the window,
archive stopped) post to `ALERT_DISCORD_WEBHOOK_URL` from the `discord-alert-credentials` Secret, deduped
per cause per day; the missed-session check is an operator cron (16:45 ET: today's manifest exists and
`trades > 100000`, else alert), like `darkpool_check.sh`.

### Testing Decisions

- A good test here observes a module's boundary with real wire shapes and small hand-checked fixtures:
  what `classify`/`OrderAggregator`/`QuoteSet`/`TapeArchiver` return, what a BFF request returns, what a
  migration grants, what a proxy forwards. No test mocks an internal collaborator; the stream and the
  BFF are `httpx.MockTransport`/fake-socket fakes at the process edge (prior art
  `tests/test_darkpool_main.py`).
- Collector: `tests/test_flow_collector.py` and `tests/test_flow_main.py` (pytest, `asyncio_mode=auto`;
  prior art `test_darkpool_collector.py`, `test_darkpool_main.py`).
- BFF: `DashboardMigrationSqlStructureTest` gains a V16 block; `FlowMigrationIT` (RUN_DB_ITS, Testcontainers,
  runs the repository's real SQL as `dashboard_writer`, prior art `DarkpoolMigrationIT`);
  `FlowIngestControllerWebMvcTest`, `FlowControllerWebMvcTest`, `FlowRetentionTest`,
  `ServiceTokenFilterTest` + the third prefix; `SpringComponentConstructorGuardTest` already pins the
  two-constructor trap.
- Dashboard: no JS runner for components — `npm run typecheck && npm run lint && npm run build`, plus
  `npm run test:lib` for `lib/flow.test.mjs`, plus a rendered check against a stubbed BFF with a
  screenshot in the PR (prior art: the dark-pool phase 3 PR).
- Workflow: `python3 scripts/tests/test_deploy_matrix_routing.py` (routing + bootstrap guard) and the
  deploy agent's `tests/test_deploy_gate.py` (service-class table completeness).

### Out of Scope

See Non-goals. Also: a tick-rule side fallback, storing quotes, a universe list in the dashboard, the
archive offload automation, alert routing per tenant, index-option mapping.

### Further Notes

- Licensing risk is recorded in #1018 and accepted by the operator; the data is shown only behind
  dashboard sign-in.
- The step order is deliberately inverted from the dark-pool plan (collector first, BFF second) because
  the measurement gates the storage floor and the memory cap.
- Prior research to read before any evaluation: rounds 7, 9 (H9.2), 17; the sister dark-pool study.

## Steps

### Step 1 — Collector dry run: stream, classify, aggregate, archive, measure

**Goal:** `flow-collector` runs one full session on the homelab in dry run (posts nothing; there is
nothing to post to yet), archives the tape, and logs the numbers that decide the 300 Mi cap and the
storage floor. Stories 22–23 (static universe), 25–28, 30, 33–34, 36–37.

**Blocked by:** none.

**Scope (files):** `services/signal-source-discord/pyproject.toml` + `uv.lock` (`msgpack`,
`websockets`); `ohmytradeagent_sidecar/flow_collector.py`, `flow_main.py`; `tests/test_flow_collector.py`,
`tests/test_flow_main.py` (+ a small msgpack fixture); `infra/k8s/67-flow-collector.yaml`;
`docs/ops/flow-archive-format.md` (story 28); `.github/workflows/deploy.yml` (dispatch option, `SERVICES`,
`add_sidecar`, the `services/signal-source-discord/*` case, the manifest map line for 67, the bootstrap
guard set) + `scripts/tests/test_deploy_matrix_routing.py` (`SIDECAR` set, guard cases for
`flow-collector`, the 67 manifest). **Isolate the workflow edit as the PR's own last commit** and say in
the PR body that this PR cannot run its own Claude review because it touches
`.github/workflows/deploy.yml` (NO_CHECK; plain CI + the two Fable reviews are the gate). The deploy
agent's `scripts/ops/pr-deploy-agent/deploy_gate.py` `SERVICE_CLASS` gains
`"flow-collector": "signal-watcher"` (+ its README row and `tests/test_deploy_gate.py` list) **in whichever
branch merges second** — that file is on the unmerged `feat/pr-deploy-agent` branch; if it is on `main`
when this step is built, change it here; otherwise record the entry in the PR body as a required edit to
that branch, because its test asserts every `deploy.yml` service is classified.

**Seams:**
- `flow_collector.parse_frame(bytes) -> list[Trade|Quote|Control]` — catches wrong field mapping and
  non-list frames; misses server-side behaviour (auth, limits).
- `flow_collector.classify(trade, quote, now)` — catches side/age/ordering rules and label mapping;
  misses whether the quote set covers the contracts that matter.
- `OrderAggregator.feed/flush` — catches grouping, premium/VWAP, thresholds, deterministic ids; misses
  real-tape ordering anomalies (the dry run covers those).
- `QuoteSet.choose` — catches priority, cap, share, diff; misses the real cadence of quote frames.
- `TapeArchiver` on a tmp dir — catches file naming, append-across-flushes gzip validity, manifest
  checksum, cap/free-floor stop + gap record; misses real disk behaviour.
- `flow_main` loop with a fake socket + `httpx.MockTransport` — catches window gating, reconnect/gap
  bookkeeping, 406 backoff, dry-run posting nothing, metrics line fields, heartbeat; misses Alpaca itself.

**Success checklist (all must be checked, each with evidence):**
- [ ] Classifier tests pass, including the two the decisions require — a quote stamped after the trade
      is never used (`side_source=none`), a sweep across ≥ 3 exchanges within 1 s is one order — plus:
      quote older than 3 s → none; buy at/above ask, sell at/below bid, mid between; one-sided/crossed
      book → unknown; a trade > 1 s after the previous starts a new order; different sides never merge;
      `premium == vwap × size × 100`; block inclusive at both thresholds and the thresholds recorded;
      `order_id` identical when the same trades are replayed; `S`/`b`/`d` → sweep, `f..l` → multi_leg;
      DTE on the ET date across midnight UTC — evidence: `cd services/signal-source-discord && uv run
      pytest -q tests/test_flow_collector.py` output with the test names.
- [ ] `QuoteSet.choose` never exceeds the cap, fills the universe share first interleaved across names,
      and returns an exact subscribe/unsubscribe diff — evidence: test names in the same run.
- [ ] `TapeArchiver`: a file written across three flushes decompresses with `gzip -t` and `zcat | wc -l`
      equals the trades fed; the manifest's `sha256` equals `sha256sum` of the file; at the cap the next
      write is refused, a gap is recorded, and the loop keeps classifying — evidence: test names.
- [ ] `flow_main`: outside 09:25–16:20 ET nothing connects and the heartbeat is still touched; inside,
      a dropped socket reconnects with a recorded gap; a 406 backs off without exiting; dry run never
      calls the ingest URL; the metrics line contains every field listed under "Metrics line" —
      evidence: `uv run pytest -q tests/test_flow_main.py` + `uv run ruff check .` clean.
- [ ] `python3 scripts/tests/test_deploy_matrix_routing.py` passes with: a `services/signal-source-discord/*`
      change rolls the sidecar set including `flow-collector`; `infra/k8s/67-flow-collector.yaml` rolls
      only `flow-collector`; a missing `flow-collector` Deployment skips with a notice naming the 67
      manifest; a missing `darkpool-collector` still skips; `lotto-signal`/`market-data`/`orchestrator`
      are still never exempted — evidence: the test output.
- [ ] Manifest facts: `command:` not `args:`; `replicas: 1`; `strategy.type: Recreate`; `envFrom`
      `alpaca-credentials-data`; `FLOW_DRY_RUN: "true"`; limit `400Mi` with a comment that Step 2 sets
      300 Mi; PVC `flow-archive` mounted at the archive dir; liveness on the heartbeat mtime — evidence:
      `kubectl apply --dry-run=client -f infra/k8s/67-flow-collector.yaml` and a `grep` of each line.
- [ ] **Operator session (after merge, image built, manual `kubectl -n copytrade apply -f
      infra/k8s/67-flow-collector.yaml` while the market is closed):** one full session with zero
      `OOMKilled` restarts (`kubectl -n copytrade get pod -l app=flow-collector -o
      jsonpath='{.items[0].status.containerStatuses[0].restartCount}'` = 0) — evidence: that command.
- [ ] **Measurement report** posted as a comment on #1018 with these exact numbers from the metrics
      lines and `kubectl top pod` at 09:35, 12:30, 15:58 ET: `trades/s` mean and 1-s peak at open
      (09:30–09:35), midday, close (15:55–16:00); `quotes/s` mean and peak; `loop_lag_max_ms`;
      `rss_peak_mb`; CPU millicores at the three samples; orders/day total and ≥ $25k, ≥ $50k, ≥ $100k;
      `quoted_premium_share`; `archive_bytes_today` and `trades`; distinct contracts seen; `reconnects`
      and total gap seconds; `rest_calls_min` peak — evidence: the comment URL.
- [ ] **Go/no-go recorded in that comment**, by these thresholds: `rss_peak_mb ≤ 240` → the 300 Mi cap
      is confirmed for Step 2; `240 < rss_peak_mb ≤ 300` → Step 2 only after a memory cut (lower
      `FLOW_QUOTE_CAP`, shrink the rolling map) and a second dry-run session; `rss_peak_mb > 300` →
      STOP and re-plan (per the operator decision existing memory is cut first, never trading pods);
      orders ≥ $25k/day `> 100,000` → the floor is raised to the smallest of $50k/$100k that is
      ≤ 100,000 before Step 2 (operator confirms); `archive_bytes_today > 500 MB` → re-plan the 20 GB
      cap/offload cadence before Step 2; `loop_lag_max_ms > 2000` sustained or CPU `> 800m` at any
      sample → lower `FLOW_QUOTE_CAP` and re-run; `reconnects > 5` or gap seconds `> 300` → investigate
      before Step 2 — evidence: the comment.

**Failure checklist (any one means STOP and report):**
- [ ] The first connect returns 406 (something else holds the options slot) — the verified fact is wrong.
- [ ] The subscription ack rejects `trades: ["*"]` or the quote list at ≤ 950.
- [ ] Peak RSS exceeds 300 MB, or the pod is OOMKilled, or node available memory drops under 1 Gi while
      it runs (`free -g` on the homelab).
- [ ] A test needs a mock of an internal collaborator (the aggregator inside the loop, the archiver
      inside the aggregator) rather than a fake at the socket/HTTP edge.
- [ ] Any change outside the listed files (in particular anything under `services/market-data/`,
      `darkpool_*`, `55-signal-source-discord.yaml`, or a trading-path manifest).
- [ ] The manifest needs `args:` or more than one replica to work.
- [ ] An existing test (darkpool, routing, deploy-gate) must be weakened or deleted to pass.
- [ ] The `uv.lock` change pulls in anything beyond `msgpack` and `websockets` (and their own deps).

### Step 2 — Storage + BFF: migration, ingest, reads, retention; collector posts

**Goal:** orders ≥ the confirmed floor and per-ticker daily summaries flow from the live collector into
the dashboard DB and out through tenant-session reads; retention bounds both tables; collector-health
alerts exist. Stories 29, 31–32, 35, plus 22–23 with the day's watchlist. Dark until the operator sets
`FLOW_ENABLED=true` on the BFF and `FLOW_DRY_RUN=false` on the collector.

**Blocked by:** Step 1 (the floor and the cap are inputs: `FLOW_STORE_MIN_PREMIUM` and the manifest
limit are set from the Step 1 report).

**Scope (files):** BFF — `db/dashboard/V16__flow.sql`, `flow/FlowRepository.java`,
`flow/FlowIngestBatch.java`, `flow/FlowRetention.java`, `web/FlowIngestController.java`,
`web/FlowController.java`, `web/FlowInternalController.java` (`GET /internal/flow/watchlist`),
`security/ServiceTokenFilter.java` (`INGEST_PREFIXES` += `/internal/flow/`), `application.yml`
(`flow.enabled`), tests as listed under Testing Decisions. Collector — `flow_main.py` gains
`FlowIngestClient` (copy `DarkpoolIngestClient`: chunking, retries, 404/401 steady-state logging),
reference-data lookups, the watchlist union, the webhook alerts; `flow_collector.py` gains `unusual` and
`moneyness` on the order; tests extended. Manifest — limit `300Mi`, `FLOW_STORE_MIN_PREMIUM`,
`BFF_INTERNAL_URL`, `ALERT_DISCORD_WEBHOOK_URL` from `discord-alert-credentials`. Docs — the operator
bring-up block in this plan's "Repo gates" is the runbook; `docs/ops/flow-archive-format.md` gains the
offload procedure (rsync closed months to the workstation, then delete).

**Seams:**
- `FlowRepository` against real Postgres as `dashboard_writer` (IT) — catches grants, `ON CONFLICT`
  arbiter SELECT, filters, paging, retention; misses the controller layer.
- `POST /internal/flow/ingest` + `GET /internal/flow/watchlist` via MockMvc with the filter — catches
  token gating, traversal under the new prefix, validation 400s, stored counts; misses SQL.
- `GET /api/flow/orders|summary` via MockMvc — catches each filter's binding and the 401 without a
  tenant header; misses SQL (the IT has it).
- `FlowIngestClient` + reference lookups with `httpx.MockTransport` — catches chunking, retry policy,
  cap, null-when-unavailable; misses live rate-limit behaviour.

**Success checklist:**
- [ ] `DashboardMigrationSqlStructureTest` has a V16 block asserting both tables, the named indexes,
      writer privileges exactly `SELECT, INSERT, DELETE` on `flow_order` and `SELECT, INSERT, UPDATE,
      DELETE` on `flow_ticker_day`, readonly `SELECT` on both, and no touch of the recorder role —
      evidence: `mvn -pl services/tenant-dashboard-bff -am spotless:apply && mvn -pl
      services/tenant-dashboard-bff -am verify` green.
- [ ] `FlowMigrationIT` (RUN_DB_ITS=true): a replayed order stores once; a re-posted ticker-day updates
      in place; each `orders` filter (date bounds in ET, ticker, min_premium, each label, right, side,
      dte range, `before` paging) returns the hand-computed rows from a 12-order fixture; `summary`
      returns the fixture's ticker-days; retention deletes only rows past the cutoff; the writer cannot
      UPDATE `flow_order` (42501); readonly cannot INSERT — evidence: the IT names in the CI log.
- [ ] `FlowIngestControllerWebMvcTest`: no token → 401; the shared token → 401;
      `/internal/flow/%2e%2e/api/positions` → 401/404; > 5000 rows → 400; a valid batch → `{stored_orders,
      stored_ticker_days}` — evidence: test names. `ServiceTokenFilterTest` gains the third-prefix cases
      mirroring the darkpool ones.
- [ ] `GET /internal/flow/watchlist` with the ingest token returns `{date, tickers:[…]}` from
      `WatchlistDailyReader`; with the shared token → 401 — evidence: test names.
- [ ] Collector: the ingest client posts snake_case bodies matching the wire contract (a test asserts the
      exact key set), chunks at 5000, retries 5xx, logs-not-retries 404/401, re-sends refused orders with
      the next batch (bounded buffer); reference lookups are batched, cached per contract per day,
      stop at `FLOW_MAX_REST_PER_MIN`, and leave `unusual`/`day_volume`/`open_interest`/
      `underlying_price`/`moneyness_pct` null when unavailable; the watchlist route result is unioned
      with the static universe and a 404/401 from it falls back to the static universe; stream-down >
      5 min inside the window and an archive stop each post one webhook message per cause per day —
      evidence: `uv run pytest -q` + `uv run ruff check .`.
- [ ] Manifest: limit `300Mi`, `FLOW_DRY_RUN: "true"` still (go-live is the operator's flip), the floor
      from the Step 1 report — evidence: `grep` lines in the PR.
- [ ] **Operator bring-up (after merge, market closed):** `kubectl -n copytrade set env
      deploy/tenant-dashboard-bff FLOW_ENABLED=true` (Flyway runs V16 on the restart); `kubectl -n
      copytrade set env deploy/flow-collector FLOW_DRY_RUN=false` + the same edit in the manifest in a
      follow-up commit (deploy.yml re-applies the file); next session `curl … /api/flow/orders` returns
      rows and `SELECT count(*) FROM flow_order` grows — evidence: the curl output and the count.
- [ ] Operator cron `flow_check.sh` installed on the homelab (16:45 ET, weekdays, holiday list), alerting
      when today's manifest is missing or `trades < 100000` — evidence: `crontab -l` line.

**Failure checklist (STOP and report):**
- [ ] The repository needs an `UPDATE` grant on `flow_order` or a sequence grant to insert.
- [ ] A test needs to mock `DSLContext`/the repository inside a controller test instead of the MockMvc
      seam with a stubbed repository bean (prior art: `DarkpoolIngestControllerWebMvcTest`).
- [ ] The ingest route is reachable with the shared token, or a tenant route with the ingest token.
- [ ] The collector's REST use needs more than 300 calls/min to populate the reference fields.
- [ ] Any edit to `58-tenant-dashboard-bff.yaml` (drifted from live; applying it breaks the BFF) or to
      `DARKPOOL_*` behaviour.
- [ ] The Step 1 report said STOP or re-plan and that has not been resolved by the operator.
- [ ] An existing dark-pool/options-chat test must change to pass.

### Step 3 — `/flow` with tabs + the Options tab; `/dark-pool` redirects

**Goal:** every signed-in user sees `/flow` with an Options tab (live table, filters, date picker,
honest empty/stale states) and a Dark pool tab that is today's page unchanged; the nav has one "Flow"
entry. Stories 1–18, 20–21, 24, 38.

**Blocked by:** Step 2 (the reads it polls).

**Scope (files):** `dashboard/app/flow/layout.tsx`, `app/flow/page.tsx`, `app/flow/dark-pool/page.tsx`,
`app/dark-pool/page.tsx` (→ `permanentRedirect`), `components/Nav.tsx` (one entry swapped),
`components/FlowOrdersTable.tsx`, `components/FlowFilters.tsx`, `lib/flow.ts` + `lib/flow.test.mjs`,
`lib/bff.ts` (`getFlowOrders`, `getFlowSummary`), `app/api/flow/orders/route.ts`,
`app/api/flow/summary/route.ts`. The dark-pool components are imported, not edited.

**Seams:**
- `lib/flow.ts` pure helpers (`ordersQuery`, `fmtPremium`, label/side pills' text, `isPollWindow` for
  09:25–16:20, retention bound on the date picker) via `node --test` — catches query building and
  formatting; misses rendering.
- `app/api/flow/*` route handlers — catches param forwarding/clamping and the 404 → `{disabled:true}`
  mapping (verified by the rendered check against a stubbed BFF); misses the BFF.
- The built app (`typecheck`, `lint`, `build`) and a rendered check with a stubbed BFF (screenshot) —
  catches type/route errors and the visible states; misses interaction over time.

**Success checklist:**
- [ ] `cd dashboard && npm run typecheck && npm run lint && npm run build && npm run test:lib` all green —
      evidence: the four outputs.
- [ ] `lib/flow.test.mjs` covers: `ordersQuery` emits only known params and clamps `min_premium` to
      ≥ 25000 and `limit` to ≤ 500; the default query carries `min_premium=100000`; `isPollWindow` is
      true at 09:26 ET and false at 16:21 ET on a weekday and false on Saturday, with `TZ=UTC` pinned;
      `fmtPremium(1234567) === "$1.23M"`; the date picker's minimum is today − 90 days — evidence: test
      names.
- [ ] Rendered check (stubbed BFF): with rows → a table of ticker, contract (expiry/strike/right/DTE),
      side pill with a "measured"/"no quote" marker, premium, size, labels (sweep/block/multi-leg/
      auction/cross/unusual), moneyness; with `{disabled:true}` → copy naming `FLOW_ENABLED`; with an
      empty page inside the window → copy naming `flow-collector` and `FLOW_DRY_RUN`; a 502 keeps the
      last frame with a stale note — evidence: four screenshots in the PR.
- [ ] `curl -sI http://localhost:3000/dark-pool` → `308` with `location: /flow/dark-pool` (signed-in
      session cookie) — evidence: the headers.
- [ ] `/flow/dark-pool` renders `DarkpoolSummaryTable` and `DarkpoolPrintsFeed` with no change to those
      components (`git diff --stat` shows no `Darkpool*.tsx` edits) — evidence: the diff stat.
- [ ] `Nav.LINKS` has `/flow` and no `/dark-pool`; the mobile primary four are unchanged — evidence: the
      diff of `Nav.tsx` is one line swapped.
- [ ] Polling runs every 5 s only inside 09:25–16:20 ET and only on a visible tab (reuses
      `useDarkpoolRead`'s visibility rule) — evidence: the component's constants + the hook import.

**Failure checklist (STOP and report):**
- [ ] The Options tab needs a new BFF endpoint or a BFF change beyond Step 2 (then the gap belongs in a
      Step 2 follow-up, not in this PR).
- [ ] `useDarkpoolRead` must be modified to serve the flow proxies (copy a `useFlowRead` instead and
      say why — but first report).
- [ ] `middleware.ts` or `auth.config.ts` needs to change (access must stay the default sign-in gate).
- [ ] A `Darkpool*.tsx` component must change to move the page.
- [ ] Any new `localStorage`/client state that survives reloads beyond the active tab.

### Step 4 — The combined per-ticker view

**Goal:** from either tab a ticker opens `/flow/ticker/<SYMBOL>`: today's options flow for the name
(call vs put premium, bought vs sold premium, largest orders) beside its dark-pool levels and large
prints; names without dark-pool data show options only with a note. Story 19.

**Blocked by:** Step 3.

**Scope (files):** `dashboard/app/flow/ticker/[symbol]/page.tsx`, `components/FlowTickerView.tsx`,
`lib/flow.ts` (+ tests), `app/api/flow/orders/route.ts` (forward `sort`), BFF `FlowController` +
`FlowRepository` (`sort=premium_desc` on `/api/flow/orders`, with a WebMvc + IT case), one `Link` on
the ticker cell of `FlowOrdersTable.tsx` and of `DarkpoolSummaryTable.tsx` (the only dark-pool edit in
this plan: a wrapper element around the symbol text).

**Seams:**
- `GET /api/flow/orders?ticker=&sort=premium_desc` (MockMvc + IT) — catches the sort and its validation;
  misses nothing else new.
- `lib/flow.ts` `tickerSummaryState(summaryRow, darkpoolSummary, symbol)` → `{options, darkpool |
  "none"}` — catches the "outside the universe / no prints yet" rule; misses rendering.
- Built app + rendered check with stubs for a covered name (AAPL) and an uncovered one.

**Success checklist:**
- [ ] BFF: `sort=premium_desc` returns the fixture's orders in premium order; an unknown `sort` → 400 —
      evidence: test names, `spotless:apply` + `verify` green.
- [ ] `lib/flow.test.mjs`: the symbol-path validator rejects `../` and lowercases nothing silently
      (uppercases); `tickerSummaryState` yields `"none"` when the symbol is absent from the dark-pool
      summary — evidence: test names.
- [ ] Rendered check: AAPL shows the four premium figures (call, put, bought, sold) with the measured
      share, the top-20 orders by premium, the dark-pool levels strip and large prints ≥ $200k; an
      uncovered symbol shows the options panel and the exact note "No off-exchange data for X today:
      outside the dark-pool universe, or no prints yet" — evidence: two screenshots.
- [ ] Ticker cells on both tabs link to `/flow/ticker/<SYMBOL>`; `git diff` of
      `DarkpoolSummaryTable.tsx` is the link wrapper only — evidence: the diff.
- [ ] `npm run typecheck && npm run lint && npm run build && npm run test:lib` green — evidence: output.

**Failure checklist (STOP and report):**
- [ ] The view needs a universe list in the dashboard or BFF (the plan's rule is data-driven: absent
      from the dark-pool summary = note).
- [ ] The view needs a new BFF aggregate beyond `sort` (then it belongs in a BFF follow-up first).
- [ ] Any edit to `DarkpoolLevels.tsx`/`DarkpoolPrintsFeed.tsx` beyond importing them.

## Repo gates

- **Java:** `mvn -pl services/tenant-dashboard-bff -am spotless:apply` before every commit touching the
  BFF; CI runs `verify` with `RUN_DB_ITS=true`.
- **PG16:** the writer role needs `SELECT` on every `ON CONFLICT` arbiter column and on the retention
  `DELETE`'s `WHERE` column; grant it in V16 (V15 documents the rule).
- **Migration structure test:** `DashboardMigrationSqlStructureTest` must gain a V16 block; the
  Flyway version is V16 (V1–V15 exist).
- **New Deployment:** `deploy.yml` cannot create one; the first `kubectl -n copytrade apply -f
  infra/k8s/67-flow-collector.yaml` is manual, and until then the bootstrap guard must skip
  `flow-collector` with a notice — the guard currently special-cases `darkpool-collector` only, so Step 1
  edits `.github/workflows/deploy.yml` (own commit; the PR cannot run its own Claude review → NO_CHECK,
  plain CI is the gate) and `scripts/tests/test_deploy_matrix_routing.py`.
- **Sidecar image coupling:** merging anything under `services/signal-source-discord/` (and
  `contract/python/`) rebuilds the one image and rolls the live signal watcher, the mirrors, lotto-signal,
  darkpool-collector and flow-collector. Roll only while the market is closed; a mid-session restart of
  `flow-collector` is a permanent tape gap.
- **Deploy agent:** `flow-collector` must be `"signal-watcher"` (market-closed only) in
  `scripts/ops/pr-deploy-agent/deploy_gate.py` `SERVICE_CLASS`, its README table and
  `tests/test_deploy_gate.py` — on `feat/pr-deploy-agent` (unmerged); whichever branch merges second
  carries the entry.
- **BFF live env is operator-patched** (`58-tenant-dashboard-bff.yaml` is drifted from live and the BFF
  is `RESTART_ONLY`): `FLOW_ENABLED=true` is a `kubectl set env`, never a manifest edit.
- **Secrets:** no new Secret; `alpaca-credentials-data` (read-only data keys), `dashboard-secrets`
  (`OPTIONS_CHAT_INGEST_TOKEN`), `discord-alert-credentials` (`ALERT_DISCORD_WEBHOOK_URL`) already exist.
- **REST budget:** 10k/min shared with market-data's fail-closed kill-switch MTM path and the dark-pool
  collector; the flow collector caps itself at 300/min and honours the `X-Ratelimit-Remaining` floor.
- **Schema regeneration / Temporal:** not applicable — no `contract/schemas` change, no workflow code.
- **Commit trailer:** `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01DHs1FCuPZMK4KCuUkyDAn4`.

## Open questions

1. **Tick-rule side fallback.** The PRD lists `side_source ∈ {quote, tick, none}`; a tick rule needs the
   previous trade price for every contract on the tape (~100 MB against the 300 MB cap). Assumption:
   ship `{quote, none}`; revisit if the Step 1 report shows ≥ 120 MB of headroom.
2. **NYSE holidays.** The collector connects on any weekday and simply sees an empty tape on a holiday;
   the missed-session cron would false-alert. Assumption: the operator cron carries a holiday list (as
   the deploy agent's `market_hours.py` does), the collector does not.
3. **Alert channel.** Assumption: collector-health alerts go to the global `ALERT_DISCORD_WEBHOOK_URL`
   (`discord-alert-credentials`), not a per-tenant webhook.
4. **Step 1 universe.** The day's watchlist needs a BFF route that does not exist until Step 2.
   Assumption: Step 1 quotes the static `darkpool-universe` ConfigMap (57 names + SPY/QQQ); Step 2
   unions today's `watchlist_daily` tickers.
5. **Dry-run memory limit.** Assumption: 400 Mi for the one measurement session (request 256 Mi) so the
   peak is observed rather than OOM-killed; Step 2 sets 300 Mi. If the operator prefers to measure under
   300 Mi from the start, the RSS log line still gives the trajectory up to the kill.
6. **Date picker bound.** Assumption: the Options tab's past-date view is bounded to the 90-day order
   retention, like the dark-pool page's 90-day prints bound.
7. **Reference data source.** Assumption: open interest, day volume and the underlying price come from
   capped REST lookups at order time, not from the homelab's `option_oi_collector.py` files, which the
   pod cannot read.
8. **Deploy-agent branch ordering.** Which merges first, Step 1 or `feat/pr-deploy-agent`? Assumption:
   the second to merge adds the `flow-collector` class entry; the plan names it in both places.
