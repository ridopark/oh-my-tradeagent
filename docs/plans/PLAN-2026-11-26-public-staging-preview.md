# PLAN 2026-11-26 — Public, login-free demo of staging_paper

Status: PROPOSED, grilled 2026-11-26 — nothing built, nothing changed in production.

## Goal

Anyone opening `https://tradeagent-demo.ridopark.com` sees a live, read-only view of the
`staging_paper` tenant that looks like the real dashboard, with no login.
`https://tradeagent.ridopark.com` is not changed: same single Cloudflare Access rule, same Google
login, no public exceptions.

## Decisions (operator, 2026-11-26)

| Topic | Decision |
|---|---|
| Audience | Link-only showcase for prospective tenants. No sign-up, no contact form, no link to the real dashboard |
| Address | Separate host `tradeagent-demo.ridopark.com` |
| Priority | Maximum security |
| Look | Same as the dashboard: built from the dashboard's own components and Tailwind styling |
| Numbers | All staging_paper numbers public; broker order ids shown |
| Hidden | Alpaca account number and credential status; signal authors and text; all internal ids; webhook; strategy config; free-text fields; options-chat; dark-pool; flow; watchlist ideas |
| Entry delay | A position is hidden until 15 min after its first entry (see rule below). Everything else is live |
| Strategies | `copytrade-v1`, `copytrade-lotto-v1`, `watchlist-trigger-v1`, each row labelled. `gated_condor` not shown; a note says the account also runs a spread experiment |
| Canary | Accepted that staging incidents are visible; covered by a "paper account, test environment" banner |
| Disclaimer | Fixed footer: paper trading, simulated fills, not investment advice, past results do not predict future ones. Operator to get wording checked if tenants are ever charged |
| Search | `noindex` + robots disallow |
| History | Fills and orders: last 30 days. Equity curve: full |
| Fills | Section is "Fills" (entries and exits as they happened), not paired round trips |
| Pages | Step 1: status, portfolio, positions, fills, orders. Step 2: read-only `/live`. Landing page `/status`, then `/live` |
| Refresh | Position prices + unrealized P&L 3 s; fills and orders 5 s; equity, cash, chart 30 s in market hours and 5 min outside. Each section shows "updated N s ago" |
| Outage | No paging. Page shows "stale"; runbook has a one-command off switch |
| Merges | PRs touching market-data or the BFF merge only outside market hours. Dashboard-only and demo-only PRs merge on review approval |
| Memory | Proceed without freeing memory first; demo has hard limits and lowest priority |

### Entry-delay rule (enforced in the BFF, so the public pod never holds undelayed data)

- A position and everything tied to it (entry fill, its orders, any exit) is omitted until 15 min
  after its first entry, then all of it appears at once.
- Adding to an already-visible position shows immediately.
- Account totals (equity, cash, P&L) are live throughout; the position count excludes hidden
  positions.
- Applies to all three shown strategies.

### Accepted differences from the real dashboard

No tenant name, tenant switcher or sign-out; header lists only demo pages; no action buttons
(stop-loss, trim, force exit, manual entry, adopt, kill-switch reset); no account-number column;
no trades "Detail" column; orders "Error" shows only that an order failed; kill-switch panel shows
state and time, no reason; trailing-stop badge kept read-only; no watchlist board on `/live`.

## Design

```
tradeagent-demo.ridopark.com ──> Cloudflare (no Access) ──> tunnel ──> demo pod (static files only)
tradeagent.ridopark.com      ──> Cloudflare Access ──────> tunnel ──> dashboard (same behaviour)

demo pod:  nginx (static export + JSON)  <── shared volume ──  poller ──> BFF /api/preview/*
```

1. **Separation by hostname**, as for `tradeagent-mcp.ridopark.com`. No demo request can be routed
   to the dashboard; no shared cookies.
2. **The demo pod serves files only.** The site is a Next.js static export built from a separate
   small project (`demo/`) that imports `dashboard/components`. Client-side code polls JSON files.
   GET/HEAD only; no app server, no auth code, no server actions, no cookies.
3. **A poller rewrites the JSON on timers**, independent of visitor count.
4. **One credential: a preview token** the BFF binds to `staging_paper` and to GET on
   `/api/preview/*`. No other secret in the pod.
5. **Preview responses are allow-list projections** built in the BFF. Hidden values and the
   entry-delay rule are applied there; nothing downstream redacts.
6. **Row identity:** broker order id for fills and orders, contract symbol for positions. No
   internal id leaves the BFF, not even hashed.

