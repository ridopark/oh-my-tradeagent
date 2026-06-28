# PLAN — 2026-06-28 live-account-view

**Feature, not a remediation.** Add a Robinhood-style account view to the dashboard `/live` page: a big account-total header with a range-aware `+$X (Y%)` change, an equity line chart with range tabs (`1D · 1W · 1M · 3M · YTD · 1Y`), the open-holdings list, and a recent-activity strip. The one net-new data dependency is Alpaca's **Portfolio History API** (`GET /v2/account/portfolio/history`), proxied through the existing AccountSnapshot-style path (broker → exec activity → orchestrator workflow → BFF endpoint → server-only `lib/bff.ts` → Next route handler → client chart). Everything else (total equity, holdings, trades/orders) is reuse.

This plan is PLAN ONLY — nothing is implemented. Anchors below were verified against the working tree on 2026-06-28.

## Confirmed data source (the key unlock)

Alpaca Trading API `GET /v2/account/portfolio/history` — account-scoped, same auth `AlpacaPaperBroker` already uses for `/v2/account`, `/v2/orders`, `/v2/positions` (`AlpacaPaperBroker.client` `RestClient`, `AlpacaPaperBroker.java:115,123`).
- **Params:** `period` = `<N>{D|W|M|A}` (no native `YTD`/`Y` — units are only D/W/M/A=year), `timeframe` = `1Min|5Min|15Min|1H|1D`, `date_end`, `extended_hours`, newer-engine `intraday_reporting`/`pnl_reset`.
- **Response:** parallel arrays indexed by `timestamp[]` (epoch seconds): `equity[]` (chart line), `profit_loss[]`, `profit_loss_pct[]`; scalars `base_value` (dashed baseline / range start), `base_value_asof`, `timeframe`.
- **One call yields BOTH the chart line AND the range-aware headline** in RH semantics: `equity`=line, `base_value`=baseline, `profit_loss`/`profit_loss_pct`=headline `+$X (Y%)`.
- **Range → period mapping (server-side, in the broker/exec layer):** 1D→`1D`+`5Min`; 1W→`1W`+`15Min`; 1M→`1M`+`1D`; 3M→`3M`+`1D`; 1Y→`1A`+`1D`; **YTD = one-line date calc** — `period=<days-since-Jan-1>D` (or `date_end`+computed start), `timeframe=1D`. Timeframe auto-rule: `1Min`/`5Min` <7d, `15Min` <30d, else `1D`.

## Rejected alternative (recorded)

**Accumulate our own equity snapshots into a new Postgres table** (cron the existing `AccountSnapshotWorkflow`, store a time series). Rejected: it needs a new table + a writer datasource + a scheduler, and has **no backfill** — the chart would start empty and fill forward. Alpaca returns fully backfilled history directly with no storage. This feature is therefore a **live proxy read, NO DB schema change**.

## Locked scope facts (bake into every phase)

