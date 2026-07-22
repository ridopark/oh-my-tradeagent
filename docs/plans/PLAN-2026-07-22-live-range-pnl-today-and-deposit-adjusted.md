# PLAN — 2026-07-22 live-range-pnl-today-and-deposit-adjusted

The `/live` header "▲ $4,282.03 (8.93%) for the selected range" is **not** the selected range's return — it is the **last trading day's** P&L, mislabeled. The whole path is a pass-through of Alpaca `GET /v2/account/portfolio/history`, and the header reads `profit_loss[last]`/`profit_loss_pct[last]` (`dashboard/components/LiveAccount.tsx:44-51`) on the false assumption that Alpaca's array is cumulative-from-`base_value`. It is not: the request omits `pnl_reset` (`services/exec/.../broker/alpaca/AlpacaPaperBroker.java:617-654`), so Alpaca defaults to **per-day reset** and every element is a single day's P&L — `1M` and `3M` both return the identical `4282.03` (today's number). The naive fix (`pnl_reset=no_reset`) is worse: verified live it returns **+$47,259 / +945%** for `1M`, because Alpaca's `profit_loss` counts **cash deposits** as profit (this account was funded from $5k with a `+$41,230` transfer on 07-15). Conclusion: neither Alpaca mode yields a truthful range return for a deposit-active account — the range figure must be **computed** from `equity[]` net of cash flows.

**Outcome:** the `/live` header shows **two** numbers — **Today** (latest trading day, kept from the existing per-day-reset value) and **This range, excl. deposits** (deposit-adjusted trading P&L over the selected range, with a Modified-Dietz %). Source of ground truth: live read of real acct `847309116`, this session's forensics.

---

## P0 — Immediate operational (no code; operator)
- **None required now.** The mislabel is cosmetic/ misleading but not a trading-risk. No orphan/position action. (Operator deploy/roll steps for the code phases are in **Ship order** below.)

---

## Phase 1 — Relabel the existing number as "Today" (dashboard only)
**Goal:** Stop the misleading "for the selected range" wording immediately; the number Alpaca gives is correctly *today's* P&L, so label it that way. Frontend-only, no backend change, ships first.

**Changes** (anchors):
- `dashboard/components/LiveAccount.tsx:86` — replace `for the selected range` label with `today` (e.g. `<span className="…">today</span>`). No command-shape/backend change.
- `dashboard/components/LiveAccount.tsx:9-13` — update the header comment block to state the number is the latest trading day's P&L (per-day-reset `profit_loss[last]`), not a range delta; remove the "range delta always matches the active tab" claim.
- Keep reading `profit_loss[last]`/`profit_loss_pct[last]` (`:44-51`) — for every range that value is the latest day's P&L (confirmed 1D/1W/1M/3M all ≈ today), so it is the correct "Today" source with no new fetch. Minor cross-tab wobble of a few dollars (5Min vs 1D granularity) is acceptable; a dedicated `1D` fetch for a rock-stable Today is explicitly deferred.

**Verification (no JS test runner in repo — operator decision 2026-07-22):**
- The dashboard has no jest/vitest. Gate on `cd dashboard && npm run typecheck && npx next lint && npm run build` (all green) plus a code assertion that the rendered label string is `today` and the literal "selected range" no longer appears in `LiveAccount.tsx`. No `LiveAccount.test.tsx`.

**Verify / success criteria:** typecheck + lint + build green; `grep -c "selected range" dashboard/components/LiveAccount.tsx` == 0 and the change line renders "today". No backend/schema touched. Dashboard auto-deploys via `deploy.yml` SERVICES matrix on merge.

---

## Phase 2 — Fetch cash flows in the exec activity (contract + exec)
**Goal:** Make the account's cash deposits/withdrawals available to the BFF so the range return can be deposit-adjusted. Additive contract fields, unused by the UI yet → safe to ship and roll.

**Changes** (anchors):
- `contract/schemas/portfolio-history-result.json:14-58` — add three OPTIONAL properties (leave `required` at `schema_version,timestamps,equity`):
  - `cash_flow_timestamps`: `array` of `integer` (epoch seconds of each cash flow in the window) — parallel-array style, matching the existing `timestamps` convention.
  - `cash_flow_amounts`: `array` of `number` (Alpaca `net_amount`; deposit `+`, withdrawal `−`).
  - `cash_flows_available`: `boolean` — `true` when the activities read succeeded (even if zero flows); `false`/absent when it failed, so the BFF can null the range number instead of showing a deposit-polluted one. Regenerates `com.ohmytradeagent.contract.PortfolioHistoryResult` (javaType) + the Python pydantic model → round-trip drift check must pass.
- `services/exec/.../broker/OptionsBroker.java:187-206` — add, mirroring the `getPortfolioHistory` default + `PortfolioHistory` record:
  - `default List<AccountCashFlow> getAccountActivities(long startEpochSec, long endEpochSec) { throw new UnsupportedOperationException(...); }`
  - `record AccountCashFlow(long timestamp, BigDecimal amount) {}`
- `services/exec/.../broker/alpaca/dto/AlpacaAccountActivity.java` (new) — `@JsonIgnoreProperties(ignoreUnknown=true)` record over Alpaca activities: `activity_type` (String), `net_amount` (BigDecimal), `date`/`transaction_time` (String; Alpaca returns a date/ISO string — parse to epoch, do NOT bind to Long, per the existing `base_value_asof` lesson at `AlpacaPortfolioHistoryResponse` javadoc).
- `services/exec/.../broker/alpaca/AlpacaPaperBroker.java` (new method, mirror `tradingDays` at `:562-600` and the `getPortfolioHistory` query-param + `mapError` pattern at `:617-654`) — `getAccountActivities(start,end)` → `GET /v2/account/activities?activity_types=CSD,CSW,JNLC&after=<ISO start>&until=<ISO end>`; map each to `AccountCashFlow(epoch(date), net_amount)`; `catch (HttpStatusCodeException e) throw mapError(e)`. READ-ONLY (no order path). Covers live too (live uses this same class via base-URL config; no separate `AlpacaLiveBroker`).
- `services/exec/.../activities/PortfolioHistoryExecActivityImpl.java:46-58` — after the history read, if `timestamps` non-empty, call `broker.getAccountActivities(timestamps[0], timestamps[last])`, set the two parallel arrays + `cashFlowsAvailable=true`. Wrap in `try/catch (RuntimeException | UnsupportedOperationException)` → on failure set `cashFlowsAvailable=false`, empty flow arrays (degrade; StubBroker / non-Alpaca targets simply report unavailable). **No new Temporal command** — this is a second broker call *inside the same existing Activity*, so `PortfolioHistoryWorkflow`'s command sequence is unchanged → **no `getVersion` gate needed** and existing (sub-8s, request-scoped) histories replay byte-identically.

**Tests (TDD):**
- `services/exec/.../broker/alpaca/AlpacaPaperBrokerTest` (WireMock, mirror the portfolio-history test): stub `/v2/account/activities` with a CSD + CSW + JNLC → asserts the URL carries `activity_types=CSD,CSW,JNLC` and `after`/`until`, and the mapped `AccountCashFlow` list has correct signs/epochs; an HTTP 500 → `mapError` failure.
- `services/exec/.../activities/PortfolioHistoryExecActivityImplTest`: broker returns history + flows → result carries both parallel arrays and `cashFlowsAvailable=true`; `getAccountActivities` throws → `cashFlowsAvailable=false`, empty flows, history still returned (degrade).
- Contract round-trip drift check green (schema ↔ Java POJO ↔ pydantic).

**Verify / success criteria:** `mvn -pl contract,services/exec -am spotless:apply && mvn -pl contract,services/exec -am spotless:check test` green. Behavioral assertion: activities URL includes `activity_types=CSD,CSW,JNLC`; degrade path yields `cash_flows_available=false` without failing the read. (Rebuild `-am` so the broker-interface change doesn't break downstream module compiles — the cross-module exec-ctor/spotless trap.)

---

## Phase 3 — Deposit-adjusted range return calculator (tenant-dashboard-bff)
**Goal:** Compute the true trading P&L over the range ($ and Modified-Dietz %) from `equity[]`, `base_value`, `timestamps[]`, and the Phase-2 cash flows; expose as additive response fields.

**Changes** (anchors):
- `services/tenant-dashboard-bff/.../portfolio/PortfolioReturnCalculator.java` (new, PURE, no I/O) — inputs: `equity[]`, `baseValue`, `timestamps[]`, `flowTimestamps[]`, `flowAmounts[]`, `flowsAvailable`. Outputs `rangePl` (BigDecimal or null) and `rangePlPct` (BigDecimal fraction or null):
  - `EV = equity[last]`, `BV = baseValue`, `T0 = timestamps[0]`, `T1 = timestamps[last]`, `NetFlows = Σ amount`.
  - `rangePl = EV − BV − NetFlows` (null if `flowsAvailable==false`, or `equity`/`baseValue` absent).
  - Modified-Dietz `rangePlPct = (EV − BV − NetFlows) / (BV + Σ(w_i·F_i))`, `w_i = (T1 − t_i)/(T1 − T0)`.
  - **Guards → null pct:** `flowsAvailable==false`; denominator `≤ 0`; `T1==T0`; empty equity. (Covers the `base_value=0` case seen in the `3M` payload.)
- `services/tenant-dashboard-bff/.../web/PortfolioHistoryController.java:66-76` — after building `body`, invoke the calculator and `body.put("range_pl", …)` / `body.put("range_pl_pct", …)` (nullable) and pass through `cash_flows_available`. Leave `profit_loss`/`profit_loss_pct` untouched (Phase-1 "Today" still reads those). Additive only.
- (No change to `PortfolioHistoryClient.java` range→period/timeframe mapping — keep default per-day reset; the range number is computed from `equity[]`, which is reset-independent.)

**Tests (TDD):**
- `PortfolioReturnCalculatorTest` — **incident-reproduction fixtures:**
  - Deposit-in-window: `BV=5000, EV=52259.56`, a `+41230` flow mid-range → `rangePl` ≈ trading-only (≈ `EV−BV−41230`), and `rangePlPct` is a sane single/low-double-digit fraction, **NOT** the 9.45 (945%) no-reset inflation. Asserts deposit inflation is removed.
  - `today != range`: a frame where the latest day is `+4282` but a mid-range deposit exists → range $ ≠ `profit_loss[last]`.
  - Guards: `baseValue=0` → pct null (no divide-by-zero); `flowsAvailable=false` → both null; single-timestamp window → pct null.
- `PortfolioHistoryControllerWebMvcTest` (extend at existing file): response JSON carries `range_pl`, `range_pl_pct`, `cash_flows_available`; null-safe when history degraded.

**Verify / success criteria:** `mvn -pl services/tenant-dashboard-bff -am spotless:apply && mvn -pl services/tenant-dashboard-bff -am spotless:check test` green. Behavioral assertion: the 945%-fixture yields a bounded, deposit-free return; controller emits the additive fields. BFF auto-deploys via `deploy.yml` on merge.

---

## Phase 4 — Render the second number (dashboard)
**Goal:** Show "This range, excl. deposits ▲ $X (Y%)" beside "Today"; render "—" when the range figure is null (flows unavailable / undefined denominator).

**Changes** (anchors):
- `dashboard/lib/bff.ts:296-303` — extend the `PortfolioHistory` interface with `range_pl: number | null`, `range_pl_pct: number | null`, `cash_flows_available: boolean` (mirrors the additive BFF fields).
- `dashboard/components/LiveAccount.tsx:81-88` — under the existing "Today" line, add a second line reading `range_pl`/`range_pl_pct`: `This range (excl. deposits) ▲ {fmtCurrency(range_pl)} ({(range_pl_pct*100).toFixed(2)}%)`; when `range_pl==null` or `range_pl_pct==null`, render "—" with a tooltip/subtext ("excludes deposits & withdrawals; unavailable for this range"). Reuse `fmtCurrency`, the up/down arrow + emerald/rose classes.
- (`dashboard/app/api/portfolio-history/route.ts` needs no change — it forwards the BFF body verbatim.)

**Verification (no JS test runner — operator decision 2026-07-22):**
- Extract the change-line rendering (arrow + `$`/% + null→"—") into a small pure helper (e.g. `formatChange(pl, plPct)`) so the null-vs-value branch is inspectable, and gate on `npm run typecheck && npx next lint && npm run build`. Manual/visual check that Today and Range render as two distinct lines and a null range → "—".

**Verify / success criteria:** typecheck + lint + build green; both lines render; `range_pl==null` → "—". Dashboard auto-deploys on merge.

---

## Ship order & gating
1. **Phase 1** (dashboard relabel) — independent, ship first; auto-deploys.
2. **Phase 2** (contract + exec) — merge, then **operator: manually roll `exec-alpaca-live` AND `exec-alpaca-paper`** (exec is NOT in the `deploy.yml` SERVICES matrix). Additive fields dormant until Phase 3/4.
3. **Phase 3** (bff) — merge after exec is rolled (so `cash_flows_*` exist on the wire before the BFF reads them); auto-deploys.
4. **Phase 4** (dashboard) — merge last, after the BFF emits `range_pl*`; auto-deploys.

Per phase: TDD-first (each includes an incident-reproduction test), `spotless:apply` on **every** touched Java module (`-am` rebuild), one PR, operator merge gate. `KillSwitchWorkflowImplTest` is a known flake → re-run, don't fix. `gh pr edit --body` is broken → set PR body at create time. Commit trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`. Do not touch `.github/workflows/*.yml`.

## Notes / deferred
- **Deposit detection is only as good as Alpaca activities.** If `getAccountActivities` fails, the range number degrades to "—" rather than showing a deposit-polluted figure (`cash_flows_available=false`).
- **Modified-Dietz vs time-weighted:** Modified-Dietz is the pragmatic single-period money-weighted return; a true TWR would need sub-period valuation at each flow — deferred as unnecessary for a single-account retail view.
- **Rock-stable "Today":** a dedicated `period=1D` fetch (instead of `profit_loss[last]` of the active range) is deferred; current cross-tab wobble is a few dollars.