## Facts this design rests on (code read 2026-11-26)

- `/api/portfolio` starts two Temporal workflows per broker target per uncached read
  (`AccountSnapshotWorkflow`, `PositionSnapshotWorkflow`); cached 10 s per tenant
  (`PortfolioCache`). Baseline: ~4,250 workflows per 24 h estate-wide (measured on a market
  holiday). Hence 30 s for equity/cash.
- `/api/live/marks` starts no workflow: an in-memory market-data read per request, plus one
  Visibility list + 2N Temporal queries + one Postgres read per tenant every 15 s (`OpenOccCache`).
  Marks are NBBO bid/mid/ask; the UI uses the bid.
- market-data polls Alpaca every 500 ms per watched contract from a budget of 10 req/s shared by
  all `/live` viewers (`DisplayRequestBudget`), max 25 contracts. A permanent demo watcher could
  exhaust it. Hence the demo's own capped allowance.
- `/api/trades` is raw `audit_log` fill rows (`EntryFilled`, `PartialExitFilled`); `/api/orders` is
  `order_intent_journal`. Both cheap, uncached.
- Hidden values are embedded in `workflow_id`, `correlation_id`, `signal_id`, `intent_key`, the
  trade `subject` JSON, `actor` (operator email on adopted positions), `last_error`, kill-switch
  `reason`, and `account_equity[].account_number`.
- Dashboard: every page fetches its own data and calls `auth()`; `DataTable`, P&L cells, contract
  links and the live-mark components are reusable as-is; `tradeFill`/`tradePnl` sit in a
  server-only module; the existing `app/` tree cannot be statically exported
  (`output: "standalone"`, middleware, route handlers). No render tests exist.

## Phases

Step 1 (public with five pages) = Phases 1–6. Step 2 = Phase 7.

### Phase 1 — BFF preview read class + projections (`services/tenant-dashboard-bff`) — merge outside market hours

- `bff.preview-read-tokens` (`<sha256>:<tenant>`), fail-closed parsing like `bff.mcp-read-tokens`;
  a `PREVIEW_ALLOWLIST` of exact paths; GET/HEAD only; bound tenant enforced.
- `/api/preview/{status,portfolio,positions,fills,orders,marks}` returning allow-list DTOs, with
  the strategy filter, the 30-day window and the entry-delay rule applied.
- Tests, each watched failing first: token confined to preview paths and methods; other token
  classes not interchangeable; sentinel values planted in every hidden field never appear in any
  response; delay rule (hidden before 15 min, all-at-once after, add-on to visible position shown,
  quick open-and-close stays hidden until 15 min); malformed token fails boot.

### Phase 2 — market-data demo allowance (`services/market-data`) — merge outside market hours

- A separate display budget for the demo (about 2 Alpaca req/s) that cannot draw on the operators'
  10 req/s. Test: demo interest saturated → operator budget unaffected.

### Phase 3 — Dashboard refactors (one PR per page, behaviour-neutral)

- Move `tradeFill`/`tradePnl`/`signalOcc` out of the server-only module into a pure one.
- Extract the display markup of status, portfolio, positions, trades, orders into presentational
  components taking data as props; pages keep fetching and pass props. Header markup extracted
  from `Nav`.
- Check per PR: rendered HTML of the page before and after is identical on staging (captured
  with the browser tool), plus `lint`, `typecheck`, `test:lib`.

### Phase 4 — Demo project and pod (`demo/`, `infra/k8s/69-demo.yaml`, build matrix)

- `demo/`: static export importing the Phase 3 components; client wrappers polling `/data/*.json`
  on the agreed cadences; banner, footer, `noindex`, "updated N s ago", "stale" state.
- First task: a test build proving the static export works with the imported components.
- Image: nginx + exported site + poller. nginx: known paths only, `limit_except GET HEAD`, per-IP
  `limit_req` on `CF-Connecting-IP`, CSP `default-src 'self'`, `frame-ancestors 'none'`,
  `nosniff`, read-only filesystem, non-root, no service-account token.
- NetworkPolicy: ingress from `cloudflared` only; egress to BFF :8083 and DNS only; BFF policy
  admits `app: demo`. Memory limits (nginx 32 Mi, poller 128 Mi); priority class below all
  trading pods. Needs one manual `kubectl apply` (deploy.yml does not apply new manifests).

### Phase 5 — Verification before exposure

- Scripted checks: non-GET refused; traversal and encoded paths 404; a full crawl contains no
  account number, whitelist author, webhook host, Discord id, operator email or tenant id.