- **READ-ONLY / no money path.** A GET of account history places no orders, touches no order path. Safe even on `prod_real` (real acct 847309116, intentionally 403-blocked). This feature MUST NOT lift the 403 and does not depend on it being lifted — a 403 only blocks orders, not account reads.
- **Account-level / shared scope.** `equity[]`, `base_value`, and the total are per **Alpaca account**, shared by every tenant on the same `broker_target` — NOT a per-tenant portfolio value. This is the same scope caveat as `account_equity_scope` (`PortfolioService` Javadoc; `lib/bff.ts:111`) and `AccountSnapshotActivity` (contract Javadoc). It is exactly the shared-demo showcase the self-registration epic fronts (see cross-ref). Every surfaced number carries an explicit "account-level (shared)" label, mirroring `status/page.tsx:96` ("Net-liq equity (account-level, shared).").
- **Tenant scoping is unchanged.** The `/live` page is tenant-scoped; the new endpoint resolves `broker_target` from the tenant exactly as `getPortfolio()` does (`X-Tenant-Id` injected server-side by `lib/bff.ts:28-48`; client components NEVER call the BFF directly — they hit a Next route handler). The chart shows whichever account the tenant maps to.
- **Replay safety.** `PortfolioHistoryWorkflow` is brand-new (no existing history to replay) → **no `Workflow.getVersion` gate needed for the new workflow itself**. No phase here modifies an existing workflow's command shape; if one ends up doing so, gate it. (Flagged per-phase below — none currently require it.)
- **Spotless per module.** `exec`, `orchestrator`, `tenant-dashboard-bff`, `contract` are the Java modules touched. Run `mvn -pl <module> -am spotless:apply` on EVERY edited module before commit or CI fails. Changing the `OptionsBroker` interface (a `default` method addition is source-compatible) does not break orchestrator ITs, but run the exec + orchestrator module tests to be sure (cross-module exec-ctor trap).
- **Deploy matrix.** `exec-alpaca-paper`, `tenant-dashboard-bff`, `dashboard` are in `deploy.yml` SERVICES → auto-deploy on their own code changes. **`exec-alpaca-live` is NOT in the matrix** → the new broker method reaches paper automatically but must be **manually rolled** to live for `prod_real` to get the chart: `kubectl rollout restart deployment/exec-alpaca-live -n copytrade`. Until then the `/live` chart on `prod_real` degrades to "unavailable" (the broker method is missing on that pod). Operator follow-up, not a code phase.
- **`KillSwitchWorkflowImplTest` is flaky** — re-run, don't fix, if it trips in an orchestrator run.
- **PR mechanics.** `gh pr edit --body` is broken here → set the body at create time or `gh api -X PATCH repos/<owner>/<repo>/pulls/<n>`. Commit trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`. No issue exists yet → no `Closes #`. Never touch `.github/workflows/*.yml`.

---

## P0 — Operator follow-ups (no code)

Run these AFTER the corresponding code phase merges and the paper service auto-deploys.

1. **Roll the new broker method to live exec.** After P1 merges + `exec-alpaca-paper` auto-deploys, manually roll live so `prod_real` gets the chart:
   `kubectl rollout restart deployment/exec-alpaca-live -n copytrade` (verify the pod comes up healthy). Until done, `prod_real`'s `/live` chart shows "unavailable" — expected, not a bug.
2. **No `40-tenants-config.yaml` change** — this feature touches no `tenants/dev/*` YAML, so the ConfigMap drift guard is not in play. (Recorded so nobody adds a spurious config knob.)
3. **No tenant-YAML change** — nothing for `staging_paper`/`prod_real` (live-cluster-only) here.

---

## Phase 1 — Backend portfolio-history proxy (contract → exec → orchestrator → BFF → lib/bff.ts + route handler)

**Goal:** one tenant-scoped endpoint `GET /api/portfolio-history?range=1D|1W|1M|3M|YTD|1Y` returns `{ timestamps[], equity[], profit_loss[], profit_loss_pct[], base_value, base_value_asof, timeframe, account_scope }`, end-to-end against `staging_paper`. This is the whole backend; ship it before any UI so the UI phases have a real endpoint to render.

**Changes (anchors):**

1. **Contract schemas (module `contract`).** Mirror `contract/schemas/account-snapshot-request.json` / `account-snapshot-result.json`:
   - New `contract/schemas/portfolio-history-request.json`: `schema_version`, `broker_target` (enum, same as `account-snapshot-request.json`), `period` (string, e.g. `1M`), `timeframe` (string), `correlation_id`. Resolve `range`→`period`/`timeframe` (incl. YTD date calc) BEFORE building the request, in the BFF client (keep the workflow/activity dumb and replay-stable — pass already-resolved `period`/`timeframe`, never the word "YTD" or a clock read into the workflow).
   - New `contract/schemas/portfolio-history-result.json`: arrays `timestamps` (integer epoch-seconds), `equity` (number), `profit_loss` (number), `profit_loss_pct` (number); scalars `base_value` (number), `base_value_asof` (integer, optional), `timeframe` (string). `required`: `schema_version`, `timestamps`, `equity`. Optional fields out of `required` (null = unavailable), per the schema-change rule.
   - The build regenerates the Java POJOs (`PortfolioHistoryRequest`/`PortfolioHistoryResult` under `com.ohmytradeagent.contract`). No Python pydantic consumer for these (account-read only), but the round-trip drift check runs anyway — keep both schemas self-consistent.

