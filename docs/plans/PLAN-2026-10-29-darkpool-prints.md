# PLAN — 2026-10-29 darkpool-prints (native off-exchange print ingest + /dark-pool page)

Operator decision 2026-10-29: own the dark-pool data in this stack rather than reviving the sister project's dormant
TimescaleDB. Scope: the 57-name watchlist universe + SPY/QQQ. Deliverables: a collector that keeps off-exchange
("dark pool") prints with the NBBO at the print, 5-minute aggregates, BFF ingest + read endpoints, and a `/dark-pool`
dashboard page. Read-only on markets; nothing on a trading path. Research context: both this project (0DTE round,
H2a/H2b) and the sister project found NO intraday predictive value in off-exchange flow; the sister project found a
daily-horizon inverted signal (late-session buy-ratio Z). The page is a data product, not a signal; no strategy
consumes it in this plan.

**Verified facts (2026-10-29 17:56 ET, read-only probe through the market-data pod's data keys):**
- `GET https://data.alpaca.markets/v2/stocks/trades?symbols=QQQ&feed=sip` returns real-time SIP trades with
  `x` (exchange; `D` = FINRA ADF/TRF = off-exchange), `c` (conditions; `T` = extended hours, `I` = odd lot),
  `s`, `p`, `t`, `i`, `z`. Rate limit header `X-Ratelimit-Limit: 10000`/min. In 3 after-hours minutes QQQ had 284
  trades, 74 on `D` (15% of volume), 3 ≥ $200k notional.
- Alpaca allows ONE data WebSocket per account on this plan; the market-data pod holds it (equity feed → watchlist
  triggers). The collector therefore POLLS REST, never opens a WebSocket (a second socket can evict the live feed).
- `GET /v2/stocks/quotes?symbols=X&start=..&end=..&limit=1` gives the NBBO just before a timestamp (side inference).
- BFF write path pattern: `POST /internal/options-chat/ingest` behind `ServiceTokenFilter` (ingest token, prefix-gated,
  `services/tenant-dashboard-bff/.../security/ServiceTokenFilter.java:40`), repository `OptionsChatRepository`,
  Flyway migrations under `services/tenant-dashboard-bff/src/main/resources/db/dashboard/V9..V12__*.sql`, structure
  test `DashboardMigrationSqlStructureTest`, IT `OptionsChatMigrationIT`. Read path `OptionsChatController` (`GET
  /internal/options-chat/messages` for the sidecar; tenant-session reads for the dashboard). Dashboard proxy:
  `dashboard/app/api/options-chat/messages/route.ts` (server-side fetch to the BFF with the shared token).
- Sidecar deployment pattern: `infra/k8s/65-lotto-signal.yaml` (same image as signal-source-discord, env from
  secrets `API_GATEWAY_SHARED_TOKEN`, `OPTIONS_CHAT_INGEST_TOKEN`, `BFF_INTERNAL_URL`), deploy.yml wiring at
  `.github/workflows/deploy.yml:111,280,299,341` (first create of a NEW Deployment is a MANUAL `kubectl apply`).
  Module pattern: `services/signal-source-discord/ohmytradeagent_sidecar/lotto_main.py` (poll loop, heartbeat,
  per-row guards, dry-run env, `tests/test_lotto_main.py`).

## P0 — Operator (no code)
1. Add the data keys to the collector's env: a `darkpool-collector` Secret in `copytrade` with
   `APCA_API_KEY_ID_DATA`/`APCA_API_SECRET_KEY_DATA` copied from the market-data pod's secret (read-only data keys).
2. First `kubectl apply -f infra/k8s/66-darkpool-collector.yaml` by hand (deploy.yml cannot bootstrap a Deployment).
3. Flip `DARKPOOL_DRY_RUN=false` on the Deployment (and in the manifest) after one dry-run session shows sane counts.

## Phase 1 — BFF: schema, ingest, read (services/tenant-dashboard-bff) — java-architect
**Goal:** the dashboard DB can store prints and 5-minute aggregates; a token-gated POST stores them idempotently; two
GETs serve the page. Dark: gated on `darkpool.enabled` (default false in `application.yml`), like `options-chat.enabled`.
**Changes (anchors):**
- `db/dashboard/V13__darkpool.sql` — tables:
  `darkpool_print(id bigserial pk, trade_id bigint, symbol text, ts timestamptz, price numeric(12,4), size int,
  notional numeric(16,2), exchange char(1), conditions text, tape char(1), bid numeric(12,4), ask numeric(12,4),
  quote_ts timestamptz, side text check (side in ('buy','sell','mid','unknown')), ingested_at timestamptz default now(),
  unique(symbol, ts, trade_id))`; index `(symbol, ts desc)`, `(ts desc)`.
  `darkpool_bar_5m(symbol text, bucket timestamptz, dp_volume bigint, dp_trades int, total_volume bigint,
  total_trades int, buy_volume bigint, sell_volume bigint, large_count int, large_volume bigint, dp_vwap numeric(12,4),
  primary key(symbol,bucket))`. Retention: a `darkpool_print` delete-older-than-90-days job mirroring
  `OptionsChatRetention` (prints) and 400 days for bars.
- `web/DarkpoolIngestController.java` — `POST /internal/darkpool/ingest` body `{prints:[...], bars:[...]}` →
  `DarkpoolRepository.ingest` (`INSERT … ON CONFLICT DO NOTHING` for prints, `ON CONFLICT (symbol,bucket) DO UPDATE`
  for bars). Register `/internal/darkpool/` as a SECOND ingest-token prefix in `ServiceTokenFilter` (same token,
  same traversal guard — extend the existing `OPTIONS_CHAT_INGEST_PREFIX` check into a small prefix list; keep the
  `%2e%2e`/`..;` guard behaviour byte-identical).
- `web/DarkpoolController.java` (tenant-session read, like `OptionsChatController`): `GET /api/darkpool/prints?symbol=&since=&min_notional=&limit=`
  (default 200, max 1000, newest first) and `GET /api/darkpool/summary?date=` → per symbol for that session:
  dp_volume, total_volume, dp_share, buy_volume, sell_volume, buy_ratio, late_buy_ratio (14:00–15:30 ET buckets),
  large_count, large_volume, top 3 price levels by large-print notional (price rounded to 0.1% buckets).
**Tests (TDD):** `DashboardMigrationSqlStructureTest` picks up V13 (structure + idempotent unique keys);
`DarkpoolMigrationIT` (RUN_DB_ITS): insert duplicate print → 1 row; bar upsert updates; summary SQL on a fixture
session returns dp_share/buy_ratio/late_buy_ratio/levels as computed by hand; `DarkpoolIngestControllerTest`
(MockMvc): no token → 401, wrong prefix traversal `/internal/darkpool/%2e%2e/api/positions` → 401/404, valid batch →
stored count; `ServiceTokenFilterTest` extended for the second prefix.
**Verify:** `mvn -pl services/tenant-dashboard-bff -am spotless:apply && mvn -pl services/tenant-dashboard-bff -am
verify`; ITs run in CI (RUN_DB_ITS). PG16 note: writer role needs SELECT on conflict-target columns (grant in V13 as
V9 did for options_chat). Constraint list: spotless ✓, new audit kinds none, no contract schema change, no tenant YAML.

## Phase 2 — Collector module + manifest (services/signal-source-discord, infra/k8s) — general-purpose (Python TDD)
**Goal:** a REST-polling collector keeps off-exchange prints for the universe, stamps each kept print with the NBBO at
the print, builds 5-minute aggregates, and posts both to the BFF; dry-run by default.
**Changes (anchors):**
- `ohmytradeagent_sidecar/darkpool_collector.py` (pure functions, unit-tested): `classify(trade)` → off-exchange iff
  `x == 'D'`; `is_large(trade, min_notional)`; `side(price, bid, ask)` → buy if price ≥ ask−tick*0, sell if ≤ bid,
  `mid` within, `unknown` if no quote within 5 s before the print; `bucket5m(ts)`; `aggregate(trades, kept)` →
  bars; `Cursor` per symbol (last trade `t`+`i` seen, resumable).
- `ohmytradeagent_sidecar/darkpool_main.py` (loop, mirrors `lotto_main.py`): every `DARKPOOL_POLL_SECS` (default 60)
  during 04:00–20:00 ET, for each symbol in `DARKPOOL_UNIVERSE` (file or env; default = the 57 names + SPY/QQQ):
  `GET /v2/stocks/trades` from the cursor (pagination), keep `x=='D'` prints with `notional ≥ DARKPOOL_MIN_NOTIONAL`
  (default $200,000) OR `size ≥ DARKPOOL_MIN_SIZE` (default 10,000), fetch the NBBO before each kept print
  (`/v2/stocks/quotes` limit 1, end = print ts), aggregate ALL trades of the symbol into 5-minute bars (dp and total
  volume/trades, buy/sell by side vs the bar's running dp VWAP for the un-quoted small prints, large counts), then
  `POST {BFF_INTERNAL_URL}/internal/darkpool/ingest` with the ingest token. Budget: ≤ 59 symbols × (1 + pages) + kept
  prints per minute ≪ 10,000/min; log the request count per cycle. Heartbeat file + per-symbol error isolation (one
  bad symbol never stops the others). `DARKPOOL_DRY_RUN` (default true) logs the batch instead of posting.
- `infra/k8s/66-darkpool-collector.yaml` — Deployment on the sidecar image, env: `DARKPOOL_DRY_RUN=true`,
  `DARKPOOL_POLL_SECS=60`, `DARKPOOL_MIN_NOTIONAL=200000`, `DARKPOOL_MIN_SIZE=10000`, `DARKPOOL_UNIVERSE_FILE` via a
  ConfigMap built from `scripts/watchlist-universe.txt` + SPY/QQQ, `BFF_INTERNAL_URL`, `OPTIONS_CHAT_INGEST_TOKEN`
  (secret), `APCA_API_KEY_ID_DATA`/`APCA_API_SECRET_KEY_DATA` from the new `darkpool-collector` Secret; resources
  128Mi/256Mi, liveness via the heartbeat mtime. Header comment: FIRST create is manual; NEVER opens a WebSocket.
- `.github/workflows/deploy.yml` — add `darkpool-collector` at the four lotto-signal sites (dispatch option, SERVICES,
  sidecar roll set, manifest→service map). `scripts/tests/test_deploy_matrix_routing.py` SIDECAR set += darkpool-collector.
**Tests (TDD):** `tests/test_darkpool_collector.py`: classify/is_large/side (ask, bid, mid, no quote), bucket
boundaries at ET DST, aggregate on a fixture of 40 trades with 6 `D` prints (hand-computed bars), cursor resume
excludes already-seen `(t,i)`, pagination; `tests/test_darkpool_main.py`: dry-run posts nothing, a 500 from one
symbol does not block the next, request budget under 59 symbols × 2 pages, heartbeat written per cycle.
**Verify:** `cd services/signal-source-discord && uv run ruff check . && uv run pytest -q`; `python3 -m unittest
scripts/tests/test_deploy_matrix_routing.py`. Merging rolls the LIVE signal watcher (shared image) — merge outside RTH.
deploy.yml edit → the PR cannot run its own Claude review (NO_CHECK; plain CI is the gate).

## Phase 3 — Dashboard /dark-pool page (dashboard/) — dashboard-dev
**Goal:** operators see today's off-exchange activity for the universe and a prints feed, read-only.
**Changes (anchors):**
- `dashboard/app/api/darkpool/prints/route.ts`, `dashboard/app/api/darkpool/summary/route.ts` — server-side proxies
  to the BFF `/api/darkpool/*` (pattern: `dashboard/app/api/options-chat/messages/route.ts`).
- `dashboard/app/dark-pool/page.tsx` + `dashboard/components/DarkpoolSummaryTable.tsx`, `DarkpoolPrintsFeed.tsx`,
  `DarkpoolLevels.tsx`: (1) session summary table sortable by dp_share / buy_ratio / late_buy_ratio / large_count with
  a date picker; (2) prints feed (time, symbol, size, notional, price, side pill, NBBO at print) with symbol and
  min-notional filters, 30-s polling during RTH; (3) per-symbol levels strip (top 3 large-print price clusters) with a
  link to the existing `/live` chart for that symbol. Nav entry next to `/options-chat`. Empty state names the
  collector and its dry-run flag.
- `dashboard/lib/darkpool.ts` — pure helpers (level bucketing, formatting) for review.
**Verify:** `cd dashboard && npm run typecheck && npm run lint && npm run build`; rendered check against a BFF stub
(dashboard has no JS test runner). A screenshot in the PR.

## Phase 4 — Operator bring-up + dead-man (no code beyond a cron line)
1. Create the Secret (P0-1), `kubectl apply` 66-*.yaml (P0-2), watch one session in dry-run: expect per-cycle logs like
   `59 symbols, N requests, K kept prints, B bars`; confirm K and the `D` volume share look like the probe (10–30%).
2. Flip `DARKPOOL_DRY_RUN=false` + manifest edit (P0-3); confirm rows via `GET /api/darkpool/summary` and the page.
3. Add a line to `~/scripts/lotto_invariant_check.sh`'s sibling pattern: a `darkpool_check.sh` at 16:45 CT that
   alerts (staging_paper webhook) when today's `darkpool_bar_5m` has < 50 buckets for SPY, deduped per day.

## Ship order & gating
Phase 1 (dark, BFF only) → Phase 2 (dark-run collector; manifest first-apply manual; outside RTH) → Phase 3 (page shows
the empty state until data flows) → Phase 4 (operator). Each: TDD, own PR from a fresh branch off `origin/main`,
`/pr-review-loop` (merges on READY; Phase 2's deploy.yml touch means NO_CHECK → green CI merges). Commit trailer:
```
Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DHs1FCuPZMK4KCuUkyDAn4
```
Design notes recorded for the implementers: off-exchange ≠ dark pool strictly (TRF also carries internalizer prints);
label it "off-exchange" in the schema and UI copy, "dark pool" in the page title only. Side inference uses the NBBO
quote ≤ 5 s before the print; prints without a quote are `unknown`, never guessed from VWAP (the sister project's
running-VWAP inference is what we are improving on).
