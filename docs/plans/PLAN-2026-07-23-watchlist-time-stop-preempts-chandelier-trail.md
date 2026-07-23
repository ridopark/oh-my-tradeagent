# PLAN-2026-07-23 — Watchlist no-progress time-stop pre-empts the chandelier trail

The watchlist-trigger exit is a 2:1 bracket: take-profit at `entry × (1 + tp_ratio × sl_pct)`
partial-closes half and arms a chandelier trail on the runner. In practice the trail almost never
governs an exit. Across the strategy's whole history, `WatchlistExitMeasured.exit_rule` is
**time_stop ×22, stop_loss ×6, chandelier_trail ×1**, and the watchlist target-runner trail has
fired **0** times. On 2026-07-23 all five staging_paper watchlist positions exited on `time_stop` at
~26 minutes, including TSLA — which hit its target, armed the trail, and was then flattened by the
time-stop before the trail could act.

Root cause: the `no_progress_time_stop_secs` timer is a hard wall-clock from the first fill that is
never cancelled or gated on the take-profit, so it flattens a trailing runner. This **contradicts the
field's own documented contract**, which scopes it to the pre-take-profit window only.

Source: live forensics 2026-07-23 (orchestrator `audit_log`, homelab) + code read of
`PositionWorkflowImpl`. Decision: Fork A (operator, 2026-07-23) — no time cap on the runner once the
take-profit fires; rely on the chandelier giveback + breakeven stop + EOD/expiry backstops.

## 1. Root cause — spec vs. code

**The field's documented contract** (`contract/schemas/strategy-config.json`,
`no_progress_time_stop_secs`):

> "**if neither the take-profit nor the hard stop has triggered** within this many seconds of the
> first fill, PositionWorkflow flattens the position (reason=time_stop) so a **stalled breakout**
> does not bleed theta into the −1R stop."

So it is a stalled-breakout guard, defined to apply only *before* the take-profit fires.

**The code fires it unconditionally:**
- `PositionWorkflowImpl.java:1889-1897` — `armWatchlistExit` arms `Workflow.newTimer(timeStopSecs)`
  and, on fire, sets `exitTimeStopFired = true`. A bare timer: there is **no `CancellationScope` in
  the entire file** (grep = 0), so nothing cancels or resets it. It is a fixed max-hold from the
  first fill.
- `PositionWorkflowImpl.java:2037-2090` — `fireExitTarget` fires the partial, moves the stop to
  breakeven, and arms the chandelier, but **does not touch the time-stop timer.**
- `PositionWorkflowImpl.java:1186-1187` — the main loop flattens the whole remaining lot on
  `exitStopFireRequested || exitTimeStopFired || exitFeedStaleFired` with **no `&& !exitTargetFired`
  guard**, so a post-target time-stop flattens the trailing runner. `reason` resolves to `time_stop`
  (the `stop_loss` label is only for the bid-stop branch).

**Why the trail is starved from both ends:** the target is `entry × (1 + tp_ratio × sl_pct)` = +60%
at the staging_paper config (`tp_ratio 2.0`, `sl_pct 0.30`); most positions do not move +60% within
25 minutes, so they time-stop before the target ever arms the trail (correct — the intended
stalled-breakout kill). The few that DO reach target then have their runner flattened by the same
25-minute timer before the giveback trail can play out (the bug). Net: `WatchlistExitTargetFired` has
occurred 3 times ever and the trail governed the exit 0 of those 3 — the time-stop took all of them.

TSLA 2026-07-23 is the exact trace: `PositionEntered` 13:33 → `WatchlistExitTargetFired` 13:46
(target 4.80) → `ChandelierArmed` 13:47 (peak 4.80, giveback 0.30) → `time_stop` flatten 13:58 @ 7.50.
The runner's `premium_mfe` reached 7.55; the giveback threshold (`7.55 × 0.70 = 5.285`) was never hit
because the time-stop got there first. The +5R was luck (the timer expired near the peak), not the
trail working.

## 2. P0 — Immediate operational (no code; operator)

- None. No positions are stuck; this is lost upside, not a stuck-state or risk breach. The watchlist
  strategy is intraday (`eod_force_flatten=true`, `force_close_eod_et=15:30`), so nothing carries
  overnight from this behavior.
- No ConfigMap or tenant-YAML change in the shippable phase, so no manual `kubectl apply`. The
  orchestrator deploys automatically on merge to main.