2. **Broker port + Alpaca impl (module `exec`).**
   - `services/exec/src/main/java/com/ohmytradeagent/exec/broker/OptionsBroker.java` — add a `default` method (source-compatible, like `getAccount`/`tradingDays`):
     `default PortfolioHistory getPortfolioHistory(String period, String timeframe, String dateEnd) { throw new UnsupportedOperationException(...); }` plus a `record PortfolioHistory(long[] timestamps, BigDecimal[] equity, BigDecimal[] profitLoss, BigDecimal[] profitLossPct, BigDecimal baseValue, Long baseValueAsof, String timeframe) {}`. A `default` (not abstract) addition means `StubBroker` and other adapters compile untouched — verify `StubBroker` still builds.
   - `services/exec/src/main/java/com/ohmytradeagent/exec/broker/alpaca/AlpacaPaperBroker.java` — override `getPortfolioHistory`, reusing the shared `RestClient client` (`AlpacaPaperBroker.java:115`) and the `mapError(HttpStatusCodeException)` translation already used by `tradingDays` (`AlpacaPaperBroker.java:597-598`). New `AlpacaPortfolioHistoryResponse` record (parallel arrays + scalars) deserialized like `AlpacaAccountResponse`/`AlpacaCalendarDay`. `GET /v2/account/portfolio/history` with `period`/`timeframe`/`date_end` query params (mirror the `tradingDays` `uriBuilder` block at `AlpacaPaperBroker.java:588-596`).

3. **Activity contract + impl.**
   - `contract/java/src/main/java/com/ohmytradeagent/contract/activities/PortfolioHistoryActivity.java` — mirror `AccountSnapshotActivity.java` exactly: `@ActivityInterface`, one method `PortfolioHistoryResult portfolioHistory(PortfolioHistoryRequest request)`.
   - `services/exec/.../activities/PortfolioHistoryExecActivityImpl.java` — mirror `AccountSnapshotExecActivityImpl.java`: resolve the broker via `BrokerClientRegistry` keyed on `tenant_id` (null/blank → `ACCOUNT_LEVEL`, same fallback as `AccountSnapshotExecActivityImpl.java:39-44`), call `broker.getPortfolioHistory(...)`, map the `PortfolioHistory` record onto `PortfolioHistoryResult`.
   - Register the impl on the exec worker: `services/exec/src/main/java/com/ohmytradeagent/exec/config/TemporalWorkerConfig.java:56,59-60` — add a `PortfolioHistoryActivity portfolioHistory` constructor param and append it to `registerActivitiesImplementations(...)`.

4. **Orchestrator workflow.**
   - `services/orchestrator/.../workflows/PortfolioHistoryWorkflow.java` + `PortfolioHistoryWorkflowImpl.java` — mirror `AccountSnapshotWorkflow`/`AccountSnapshotWorkflowImpl.java:19-36` verbatim: dispatch to `broker-<target>` via `ExecActivitiesFactory.taskQueueFor(brokerTarget)`, same activity-stub options (15s start-to-close, 60s schedule-to-close, 3 attempts). **Brand-new workflow type → no `getVersion` gate.**
   - Register it: `services/orchestrator/.../config/TemporalWorkerConfig.java:111-150` — add `PortfolioHistoryWorkflowImpl.class` to `registerWorkflowImplementationTypes(...)` (alongside `AccountSnapshotWorkflowImpl.class` at line 123). The activity impl lives on the exec worker, not here — do NOT register the activity in the orchestrator.

