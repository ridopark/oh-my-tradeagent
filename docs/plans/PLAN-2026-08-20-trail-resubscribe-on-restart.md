# Plan — Re-subscribe armed trailing stops after a market-data restart (#776)

## Context / incident

Every market-data restart silently disarms **every** armed trailing stop in the estate. The
workflow keeps reporting `trailingArmed: true` while no premium ticks arrive, so the chandelier
stop never evaluates and never fires — at any price.

This happened **twice on 2026-08-20**:

| Time | Event |
|---|---|
| 02:34:58Z | market-data restart → all trails orphaned |
| 08:31:09Z | operator re-arms prod_real TSLA → feed `0→1`, tick delivered, healthy |
| 11:41:22Z | market-data rolls (a **dashboard** PR) → orphaned again, destroying the 08:31Z repair |

Ground truth at 11:44:08Z, with two real-money stops reporting `armed: true`:

```
GET /md/premium-subscriptions
{"now":"2026-08-20T11:44:08.570384543Z","subscriptions":[]}
```

- `TSLA  260918P00300000` — 50 contracts, stop 1.023, `ticksReceived` frozen at 1
- `DRAM  270319C00100000` — 2 contracts, stop 2.43375, last tick `2026-08-19T18:14:50Z`

#775 shipped the DETECTION half of #717 (the registry endpoint + the red dot on /live). This plan
is the REPAIR half: nothing re-subscribes, so an operator must notice the dot and manually re-arm
every position after every roll.

## Root cause (verified in code, not inferred)

- `AlpacaMarketData:75` — `bySymbol` is a plain in-process `ConcurrentHashMap`. Nothing persists it.
- **No `@PostConstruct` / `ApplicationReadyEvent` anywhere in `services/market-data`** rebuilds it.
- `SubscribePremiumActivityImpl` *completes* rather than retrying — its javadoc: "swallows
  source-side exceptions and returns FAILED so the workflow can audit and proceed without a trail
  (instead of going into Temporal retry)". **A completed activity is never re-run by Temporal.**
- All `subscribePremium` call sites in `PositionWorkflowImpl` (~2254 arm, ~2384, ~2636
  watchlist-exit) are event-driven. None is periodic; none fires on restart.

## Design decision: fix it in market-data, NOT in the workflow

#776 ranked three options. This plan takes **A (re-subscribe on market-data boot)** over
**B (periodic re-assert from `PositionWorkflowImpl`)**, for two reasons that are decisive here:

1. **No version gate on a real-money workflow.** B adds a timer + activity to
   `PositionWorkflowImpl`. `Workflow.getVersion` marker ORDER is part of the replay contract, and
   this workflow is currently carrying four gates added in the last 24h. A touches zero
   orchestrator workflow code, so there is no replay surface at all.