## 3. Phases

### Phase 1 — Scope the no-progress time-stop to the pre-take-profit window (orchestrator)

**Goal:** make the time-stop match its documented contract — a stalled-breakout guard that stops
applying once the take-profit fires and the runner is trailing. This is Fork A: after target, the
runner is governed only by the chandelier giveback, the breakeven stop, and the EOD/expiry flattens.

**Changes** (anchors):
- `PositionWorkflowImpl.java:1186` — gate the time-stop leg on the target not having fired. The
  branch becomes, in effect, `exitStopFireRequested || (exitTimeStopFired && !exitTargetFired) ||
  exitFeedStaleFired`. The bid-stop (`stop_loss`) and feed-staleness legs are unchanged — only the
  `exitTimeStopFired` contribution is narrowed. Once `exitTargetFired`, the moved-to-breakeven stop
  (`fireExitTarget`, `:2083-2085`) is the floor and the chandelier is the ceiling.
- `PositionWorkflowImpl.java:1152` — narrow the matching `Workflow.await` wake predicate the same way
  (`exitTimeStopFired && !exitTargetFired`) so a post-target time-stop fire does not needlessly wake
  the loop to a no-op. Behavior-preserving relative to the `:1186` guard; keeps the two in lock-step.
- Leave the timer arm at `:1892` as-is: a bare fire-and-latch timer is harmless once the consumer is
  gated, and NOT arming/cancelling it keeps the recorded command stream identical for replay (see
  below). `exitTimeStopFired` simply becomes a no-op latch when the target has already fired.

**Replay safety (mandatory):** this changes *when* a `flattenRemaining("time_stop")` command is
issued on a running `PositionWorkflow` history (a post-target time-stop that used to flatten now does
not), so it MUST be version-gated. Add a new marker read once at a stable scope
(e.g. alongside `watchlistExitVersion`):
```java
Workflow.getVersion("watchlist-timestop-pretarget-only-v1", Workflow.DEFAULT_VERSION, 1)
```
At `DEFAULT_VERSION` keep the current unconditional fire so in-flight histories replay
byte-identically. Deliberately NOT wrapping the timer in a `CancellationScope`: cancelling it would
be a new command and a larger replay surface; gating the *consumer* achieves Fork A with the timer
still firing into a latch that is now ignored post-target.

**Tests (TDD):**
- `PositionWorkflowImplTest.watchlistTimeStop_afterTargetFired_doesNotFlattenRunner` — **reproduces
  the TSLA incident**: arm the exit, drive a tick ≥ target (fires the partial + arms the trail), then
  let the `no_progress_time_stop_secs` timer elapse → assert the runner is NOT flattened with
  `reason=time_stop`, the position stays open on the trail, and NO `WatchlistExitMeasured
  exit_rule=time_stop` is emitted for the runner.
- `PositionWorkflowImplTest.watchlistTimeStop_beforeTargetFired_stillFlattens` — a stalled position
  that never reaches target still time-stops at the window (the intended guard is preserved).
- `PositionWorkflowImplTest.watchlistTimeStop_afterTarget_runnerExitsViaChandelier` — after target,
  drive the bid up to a peak then give back `trail_giveback_pct` → the runner exits with
  `exit_rule=chandelier_trail`, proving the trail now governs.
- Replay determinism: a pre-change history that flattened a post-target runner on `time_stop`
  replays byte-identically at `DEFAULT_VERSION`.

**Verify / success criteria:**
```
mvn -pl services/orchestrator -am spotless:apply
mvn -pl services/orchestrator -am test -Dtest=PositionWorkflowImplTest
```
Behavioral assertion: replaying the TSLA 2026-07-23 sequence (entry 3.00, target 4.80 at +13m,
bid peak 7.55) yields a chandelier-governed runner exit, not a `time_stop` flatten at +25m.
`KIND_WATCHLIST_EXIT_MEASURED` and the `chandelier_trail` label already exist — no `AuditEventKinds`
change. `KillSwitchWorkflowImplTest` is a known timing flake — re-run, do not fix.

### Phase 2 — Target partial fails to place and is not re-tried in-session (orchestrator) — FORENSICS FIRST, not yet shippable