5. **BFF client + service + controller.**
   - `services/tenant-dashboard-bff/.../portfolio/PortfolioHistoryClient.java` — mirror `AccountEquityClient.java`: synchronous start-and-`getResult` against `WORKFLOW_TYPE = "PortfolioHistoryWorkflow"` on `orchestratorTaskQueue`, bounded `RESULT_TIMEOUT_SECONDS` (use 8s like `AccountEquityClient.java:39`), cancel-the-orphan-on-timeout (`AccountEquityClient.java:90-104`), degrade to a null/empty result on any error. **This client owns the `range`→`period`/`timeframe` resolution** (incl. the YTD `days-since-Jan-1` calc) so the workflow stays a dumb, deterministic pass-through.
   - `services/tenant-dashboard-bff/.../web/PortfolioHistoryController.java` — mirror `PortfolioController.java`: `@RestController @RequestMapping("/api/portfolio-history")`, `@GetMapping` reads `@RequestParam(defaultValue="1M") String range` + `ctx.tenantId(req)`, returns the history JSON with an `account_scope` label string (reuse the wording from `PortfolioService`'s `account_equity_scope`). Resolve the tenant's `broker_target` the same way `PortfolioService` does (it already aggregates per-tenant broker targets — reuse that resolution, do not reinvent it). For a tenant with multiple broker targets, return the primary/first (document this; the total header is single-account RH semantics).

6. **Dashboard server hop.**
   - `dashboard/lib/bff.ts` — add interface `PortfolioHistory` (the result shape above) + `export const getPortfolioHistory = (range: string) => bffGet<PortfolioHistory>(\`/api/portfolio-history?range=${range}\`);` (mirror `getPortfolio` at `lib/bff.ts:198`). Server-only; `X-Tenant-Id` injected automatically by `bffGet` (`lib/bff.ts:28-48`).
   - `dashboard/app/api/portfolio-history/route.ts` — new route handler mirroring `app/api/proximity/route.ts` verbatim: `export const dynamic = "force-dynamic"`, read `range` from `request.nextUrl.searchParams`, call `getPortfolioHistory(range)`, 401 on `NotAuthenticatedError`, 502-degrade otherwise.

**Tests (TDD):**
- `AlpacaPaperBrokerTest` (exec) — new case: stub a `/v2/account/portfolio/history` 200 with parallel arrays + `base_value`; assert the `PortfolioHistory` record maps every field. Add an error-mapping case (4xx → `mapError`) mirroring the existing account tests.
- `PortfolioHistoryExecActivityImplTest` (exec) — mirror `AccountSnapshotExecActivityImplTest`: tenant present → that tenant's broker; null/blank tenant → `ACCOUNT_LEVEL`.
- `PortfolioHistoryClientTest` (bff) — mirror `AccountEquityClientTest`: happy path maps the result; timeout → cancel + degrade to empty; **`range` resolution table test** asserting `1D→(1D,5Min)`, `1W→(1W,15Min)`, `1M→(1M,1D)`, `3M→(3M,1D)`, `1Y→(1A,1D)`, and `YTD→(<days-since-Jan-1>D,1D)` for a fixed injected clock.
- `PortfolioHistoryControllerWebMvcTest` (bff) — mirror `PortfolioControllerWebMvcTest`: `GET /api/portfolio-history?range=1D` 200 with the body shape + `account_scope`; missing `X-Tenant-Id` → the standard tenant-context rejection.

**Verify / success criteria:**
- Build: `mvn -pl contract,services/exec,services/orchestrator,services/tenant-dashboard-bff -am spotless:apply` then `spotless:check`; run the exec + orchestrator + bff module test suites green (re-run `KillSwitchWorkflowImplTest` if flaky).
- Dashboard: `cd dashboard && npm run typecheck && npm run lint && npm run build`.
- **End-to-end behavioral (against `staging_paper`, acct PA3FKGPFYPLH — paper, safe):** with a `staging_paper` session cookie, `curl -s 'http://<dashboard>/api/portfolio-history?range=1M' | jq '.equity | length, .base_value'` returns a non-empty `equity[]` and a numeric `base_value`; `?range=YTD` returns history starting on/after Jan 1. Tenant-scoping check: the same call with a `dev` session resolves `dev`'s broker_target (no cross-tenant leak — it goes through `bffGet`'s `X-Tenant-Id`).
- Spotless note: this phase edits 4 Java modules — `spotless:apply` on EACH. Replay note: new workflow type only, NO `getVersion`. Deploy note: triggers P0.1 (manual live-exec roll).

