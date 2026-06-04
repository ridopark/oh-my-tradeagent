# Plan — Fix partial-exit retry over-sell on a late fill

## Context / incident

A copytrade STC over-sold and got broker-rejected, leaving a position stuck open.

Timeline (homelab, QQQ 260605C00742000, audit + `order_intent_journal`):
- `PositionEntered qty=5` (BTO filled 5 long calls).
- STC "half" signal (`…1512129296679043172`) → `PartialExitRequested {fraction:0.5, qty_to_close:3}` → limit **SELL 3**.
- Order didn't fill within the 90 s window → `PartialExitFillTimeout {ttl_secs:90}` → `PartialExitRetryRequested`.
- **But the original SELL 3 filled late, right around the timeout** → 3 of 5 sold, **2 remain**.
- The retry re-submitted **SELL 3** (the original qty) → only 2 held → Alpaca `403 "account not eligible to trade uncovered option contracts"` (the 3rd contract is a naked short).
- Reconciliation: `PositionOrphan {qty:2}` — 2 contracts left open, exit stuck.

## Root cause (verified in code)

`services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/workflows/PositionWorkflowImpl.java`, `processOne(PartialExitRequest)`:

1. `qtyToClose` is computed **once** at entry (~line 919: `qtyToClose = Math.min(remainingQty, (long) Math.ceil(remainingQty * fraction))`) and **reused** for both the original order (~line 962) and the retry (~line 1010). It is never re-derived against the live `remainingQty` at retry time.
2. The retry loop clears the pending fill at the top of each iteration (~line 1013: `lastFillEvent = null`) **without processing it**, so a late fill that arrived in the timeout→retry gap is dropped: `remainingQty` is **not** decremented and **no** `PartialExitFilled` is audited.

Net: the retry both (a) ignores that the original exit already (late-)filled and (b) re-sends the full original qty → over-sell.

## The fix (behavior, version-gated)

Two coupled changes in `processOne`, behind ONE new version gate `VERSION_EXIT_RETRY_LATE_FILL_RECONCILE` (`Workflow.getVersion(..., DEFAULT_VERSION, 1)`) so in-flight (pre-fix) workflows replay unchanged:

1. **Reconcile the late fill before retrying.** When the bounded await times out, if a fill was observed (`lastFillEvent != null`), **process it first** (decrement `remainingQty`, emit `PartialExitFilled`, release the in-flight latch) exactly as the normal fill path does — do **not** clear it unprocessed. The late fill is real and must update state + audit.
2. **Clamp the retry to the UNFILLED remainder of the original exit intent — not a re-fraction.** The retry must complete *this exit's* original intent, accounting for what already filled:
   - Track the exit's target: `targetRemaining = remainingAtExitRequest − qtyToClose` (e.g. 5 − 3 = 2).
   - Retry qty = `max(0, remainingQty − targetRemaining)`, which is inherently ≤ `remainingQty` (never a naked short) and never re-applies the fraction to the shrunken position.
   - If the retry qty is **≤ 0** (the original filled late and satisfied the intent), **skip the retry entirely** — emit a clear audit (e.g. reuse `ExitDuplicateSuppressed`/a `note:"exit_satisfied_by_late_fill"` or a dedicated kind) and release the latch. No order is placed.
   - Otherwise place the retry with the clamped qty (with the existing fresh-limit logic unchanged).

Do NOT change v=0 behavior. Keep the existing retry cap (max 1 retry), the fresh-limit source logic, the `:retry` intent-key suffix, and `currentInFlightIntentKey` tracking intact.

### Constraints / invariants to preserve
- Determinism: all logic stays in the workflow; no `Instant.now`/`UUID`/`Math.random`. The new gate uses `Workflow.getVersion`.
- A retry must NEVER submit a qty greater than the live `remainingQty` (the structural anti-naked-short guarantee).
- Existing exit/timeout/retry tests must stay green under the new gate (the gate defaults new executions to the fixed path; legacy-replay tests pin v=0).
- Out of scope: a broader cancel/replace redesign, and remediating the *currently* stuck homelab position (handled separately).

## Tests (TDD)

Add to `services/orchestrator/src/test/java/com/ohmytradeagent/orchestrator/workflows/PositionWorkflowImplTest.java`, in the existing style (`TestWorkflowEnvironment`, mocked `ExecActivities`, `ArgumentCaptor<OrderIntent>`). Reproduce the incident:

1. **`processOne_lateFillBeforeRetry_doesNotOversell` (regression, headline):** position 5; STC fraction 0.5 → intends 3; original SELL 3 placed; advance past the 90 s TTL so the timeout fires; deliver `onFill(qty=3)` for the original (the late fill); let the retry path run. Assert:
   - The retry **either places no order OR places one with qty ≤ remaining (here 0 → skipped)** — crucially it never submits `SELL 3`/any qty > live remaining (capture all `placeOrder` `OrderIntent`s and assert no `:retry` intent has `qty > 2`).
   - The late fill is reflected: a `PartialExitFilled` is audited and `remainingQty` ends at 2 (assert via the position query / closing drain).
2. **`processOne_lateFillPartial_retrySellsOnlyUnfilledRemainder`:** original SELL 3 fills only 1 late → remaining 4, target 2 → retry sells exactly `4 − 2 = 2` (not `ceil(4*0.5)=2` by coincidence — pick numbers that distinguish: position 6, fraction 0.5 → intends 3; original fills 1 → remaining 5, target 3 → retry sells `5 − 3 = 2`, whereas a re-fraction would be `ceil(5*0.5)=3`). Assert the retry qty is 2.
3. **Keep green:** `processOne_exitFillTimeoutRetry_freshLimitOrderFillsAndDecrementsRemaining`, `…secondTimeoutDropsAndCapsAtOneRetry`, `processOne_retryActive_eodTimerFires_cancelHitsRetryKey`, and `PositionWorkflowImplLegacyReplayTest` (v=0 replay) all still pass.

## Success criteria (verbatim, must all hold)
1. `mvn -B -ntp -pl services/orchestrator -am test` → BUILD SUCCESS, 0 failures (KillSwitchWorkflowImplTest is known-flaky: re-run once, do not "fix").
2. The two new tests pass and FAIL without the production fix (i.e. they genuinely reproduce the over-sell — verify by confirming the regression test asserts on the retry qty/skip).
3. `PositionWorkflowImplLegacyReplayTest` passes (v=0 replay determinism preserved by the new version gate).
4. No retry path can emit an `OrderIntent` whose qty exceeds the live `remainingQty` (covered by test 1's capture assertion).
5. `mvn -B -ntp -pl services/orchestrator spotless:apply` then `spotless:check` clean.

## Halt conditions
- If the fix would require changing v=0 behavior or removing an existing version gate → stop and surface (replay-safety risk).
- If the legacy-replay test cannot pass without altering pre-fix command ordering → stop (indicates a missing/incorrect version gate).

## Verification commands
```
mvn -B -ntp -pl services/orchestrator -am -Dtest=PositionWorkflowImplTest,PositionWorkflowImplLegacyReplayTest test
mvn -B -ntp -pl services/orchestrator -am test
mvn -B -ntp -pl services/orchestrator spotless:apply && mvn -B -ntp -pl services/orchestrator spotless:check
```

## Out of scope
- Remediating the currently-stuck 2 QQQ contracts (separate operational action).
- Any cancel/replace or broker-position-reconciliation redesign beyond the in-flight workflow's own fill accounting.
- Sizing/rounding policy for the initial `qty_to_close` (unchanged).