**Concern:** on 2026-07-23 the TSLA target partial (`sell 3 of 5` at 4.80) emitted
`PartialExitPlaceFailed` with `retryState=RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED`; the underlying
broker cause is not in the audit subject and the exec pod logs have recycled. So the 2:1 tier was
never banked and the whole lot rode to exit.

**Two distinct problems, both needing investigation before an anchor-level fix:**
1. **Why did `PlaceOrder` exhaust retries** for a plain marketable SELL of 3 contracts on a paper
   account? Needs a forensic against the exec `order_intent_journal` for the intent_key
   `...:exit:...:watchlist-target...` and the exec activity retry policy — is this a transient broker
   error, a duplicate-cid 422, buying-power, or a too-tight retry budget?
2. **The existing re-drive is next-session only.** `PositionWorkflowImpl.java:2431-2470`
   (`VERSION_PARTIAL_PLACE_RETRY_NEXT_SESSION`) re-drives a failed partial at the NEXT RTH open — but
   a watchlist position is flat by `force_close_eod_et=15:30` the same day, so a next-session re-drive
   can never help it. The target partial needs an **in-session** retry (or to fold into the immediate
   chandelier/stop management) to be meaningful for an intraday strategy.

**Do NOT write this phase's anchors until the forensic on (1) lands** — the fix differs sharply
depending on whether the place failure is a broker-transient (→ widen/relax the exec retry) vs. a
structural rejection (→ different handling). File as a follow-up.

### Phase 3 — Post-target feed-blind backstop (orchestrator) — discovered in Phase 1 review, FOLLOW-UP

**Concern (surfaced by quant-analyst + risk-manager review of the Phase 1 diff, 2026-07-23):** once
the take-profit fires, both the runner's breakeven stop and the chandelier trail are **bid-tick
driven** (`processExitTick`, ~`:2028`). The feed-staleness backstop only guards the *never-saw-a-tick*
window (`!exitTickSeen`, ~`:1939`), which is already false post-target. Before Phase 1 the
`no_progress_time_stop` was the de-facto feed-blind catch-all in this window; Phase 1 gates it off
post-target (correctly, per Fork A). So if the premium feed goes dark **after** target fires, neither
tick-driven governor can evaluate and the runner rides unprotected until the EOD `15:30` flatten.

**Bounded, not a blocker for Phase 1:** watchlist is intraday (`eod_force_flatten=true`), so the loss
is capped same-day (no overnight/assignment), the +2R tier is already banked on the partial, and this
is a rare dead-feed combination. A sharper instance: because `exitTargetFired` is set before the
partial places (`fireExitTarget:2069`), if the target partial *place-fails* (the Phase 2 case) the
**whole** lot — not half — rides to EOD without the time-stop; still protected by the breakeven stop
when the feed is live, exposed only in the dead-feed combination.

**Fix direction (not yet anchored):** a post-target feed-staleness watchdog (re-arm a bounded
staleness timer once `trailingArmed`, flatten `reason=feed_stale` if no tick within the window) closes
the dead-feed variant; Phase 2's in-session partial retry closes the whole-lot variant. File as a
follow-up; neither blocks Phase 1 given the intraday EOD bound.

**Also noted (pre-existing, out of scope):** the post-target breakeven level uses
`input.getEntryPremium()` (`:2071`) while the stop/target were armed off the actual `firstFillPrice`
(`:1144`); on slippage/partial fill the "breakeven" is the signal entry, not the true fill basis.
Pre-existing, not introduced by Phase 1.

## 4. Forks

**Fork A — DECIDED (operator, 2026-07-23): no time cap on the runner once the take-profit fires.**
Rely on the chandelier giveback + the breakeven stop + EOD (`15:30`) + the expiry-lead flatten. The
alternative (B: a separate longer max-hold on the runner) was declined — the breakeven stop already
caps the runner's downside at scratch and EOD flattens intraday, so a second timer adds complexity
for little gain. Phase 1 implements A.

## 5. Ship order & gating

1. **Phase 1** (time-stop pre-target guard) — the shippable fix; version-gated workflow change, its
   own PR, operator merge gate (trading-critical).
2. **Phase 2** (target-partial place failure) — blocked on a forensic; opens as a separate
   investigation, then its own plan/PR.

Phase 1: TDD-first, `spotless:apply` on `services/orchestrator` before commit, own PR. No
`.github/workflows/*.yml` edits. Set the PR body at create time (`gh pr edit --body` is broken in
this repo). Commit trailer:
`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