- Measure added Temporal workflows over one market session: must be under +50% of baseline.
- Measure the operators' `/live` mark freshness with the demo running: unchanged.
- Mint the preview token (hash into `dashboard-secrets`); runbook.

### Phase 6 — Go public (operator present, outside market hours)

Operator, in Cloudflare (the saved token has no DNS or Tunnel permission): add the published
application `tradeagent-demo.ridopark.com` → `http://demo:8080` to tunnel `homelab-dashboard`.
No Access application for it.

Checks from an unauthenticated network: demo pages and `/data/*.json` → 200, anything else 404,
POST 405; `tradeagent.ridopark.com` paths still return the Access 302; Access app list unchanged;
read `cf-cache-status` on the JSON and add a Cache Rule if it is not cached.

Rollback: remove the demo hostname from the tunnel, or scale the demo deployment to zero.

### Phase 7 — Read-only `/live` (step 2)

Extract the `/live` page body, split `AccountGuardBanner` markup from its action, lift the
trailing-stop badge out of `StopLossButton`, make the polling URLs injectable; add the page to the
demo and switch the landing page to it.

## Security review 2026-11-26 (own pass + independent reviewer)

Fixed in phase 1b before merge, each with a test watched failing:

- Watchlist legs share one entry signal id, so a leg bought seconds ago was dated by the morning's
  first leg and skipped the delay (confirmed on staging: 9 of 9 ids in 30 days span several
  contracts). Entry time is now keyed by signal id AND contract, latest `EntryFilled`.
- A position of unknown age was shown; it is now hidden.
- A stale 15 s position cache could make a re-entry look like an add-on; a cached list older than
  3 s is re-read before it may reveal anything.
- Only an order marked exactly `BUY` started the delay; now anything that is not `SELL` does, and
  an undated buy hides the whole window. Rows within 2 min before a buy are hidden (clock skew).
- A position in a strategy the demo does not show could anchor; it no longer can.
- `entry_qty` / `realized_pl` are summed per signal id upstream; dropped from the demo.
- Error bodies echoed some exception messages; preview endpoints now answer one fixed body.
- The quote's nested `underlying` block was copied whole; now named fields only.
- `broker_order_id` is published only when it is a UUID.
- A token holder could pin BFF threads / DB pools: at most 2 preview requests run at once (others
  refused), and the broker-backed portfolio read runs at most once per 30 s.
- A preview token bound to a tenant with any non-paper broker target is refused.

Accepted, by the operator's "totals stay live" decision — these reveal THAT something happened,
never which contract: equity, cash and realized P&L move on hidden entries and include
`gated_condor`; the number of rows in a 500-row read shifts when a hidden order is journalled.

Must be settled before the demo pod is admitted to the BFF's NetworkPolicy (phase 4):

- `/actuator/**` needs no token and shares port 8083. `/actuator/prometheus` exposes per-route
  request counters, so a compromised demo pod could watch operator actions (force-close, manual
  entry) on LIVE tenants in real time. Move actuator to its own management port, or stop exempting
  `/actuator/prometheus` for non-monitoring peers.
- The poller is a second, independent tripwire: it refuses to publish any payload matching
  forbidden patterns (account number, `chat-messages-`, `@`, `discord`, webhook hosts) and never
  writes an error body to disk.
- Pin the demo image by digest; this pod is internet-facing and `:latest` re-pulls on a reboot.
- The page must render through React only (no `innerHTML`): order ids and symbols are data.

Decided 2026-11-26: `trail_stop_price` stays published. The operator accepts that the live
tenants' positions (after 15 min) and stop levels can be inferred from staging's.

## Second security review 2026-11-26 (structured review + adversarial red team, on merged code)

No critical or high finding. The token gate, the allow-list projections, the paper-only check and
the dashboard refactor held against both. Fixed, each with a test watched failing:

- Race: the delay was computed BEFORE the data it filters, so a buy landing during a slow read was
  published once (proved by the red team). Every read now loads data first and orders last.
- `entry_basis` blended every contract sharing a correlation id, so a hidden watchlist leg moved a
  visible row's figure. The demo now computes basis per (signal, contract) from visible fills only.
- The delay ran from the order's journal time. It now runs from the latest activity: fill time, or
  now while the buy is still working (bounded to 24 h so an abandoned row cannot hide a contract
  forever). A buy stamped ahead of the BFF clock is clamped.