2. **No added Temporal history.** B adds ~5 events per re-assert per position, on the one
   long-lived workflow here that has **no continue-as-new** (#752) and already approaches the
   10,000-event watermark on a multi-month LEAP trail. A adds **zero** workflow events: subscribing
   happens entirely inside market-data, and only ticks (which would flow anyway) become events.

market-data already holds a `WorkflowClient` (injected into `SubscribePremiumActivityImpl`), so
option A needs no new wiring to reach Temporal.

## The blocking prerequisite: subscribePremium does not dedup

`PositionWorkflowImpl:2792` documents the hazard and works around it:

> re-subscribing would open a SECOND live premium subscription (**SubscribePremiumActivity does not
> dedup**), double-delivering every NBBO print and letting one market print satisfy the post-target
> breakeven-stop debounce.

A boot-recovery that ignores this is **worse than the bug it fixes**: recovery would race an
operator's manual re-arm (which is exactly what an operator does on seeing the new red dot),
producing two subscriptions on the same contract. That doubles the tick→signal rate on a workflow
with no continue-as-new, and lets a single market print satisfy a debounce counter that is supposed
to require consecutive independent ticks.

So dedup is Phase 1 and lands first, independently.

---

## Phase 1 — Make `subscribePremium` idempotent per (occSymbol, positionWorkflowId)

**Files:** `services/market-data/src/main/java/com/ohmytradeagent/marketdata/activities/SubscribePremiumActivityImpl.java`

A second subscription for the same (contract, target workflow) is **never** wanted — the ticks go
to the same workflow, so a duplicate is always pure harm. Key the existing `active` registry by
that pair in addition to `subscription_id`, and have a repeat subscribe return the EXISTING
subscription id rather than opening a second provider subscription.

### Constraints / invariants
- The dedup key is (occSymbol, positionWorkflowId) — **not** occSymbol alone. Two different
  tenants' PositionWorkflows on the same OCC are independent subscribers and must both be fed.
  (prod_real and prod-jinchul hold the same TSLA contract today; collapsing them would starve one.)
- Returning an existing id must NOT reset the throttle baseline: a re-subscribe that zeroed
  `ThrottleState` would emit an unthrottled tick burst.
- Must remain safe under concurrency — the provider drives callbacks from a feed thread. Use the
  same compute-under-lock discipline as `AlpacaMarketData.subscribePremium`.
- Behaviour when the workflow is gone is unchanged (existing `WorkflowNotFoundException`
  self-tear-down must still remove BOTH index entries).

### Tests (TDD)
Add to `services/market-data/src/test/java/.../activities/SubscribePremiumActivityImplTest.java`:
1. **`secondSubscribeForSameOccAndWorkflow_reusesTheSubscription`** — subscribe twice; assert the
   provider opened exactly ONE subscription and both calls returned the same `subscription_id`.
2. **`secondSubscribe_doesNotResetTheThrottleBaseline`** — subscribe, emit a tick, re-subscribe,
   then feed a tick INSIDE the emit band; assert no second signal. Guards the burst hazard above.
3. **`sameOccDifferentWorkflows_bothGetTheirOwnSubscription`** — the multi-tenant case. Assert two
   distinct subscriptions and that BOTH workflows are signalled.
4. **`tearDownRemovesBothIndexEntries`** — after a `WorkflowNotFoundException` tear-down, a fresh
   subscribe for the same pair opens a NEW subscription (no stale-id reuse).

### Success criteria (must all hold)
1. `mvn -B -ntp -pl services/market-data test` → BUILD SUCCESS, 0 failures.
2. Test 1 FAILS without the fix (genuinely reproduces the double-subscription).
3. Test 3 FAILS if the dedup key is narrowed to occSymbol alone — proving the multi-tenant case is
   actually guarded and not merely asserted.
4. All existing `SubscribePremiumActivityImplTest` and `AlpacaMarketDataTest` cases stay green.

---

## Phase 2 — Re-subscribe armed trails on market-data startup — **DEFERRED, REDESIGN REQUIRED**

> **Status 2026-08-20:** implemented, then withdrawn before merge on adversarial review. The
> approach (recover in market-data, not in the workflow) was independently endorsed by all three
> review lenses and is unchanged. What failed review is the TIMING.
>
> **Blocking finding.** Three feed-side guards are plain in-process maps and are COLD on a fresh
> process: `lastAcceptedPremium` (the #690 outlier reference — `acceptPremiumQuote` accepts the
> first quote unconditionally when the ref is null), `lastQuoteStamp` (the resample dedup needs a
> previous stamp), and `throttles` (`shouldEmit` returns true when the baseline is null). Downstream,
> `PositionWorkflowImpl.processTick` has NO debounce — one tick latches the fire — unlike
> `processExitTick`, which requires consecutive sub-threshold ticks precisely so "a single outlier
> print cannot fire". There is no absolute quote-age check anywhere in market-data.
>
> Outside RTH, Alpaca's `latestQuote` returns the PRIOR SESSION's quote. Directly observed
> 2026-08-20 08:35Z: the TSLA snapshot carried `latestQuote.t = 2026-08-19T19:59:59Z`. Both observed
> restarts (02:34:58Z, 11:41:22Z) were outside RTH.
>
> So the first production execution would re-subscribe the whole armed book at once, at boot,
> outside RTH, with every filter cold — turning a pre-existing hazard (operator-initiated, one
> position at a time) into an automatic whole-book one. Verified as NOT currently armed: TSLA
> threshold 1.023 vs stale quote 1.86, DRAM 2.43375 vs 3.20 — neither would fire today. The hazard
> is structural, not live.
>
> **Redesign: an RTH-gated retry loop.** Recovery waits until the options market is open, then
> sweeps, and retries instead of giving up. One mechanism closes four findings: quotes are fresh
> during RTH so the cold-guard first tick is a real current price (and firing is then CORRECT);
> a Temporal/orchestrator blip at boot no longer means "silently does nothing until the next
> restart" (the dominant restart cause is a whole-estate roll, where those deps are also down —
> the failure is correlated with the trigger); crashloop amplification is bounded; and the
> market-hours check exists at all.
>
> **Also required before Phase 2 ships:**
> - Recover `exitArmed` (watchlist-exit) trails too, not only `trailingArmed`. `trailingState()`
>   exposes only the latter; `exitProximity()` carries `contractSymbol`, `trailingArmed` AND
>   `exitArmed` in ONE query, so switching to it fixes the gap and halves the round-trips.
> - Fix the tally: a re-subscribe that returns `Status.FAILED` is currently counted as
>   `skipped_unarmed`, so the AUDIT line misreports the one case it exists to catch.
> - Skip `remainingQty <= 0` (the field is already fetched and never read).
> - A wall-clock deadline, not only a workflow-count cap — the count cap alone permits a ~66min
>   silent sweep, and truncation warns only on the count.
> - Size the cap against the poll ceiling (~20 concurrent contracts on a fixed 4-thread scheduler),
>   and count it against SUBSCRIPTIONS created rather than workflows examined.
> - A `recovery-started` marker, a metric, and an alert route — "no log" currently cannot be told
>   apart from "never deployed".
> - A payload-drift test mirroring `PositionStateViewDriftTest`: deleting `@JsonIgnoreProperties`
>   from the transport mirrors survives the whole suite, because every test mocks `stub.query`.
> - `parseExpiry` must actually match the BFF reader it claims to (that one handles the compact
>   broker form; this one only handles the padded form).
> - Set `tenant_id`/`strategy_id` on the request — the schema marks both required, and failure logs
>   currently print `tenant=null strategy=null`.

