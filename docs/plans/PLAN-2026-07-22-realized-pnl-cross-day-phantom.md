# PLAN — 2026-07-22 realized-P&L cross-day phantom-proceeds (display + kill-switch)

Operator found prod_real's `/status` "Realized P&L today" showing **+$2,068** on a day it was a
**LOSS**. Confirmed: the day-scoped realized calc credits **raw sale proceeds with zero cost basis**
for a position **entered on a prior day and exited today** (no same-day BUY to FIFO-match) — the
documented-but-unfixed #276 §4 "phantom gain". prod_real 07-22: SELL 11 AAPL 260727C00330000 @ 1.88,
FIFO entry 1.99 (bought 07-21) → correct realized = `(1.88−1.99)×11×100 = −$121`; shown = `1.88×11×100
= $2,068` (raw proceeds). Source: 2026-07-22 forensics (fills + `RealizedPnlCalculator`).

**The same phantom is duplicated in 3 impls**, and the kill-switch copies are real-money-safety:
- `services/tenant-dashboard-bff/.../portfolio/RealizedPnlCalculator.java` — /status + /live display.
- `services/orchestrator/.../activities/DailyPnlActivitiesImpl.java` — account/strategy kill switch.
- `services/exec/.../activities/DailyPnlExecActivityImpl.java` — exec-side daily P&L.
The kill-switch impls feed `AccountKillSwitchWorkflowImpl:1077` / `KillSwitchWorkflowImpl:306` — the
phantom always INFLATES realized (credits proceeds), so a prior-day position **sold today at a loss**
reads as a phantom GAIN → the daily-loss cap can **fail to trip (fail-OPEN)** on cross-day losses.

## P0 — operator: none (calc-only; no live mutation).

## Phase 1 — fix the display calc (bff; RealizedPnlCalculator + PortfolioService)
**Goal:** the day-scoped realized figure FIFO-matches a cross-day exit against its REAL prior-day
entry basis (attributing realized to the exit's trading day), instead of crediting raw proceeds.
BFF-only, no exec change, no exec-alpaca-live roll.

**Changes (anchors — implementer re-reads):**
- `RealizedPnlCalculator.java`:
  - `Lot` gains a `LocalDate day` (the fill's ET date; populated for exits, unused for entries).
  - `fetchLots(...)`: ALWAYS fetch full history (drop the per-day predicate from the FETCH) and carry
    each row's ET date `(filled_at AT TIME ZONE 'America/New_York')::date`.
  - `realize(tenant, strategy, tradingDay)`: fetch ALL entries + ALL exits; call
    `realizePerSymbol(entries, exits, tradingDay)`. `tradingDay == null` → all-time (sum every exit,
    UNCHANGED). Non-null → sum only exits whose ET date equals `tradingDay`, while still consuming
    entry lots for prior-day exits so the FIFO reaches today's correct remaining basis.
  - `realizePerSymbol(entries, exits, LocalDate targetDay)`: FIFO-match all exits chronologically;
    add each exit's realized (matched `(exit−entry)` AND the residual raw-proceeds fallback) to the
    total ONLY when `targetDay == null || exit.day.equals(targetDay)`.
- `PortfolioService.java` (:161-165): the daily realized calc is currently INLINE ("small/fast");
  it's now a full-history scan, so move it into the concurrent sub-read budget (`subreadPool.submit`
  + `await`, same null-seeded degrade as the all-time calc), summed across strategies.

**Tests (TDD):**
- **incident reproduction**: BUY 50 @1.99 (day D1) + SELL 39 (D1) + SELL 11 @1.88 (D2) →
  `realize(D2) == −121` (NOT 2068); `realize(all-time)` unchanged.
- a same-day round-trip still realizes correctly (buy+sell same day).
- a cross-day exit whose entry PRE-DATES history still falls to raw proceeds (remaining documented
  limitation), counted only on its exit day.
- `RealizedPnlCalculatorUnitTest` (realizePerSymbol new signature) + `RealizedPnlCalculatorIT` +
  `PortfolioServiceTest` updated.

**Verify:** `mvn -pl services/tenant-dashboard-bff -am spotless:apply` + `spotless:check` + module
tests; behavioral: prod_real 07-22 realized-today reads −$121.

## Phase 2 — kill-switch daily-loss phantom (RISK-REVIEWED; orchestrator + exec)
**Goal:** close the fail-open gap where a cross-day loss reads as a phantom gain in the daily-loss
cap. **Gated on risk-manager analysis** of the correct daily-loss semantic for a cross-day exit
(FIFO since-inception attributed to the exit day — as Phase 1 — vs a daily mark-to-mark vs SOD /
prior-close), since the account cap's basis is SOD-equity, not since-inception. The mechanical
phantom (raw proceeds) is unambiguously wrong either way; the open question is what the CORRECT
cross-day daily number is for the cap.
- Anchors: `DailyPnlActivitiesImpl.realizePerSymbol` (:117-135), `DailyPnlExecActivityImpl`
  (:86-...), their `computeRealizedPnl(day)` callers in `AccountKillSwitchWorkflowImpl` /
  `KillSwitchWorkflowImpl` / `AccountPnlActivitiesImpl`.
- Replay: the calc runs inside a Temporal ACTIVITY — changing the FIFO result is an activity-OUTPUT
  change, NOT a command-shape change, so NO `Workflow.getVersion` gate (only command type/ordering is
  replay-checked). But it CHANGES real-money trip behavior → risk-manager sign-off + tests that lock
  the direction (a cross-day loss now trips; a cross-day gain unchanged).
- Scoped/planned after Phase 2's risk analysis lands; likely its own PR(s).

## Ship order & gating
1. Phase 1 (display, bff-only) → merge + deploy. 2. Phase 2 (kill-switch) after risk analysis, its
own risk-reviewed PR(s). Each: TDD, spotless on touched modules, operator merge gate. Commit trailer:
`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
