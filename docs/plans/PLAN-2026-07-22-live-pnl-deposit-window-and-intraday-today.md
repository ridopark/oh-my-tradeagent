# PLAN — 2026-07-22 fix /live P&L: deposit-window double-count + mislabeled "today"

Two proven bugs on the `/live` account view, found live on prod_real (2026-07-22, real-money acct).
Forensics: this session's trace against live Alpaca data + `PortfolioReturnCalculator`.

**Broker truth (prod_real):** deposits $5,000 (06-12) + $3,000 (07-06) + $1,000 (07-13) + $40,000
(07-15) = **$49,000**; 1M `base_value=5000` asof **2026-06-18**; `equity[last]=52,259.56`; live
`equity=50,477.06`, `last_equity=52,259.56`.

- **Bug 1 — range "excl. deposits" is sign-flipped.** Shows **−$1,740.44**; true deposit-adjusted
  trading P&L is **+$3,259.56 (a profit)**. `−1740.44 = 52259.56 − 5000 − 49000` (all deposits);
  correct `= 52259.56 − 5000 − 44000` (exclude the pre-base 06-12 funding, already IN base_value).
  The initial funding is double-subtracted.
- **Bug 2 — "today" shows yesterday.** Header "▲ $4,282.03 today" is the last COMPLETED daily bar
  (07-21's +$4,282), not live intraday. True today = `equity − last_equity = 50477.06 − 52259.56 =
  −$1,782.50 (a loss)`.

## P0 — operator: none (read-only view; no live mutation).

## Phase 1 — window range cash-flows by base_value_asof (exec + bff)
**Goal:** exclude any cash flow dated at/before the range baseline (`base_value_asof`) from
`netFlows`, so the initial funding baked into `base_value` is never subtracted again. Highest-impact
(sign-flipped real-money number); self-contained. Ships first.

**Root cause (verified by reading):** `PortfolioReturnCalculator.compute` (`PortfolioReturnCalculator
.java:91`) sets `windowStart = floorDiv(timestamps[0],86400)*86400` and filters flows `t >= windowStart`
(`:99`). It should anchor to `base_value_asof` — but that field is DISCARDED upstream
(`AlpacaPaperBroker.java:717-721` passes `null`), and a degenerate leading `0L` equity timestamp
(`AlpacaPaperBroker.java:733-734` coerces `null→0L`) makes `windowStart=0`, admitting every deposit.

**Changes (anchors — implementer re-reads before editing):**
1. `services/exec/.../broker/alpaca/dto/AlpacaPortfolioHistoryResponse.java` (~:24-30) — map Alpaca's
   `base_value_asof` (`@JsonProperty("base_value_asof") String baseValueAsof`).
2. `services/exec/.../broker/alpaca/AlpacaPaperBroker.java` (:717-721) — parse `resp.baseValueAsof()`
   (date string, e.g. `2026-06-18`) to epoch-seconds (`LocalDate.parse(..).atStartOfDay(UTC)
   .toEpochSecond()`) into the `PortfolioHistory` record's existing `baseValueAsof` slot instead of
   `null`. Guard a null/blank string → null. The exec activity + contract `PortfolioHistoryResult`
   already carry the field (chain is dark today), so NO contract-schema change.
3. `services/exec/.../broker/alpaca/AlpacaPaperBroker.java` (:733-734, `toLongArray`) — HARDENING:
   skip/drop a leading `null` timestamp rather than coercing to `0L` (a `0L` lower bound is the
   trigger that admits inception flows + over-fetches activities at `:764 after=truncate(ts[0])`).
4. `services/tenant-dashboard-bff/.../portfolio/PortfolioReturnCalculator.java` — add a `Long
   baseValueAsof` param to `compute(...)`; at `:99` change the lower-bound test so anything dated
   `<= baseValueAsof` is excluded (already in base). Fall back to the current `timestamps[0]`-derived
   `windowStart` when `baseValueAsof` is null (behavior-preserving when the field is absent). Keep the
   first-day-deposit handling (a first-day flow is dated AFTER base_value_asof, still counted +
   weight-clamped).
5. `services/tenant-dashboard-bff/.../web/PortfolioHistoryController.java` (:86-92) — pass
   `history.getBaseValueAsof()` into `compute(...)`.

**Tests (TDD):**
- `PortfolioReturnCalculatorTest` — **incident reproduction**: `equity[last]=52259.56`,
  `baseValue=5000`, `timestamps[0]` = a pre-funding/`0L` leading value (the production shape),
  flows = [06-12:+5000, 07-06:+3000, 07-13:+1000, 07-15:+40000], `baseValueAsof=<06-18 epoch>` →
  `rangePl = +3259.56` (NOT −1740.44); the 06-12 funding is excluded.
- boundary: a flow dated EXACTLY on `base_value_asof` is excluded; one dated the next day is included.
- `baseValueAsof=null` → falls back to the old `timestamps[0]` window (existing tests still pass).
- exec: `AlpacaPaperBroker` parses `base_value_asof` into the record; a null/blank string → null;
  `toLongArray` drops a leading null instead of emitting `0L`.

**Verify:** `mvn -pl services/exec -pl services/tenant-dashboard-bff -am spotless:apply` +
`spotless:check`; the two module test runs; behavioral assertion: the prod_real 1M inputs yield
`range_pl = +3259.56`.

## Phase 2 — true intraday "today" from equity − last_equity (exec + bff + dashboard)
**Goal:** the "today" line reflects live intraday P&L, not the last completed daily bar.

**Changes (anchors):**
1. `services/exec/.../broker/alpaca/dto/AlpacaAccountResponse.java` (:38-50) — map `last_equity`
   (`@JsonProperty("last_equity")`), currently dropped by `@JsonIgnoreProperties`.
2. Thread `last_equity` through the account snapshot (`AccountSnapshotWorkflow` result → the account
   DTO the BFF reads) alongside the existing `equity`. Confirm whether this touches the account
   snapshot contract DTO; if so, add the field optional/nullable (additive) and regen.
3. BFF: surface `today_pl = equity − last_equity` (and `today_pl_pct = (equity−last_equity)/
   last_equity`) on the account-equity read the `/live` header uses (or on the portfolio-history
   response), null when `last_equity` unavailable.
4. `dashboard/components/LiveAccount.tsx` (:70-77, `AccountTotal`) — the "today" line reads the new
   intraday `today_pl`/`today_pl_pct` when present; fall back to the current `profit_loss[last]` only
   when the intraday field is unavailable. Keep the label "today" honest (it's now genuinely today).

**Tests (TDD):**
- exec: `AlpacaAccountResponse` maps `last_equity`; account snapshot carries it.
- bff: `today_pl = equity − last_equity` (sign preserved); null when `last_equity` null.
- dashboard: header renders the intraday figure when present (prod_real: −$1,782.50), falls back
  cleanly when absent. (Note: `dashboard/` has no test runner — assert via typecheck + a `// TODO`
  if no infra, per repo precedent.)

**Verify:** `mvn -pl services/exec -pl services/tenant-dashboard-bff -am spotless:apply` +
`spotless:check` + module tests; dashboard `tsc --noEmit` + `next build`; behavioral: prod_real
"today" reads ≈ −$1,782.50 (loss).

## Ship order & gating
1. **Phase 1** (range double-count; exec + bff; no contract-schema change) → merge + deploy.
2. **Phase 2** (intraday today; exec + bff + dashboard; possible additive account-snapshot field).
Each: TDD, spotless on every touched Java module, own PR, operator merge gate. No Temporal
command-shape change (pure read/DTO/calc). No `tenants/*.yaml`/ConfigMap change. Commit trailer:
`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