- Demo and operator price lanes shared one 4-thread pool; slow Alpaca responses on the demo side
  cut operator refreshes from 25 to 5 in 12 s (proved). Each lane now has its own pool.
- A read-token hash equal to the shared or ingest token's hash, or listed twice, fails boot.
- `/api/preview/status` cached 15 s; failed or refused loads held off 5 s and no longer logged per
  request; an add-on forces a position re-read at most every 5 s; the portfolio read runs at most
  every 5 min outside the regular session; only shown strategies' buys start a delay; a tenant with
  no strategies is refused; a failed entry-fill read degrades to "no basis" rather than a 503.

Closed 2026-11-27: the delay compares the BFF clock with database timestamps. The cluster is one
node, so both read the same kernel clock; three paired readings differed by ~160 ms, all of it the
latency between the two commands. Re-check if the BFF and Postgres ever run on different nodes.

## Phase 4 as built (2026-11-27)

- `demo/`: a Next.js static export (five pages, ~1.1 MB of files) that imports
  `dashboard/components/views/*` and `NavHeader` directly. Pages are thin client wrappers: poll
  `/data/<feed>.json`, adapt, render the dashboard's view. `lib/adapt.ts` supplies neutral values
  for the fields the projections omit. A live quote is laid over a position only when the
  dashboard's own `bidUsability` rule calls it live (polled within 10 s, not warming).
- `demo/poller/`: `poller.mjs` + `check.mjs`, Node with no dependencies. `check` is a second
  allow-list written independently of the BFF's: every key must be on the feed's list, fill
  subjects are re-parsed, strategies must be shown ones, broker targets must end `-paper`, order ids
  must be UUIDs, and no string may contain a Discord id, URL, email, workflow-id shape, account
  number, markup or the hidden strategy's name. A refused or non-200 response is never written.
- `demo/nginx.conf`: five exact data files, GET/HEAD only, per-visitor rate limit, CSP, noindex.
- `infra/k8s/69-demo.yaml`: one pod (web + poller, in-memory volume), lowest priority, no service
  account token, ingress from cloudflared only, egress to the BFF and DNS only. Validated with a
  server-side dry run; NOT applied. The BFF-side grant is commented out until `/actuator/**` is
  closed off.
- Checked against production data: the five real `/api/preview/*` responses for staging_paper
  (4 positions, 133 fills, 186 orders) pass the poller's gate and contain none of the hidden
  values; the site renders them.
- Differences from the plan text: the Fills table keeps no Detail column and is titled "Fills" via
  two optional props on the dashboard's `TradesView`; the header brand still reads "Tenant
  Dashboard".

Still to do for phase 4: add `demo-web` and `demo-poller` to the image build matrix (a workflow
edit, so a separate PR); the Dockerfiles are untested until then (no Docker on the workstation).

## Capacity (estimates, not measured)

Bandwidth is the limit, not the homelab. At roughly 10 KB compressed per refresh cycle a viewer
costs on the order of 27 kbit/s: about 370 concurrent viewers on a 20 Mbit/s uplink with half kept
free for trading. Cloudflare caching of the JSON removes most of that; verified in Phase 6.

## Risks

| Risk | Handling |
|---|---|
| Public mirror of the private group's alerts → source account banned | 15-min entry delay; authors, text and ids never sent |
| A hidden field is added later | Allow-list projections; sentinel test |
| Refactor changes a production page | One page per PR; before/after HTML identical; no behaviour change intended |
| Demo starves operators' live prices | Separate capped market-data allowance; measured in Phase 5 |
| Temporal load on the trading database | 30 s account cadence; measured gate in Phase 5 |
| Preview token leaks | Bound to staging_paper + preview paths; rotate the hash |
| Traffic flood | Static files, per-IP limit, Cloudflare cache |
| Node memory (≈2 GB free of 15) | Hard limits, lowest priority; flagged separately as thin |

## Out of scope

Self-registration and removing the allowlist; any change to prod tenants, the dashboard's Access
rule, or the MCP gateway; paired round-trip trades; validating the Access token in the dashboard.

## Appendix — why not a path on the dashboard host

Tested 2026-11-26 on a throwaway host (temp Access app since deleted): an override with
`path_pattern: "/"` does make only the exact root public. But Access normalizes `//`, `/./`,
`/x/../` and `/?x=1` to the root, the override is not method-scoped (POST `/` passes), matching is
case-insensitive, and `%2f` is not decoded (`/preview/..%2fstatus` passes). A separate hostname
avoids all of it.