---

## Phase 2 — Value chart component with range tabs (dashboard only)

**Goal:** a self-contained `"use client"` chart component that polls `/api/portfolio-history`, renders the equity line (green up / red down vs `base_value`) with a dashed baseline and `1D · 1W · 1M · 3M · YTD · 1Y` tabs (1D default), and exposes the range-aware `profit_loss`/`profit_loss_pct` to its parent. No backend change. Independently shippable: it can be dropped onto a scratch route or storybook-style page and verified before P3 wires it into `/live`.

**Charting library — DECIDED: `recharts`** (operator-confirmed 2026-06-28; was the plan's recommendation).
- The dashboard has NO charting lib today (`package.json:12-19`) — `recharts` is the net-new dashboard dependency this feature installs.
- One-line rationale: smallest integration effort for "line + dashed baseline + responsive container" in this React/Tailwind app — declarative `<LineChart>` + `<ReferenceLine>` (the dashed baseline), SSR-safe under Next 14 App Router behind a `"use client"` boundary, and the team already writes declarative React.
- **Considered / fallback (NOT pending decisions):** `lightweight-charts` (TradingView; smaller bundle, stronger scrubbing/crosshair, but imperative/canvas + more React glue) is the candidate to revisit ONLY if P4's scrub interaction becomes a hard requirement. A hand-rolled SVG sparkline (zero-dependency, ~80 lines) is the fallback if `recharts` is ever dropped; swapping to it would change only the component internals, not the P1 data contract.

**Changes (anchors):**
- `dashboard/package.json:12-19` — add `recharts` to `dependencies` (pin an exact version). Run `npm install` to update the lockfile; commit the lockfile.
- `dashboard/components/AccountValueChart.tsx` (new, `"use client"`) — mirror the `LiveProximity.tsx` polling shape (`components/LiveProximity.tsx:13-45`: `POLL_MS`, last-good-frame on error, `cache: "no-store"` fetch) but fetch `/api/portfolio-history?range=<tab>` and re-fetch on tab change. Chart built with recharts `<ResponsiveContainer>` → `<LineChart>` → `<Line>` (the `equity[]` series) + `<ReferenceLine y={base_value} strokeDasharray=...>` (the dashed baseline). Use 1D-appropriate polling only when the 1D tab is active (e.g. `POLL_MS=15000`); longer ranges need not poll aggressively. Type-only import of `PortfolioHistory` from `@/lib/bff` (server-only — only the shape crosses, per `LiveProximity.tsx:4-11`). Line color: emerald when `profit_loss >= 0`, rose otherwise (reuse the threshold logic style from `Pnl.tsx`). Dark slate theme to match.
- Tabs: a small headless tab strip (`@headlessui/react` is already a dep) or plain buttons; 1D default.

**Tests (TDD):**
- The dashboard has no JS test runner configured today (scripts are `lint`/`typecheck`/`build` only, `package.json:5-11`). Do NOT add a test framework in this phase (out of scope / speculative). Success is enforced by `typecheck` + `lint` + `build` + the manual render check below. If the lead wants component tests, that is a separate infra phase.

**Verify / success criteria:**
- `cd dashboard && npm run typecheck && npm run lint && npm run build` green (proves `recharts` + the component compile and SSR-build under App Router behind the `"use client"` boundary).
- **Render check:** temporarily mount `<AccountValueChart>` on a scratch route (or directly on `/live` behind P3) against a `staging_paper` session: switching tabs `1D→1W→1M→3M→YTD→1Y` re-fetches and redraws; the recharts `<ReferenceLine>` dashed baseline sits at `base_value`; the `<Line>` renders emerald on an up day, rose on a down day; an unavailable/empty response shows a degraded state (no crash, mirrors `LiveProximity`'s last-good-frame banner).
- No Java, no spotless, no replay concern. The `recharts` npm dependency is the only added risk (see Risks).

---

## Phase 3 — Compose the `/live` page (dashboard only)

**Goal:** assemble the RH layout top-to-bottom on `/live`: (1) account-total header + range-aware `+$X (Y%)` sharing the chart's range/baseline, (2) the P2 chart, (3) holdings (reuse `open_positions`), (4) recent-activity strip (reuse Trades/Orders). No backend change.

**Changes (anchors):**
- `dashboard/app/live/page.tsx:7-22` — keep the server component shell (`force-dynamic`, `<Nav>`). Render the new composition. Two reasonable structures (pick the simpler at implementation time, KISS):
  - **(a)** A single new `"use client"` `LiveAccount.tsx` that owns the chart + header (so the header's `+$X (Y%)` reads the SAME `profit_loss`/`profit_loss_pct`/`base_value` the chart already fetched — one fetch, one baseline, shared range state), and the server page fetches `getPortfolio()` once for holdings + passes initial data; OR
  - **(b)** server page fetches `getPortfolio()` for holdings and renders `<AccountValueChart>` which lifts its headline up via a callback. (a) is cleaner for the "share one baseline" requirement — prefer it.
- **Account total + header** — the big number is the latest `equity[]` value from the history fetch (account-level, shared); the change is `profit_loss`/`profit_loss_pct` for the selected range, rendered with the green ▲ / red ▼ + the shared `Pnl`/`fmtCurrency` helpers (`components/Pnl.tsx`). Label it "account-level (shared)" exactly like `status/page.tsx:96`.
- **Holdings** — reuse `getPortfolio()` (`lib/bff.ts:198`) `open_positions[]` (`Position`, `lib/bff.ts:56-69`) rendered via `<DataTable>` exactly as `app/positions/page.tsx` already does (options-only; no stock/crypto holding type exists — do not invent one). Columns: contract, qty, entry premium, current mark, unrealized today/total via `Pnl`.
- **Recent activity strip** — reuse the existing Trades/Orders data (`getTrades`/`getOrders`, `lib/bff.ts:194-197`); render a compact last-N list below holdings (link out to the full Trades/Orders tabs). This is reuse, not new data.
- Degrade like `status/page.tsx:20-28`: if `getPortfolio()` throws (non-auth), render a "temporarily unavailable" panel at HTTP 200 with `<Nav>` intact so the kill switch stays reachable.

**Tests (TDD):**
- Same as P2 — no JS test runner; gate on `typecheck`/`lint`/`build` + the manual render checks below.

**Verify / success criteria:**
- `cd dashboard && npm run typecheck && npm run lint && npm run build` green.
- **Render check (staging_paper):** `/live` shows top-to-bottom: account-total big number, `+$X (Y%)` matching the active range tab (flip tabs → header + chart update together off the SAME baseline), the chart, the holdings table (matches `/positions`), and a recent-activity strip (matches the top of `/trades`). 1D defaults on load.
- **Scope-label check:** the header and total visibly carry the "account-level (shared)" caveat.
- **prod_real check:** on a `prod_real` session, the page renders; the chart shows "unavailable" until P0.1 (live-exec roll) is done, and NO order path is touched (read-only). The kill switch remains reachable if the BFF degrades.
- No Java, no spotless, no replay concern.

---

## Phase 4 (optional) — Polish

**Goal:** the RH finishing touches, only if the lead wants them; each is independently shippable and none touch the backend.

**Changes:**
- **True 1D baseline = prior close.** For the 1D tab, confirm `base_value`/`base_value_asof` reflect prior close (Alpaca's `intraday_reporting`/`pnl_reset` params may need setting in the P1 client's 1D mapping). If a knob is needed, it is a one-line param in `PortfolioHistoryClient`'s 1D resolution (extends P1, gate behind its own PR).
- **Scrub/crosshair interaction** on the chart (hover shows the point's equity + time). Cheap with recharts `<Tooltip>`. If a richer financial crosshair is wanted, this is the one place to revisit the considered `lightweight-charts` alternative — its own decision/PR, not a change to the locked recharts choice.
- **Account-scope labeling polish** — a small info tooltip explaining "this is the shared brokerage account, not your tenant's slice," cross-linking the same caveat shown on `/status`.

**Verify / success criteria:** `typecheck`/`lint`/`build` green + a manual render check of the specific affordance. If the 1D-baseline knob touches `PortfolioHistoryClient` (Java), re-run `spotless:apply` + the bff module tests for that PR.

---

## Ship order & gating

1. **Phase 1** (backend proxy) — riskiest-but-foundational; 4 Java modules, own PR. spotless on each module, exec+orchestrator+bff tests green, e2e curl against staging_paper. → triggers **P0.1** (manual `exec-alpaca-live` roll) so prod_real gets the method.
2. **Phase 2** (chart component, recharts) — dashboard-only, own PR. Charting library is DECIDED (recharts); no upstream blocker. Verifiable standalone before wiring.
3. **Phase 3** (compose `/live`) — dashboard-only, own PR. Depends on P2 (the chart) and P1 (the endpoint).
4. **Phase 4** (optional polish) — one PR per affordance, last.

Each phase: TDD where a runner exists (Java) / build+lint+typecheck+manual render where it doesn't (dashboard), own single-concern PR, operator merge gate. No phase modifies an existing Temporal workflow → no `getVersion` gate anywhere in this plan (the new `PortfolioHistoryWorkflow` is net-new history).

## Risks & rollback

- **exec-alpaca-live manual-roll gap (highest-friction).** The new broker method auto-deploys to paper but NOT to live (`exec-alpaca-live` absent from `deploy.yml` SERVICES). prod_real's `/live` chart shows "unavailable" until P0.1 runs. Mitigation: the chart degrades gracefully (last-good-frame / "unavailable" banner, never a crash), so the gap is cosmetic, not breaking. Rollback: none needed — it's read-only; reverting the dashboard PR removes the surface.
- **Account-level / shared-scope misread.** Users may read the total as "their" money. Mitigation: explicit "account-level (shared)" labels on the total and header (matching `/status`). This is the intended shared-demo framing for the self-registration epic.
- **New charting dependency (`recharts`).** Net-new in `dashboard/package.json` — supply-chain + bundle-size surface. Mitigation: pin an exact version, commit the lockfile; the hand-rolled-SVG fallback remains available if `recharts` is ever dropped (swapping it touches only the component internals, not the data contract). Rollback: revert the dashboard PR.
- **Alpaca Portfolio History semantics drift** (YTD derivation, 1D prior-close baseline, timeframe auto-rules). Mitigation: the `range`→`period`/`timeframe` resolution is unit-tested in `PortfolioHistoryClientTest` against a fixed clock (P1), and P4 isolates any prior-close-baseline tuning into its own PR.
- **No DB change, read-only, no money path** → the lowest-risk class of feature here; the 403 block on prod_real is untouched and not depended upon.

## Cross-reference

- **`[[project_self_registration_epic]]`** (`docs/plans/PLAN-2026-06-28-self-registration.md`) — external customers self-register and land in the SHARED `staging_paper` demo showcase (acct PA3FKGPFYPLH). This live-account view is exactly the screen that fronts that shared demo; the "account-level (shared)" scope framing here IS that epic's "everyone sees the same data" demo property. The demo banner / read-only-ish posture from that epic applies to this page when shown to demo-status users.
- Mirrors the existing `AccountSnapshot` path end-to-end (`AccountSnapshotActivity` / `AccountEquityClient` / `AccountSnapshotWorkflowImpl`) — same client-can't-dispatch-an-activity reason, same bounded-getResult-then-degrade posture.