### Original design (retained for the redesign)

**Files:** new `services/market-data/src/main/java/com/ohmytradeagent/marketdata/recovery/PremiumSubscriptionRecovery.java`; small extraction in `SubscribePremiumActivityImpl`

On `ApplicationReadyEvent`, asynchronously:
1. List running `PositionWorkflow` executions via the existing `WorkflowClient`
   (`WorkflowType='PositionWorkflow' and ExecutionStatus='Running'`).
2. For each, query `trailingState`. Skip unless `armed == true`.
3. Re-subscribe via the SAME code path the activity uses (see extraction below), which after
   Phase 1 is idempotent — so this cannot double-subscribe against an operator's manual re-arm.
4. Emit a per-outcome log/metric (`recovered`, `skipped_unarmed`, `failed`) so the recovery is
   verifiable rather than assumed.

**Extraction:** the subscribe+throttle+signal wiring currently inline in
`SubscribePremiumActivityImpl.subscribePremium` must be reused, NOT reimplemented. The min-move
throttle is load-bearing — a recovery path that re-implemented subscription without it would signal
~11,700 events/day/position into a workflow with no continue-as-new. Extract it to a
package-private method both callers share.

### Constraints / invariants
- **Must never block or fail startup.** Run off the main boot thread, catch `Throwable`, and let
  market-data come up regardless. A Temporal blip must degrade to "no recovery, loud log", never to
  a crashloop — market-data down is worse than trails unsubscribed.
- Bounded: cap the number of workflows examined and the total recovery wall-clock; log explicitly
  when the cap truncates rather than silently covering less than everything.
- Read-only against Temporal (list + query). It must NOT signal, mutate, or start anything.
- A workflow that is armed but whose contract has expired must not be re-subscribed.

### Tests (TDD)
1. **`recoversAnArmedTrailOnStartup`** — a fake client returning one running workflow with
   `armed:true`; assert exactly one subscription opened for that OCC + workflow id.
2. **`skipsUnarmedWorkflows`** — `armed:false` → no subscription. (Guards against re-subscribing the
   whole book, which would both cost quota and resurrect trails nobody armed.)
3. **`temporalUnavailableAtBoot_doesNotFailStartup`** — the client throws; assert the context still
   starts and the failure is logged. **The headline safety test.**
4. **`recoveryIsIdempotentAgainstAConcurrentManualArm`** — recovery and an activity subscribe for
   the same pair produce ONE subscription. Ties Phase 2 to the Phase 1 guarantee.
5. **`respectsTheWorkflowCap_andSaysSoWhenItTruncates`** — asserts the log/metric, so a silent
   partial recovery cannot masquerade as a complete one.

### Success criteria (must all hold)
1. `mvn -B -ntp -pl services/market-data test` → BUILD SUCCESS, 0 failures.
2. Test 3 FAILS if the recovery is made synchronous/uncaught on the boot path — proving the
   fail-soft property is actually enforced.
3. Test 1 FAILS without the recovery component.
4. `spotless:apply` clean; the whole `services/market-data` suite green.

### Deploy verification (post-merge, market CLOSED)
Restart market-data with an armed trail live, then within one minute:
- `GET /md/premium-subscriptions` lists the armed OCC with `poll_ok_count` climbing
- the /live dot returns to **green** with no operator action
- `/api/trail-liveness` reports `feed_status: "live"` for that position

This is the acceptance test #776 asks for, and it exercises the market-data↔BFF hop that CI
structurally cannot (the two are never started together in CI).

## Halt conditions
- Any change that would require editing `PositionWorkflowImpl` — that is option B, is out of scope
  here, and must not be entered without a version-gate review.
- Any recovery path that can block, slow, or fail market-data startup.
- Any dedup keyed on occSymbol alone (starves the multi-tenant same-contract case).
- If Phase 1's dedup cannot be made safe under the feed-thread concurrency, STOP: Phase 2 must not
  ship without it.

## Out of scope
- Repairing the trail ANCHOR. A re-subscribed trail resumes from its stored `peakPremium`; whether
  that anchor is still correct after a gap is a separate question, and interacts with the
  entry-anchored-stop gap in the exit-policy P&L review.
- `PositionWorkflowImpl` continue-as-new (#752).
- Alerting on the armed-vs-not-subscribed disagreement (#776 option C) — composes with this, but is
  independently shippable and not required for the fix.
