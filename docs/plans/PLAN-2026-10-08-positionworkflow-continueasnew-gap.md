# PLAN — 2026-10-08 PositionWorkflow continue-as-new gap (#752) + trailing-stop gap protection (#747)

**Status:** plan only. No code is authorised by this document.
**Anchors:** verified by reading `origin/main` @ `fe210c68` (2026-10-08). `PositionWorkflowImpl.java`
and `PositionWorkflowImplContinueAsNewTest.java` are byte-identical on `fix/903-condor-gate-gap`;
alert-package line numbers differ on that branch, so branch from `main`.
**Predecessor:** `docs/plans/PLAN-2026-08-22-position-continue-as-new.md` (its Phases 1 and 2 shipped).

Both issues live in the same code path: the chandelier trail inside `PositionWorkflowImpl`. #752
is about how long an armed trail can live, and #747 is about what it does at the open after an
overnight hold. One plan orders them so that any future change to the trail's behaviour (which
#747 may require) runs into the continue-as-new guard rails first.

---

## 0. TL;DR

- **#752 is mostly shipped, not missing.** Continue-as-new landed in #794 (2026-08-22) and was hardened
  by #826/#838, #837/#841 and #840/#843. The history gauge (old plan's Phase 1) is live.
  **What remains is proof and guard rails:** the roll has never run against a real Temporal
  server. Zero `PositionWorkflow` executions have ever had status `ContinuedAsNew` in prod
  (checked today). Two latent hazards were found by reading the code (§3).
- **The DRAM LEAP that motivated both issues is closed.** Its trail fired at the 2026-09-01 open
  after an overnight gap: 45% giveback configured, ~52% realized (peak 3.525, threshold 1.939,
  filled 1.68). **No trail is armed anywhere on the live book today**, and every running position
  has a history under 200 events. There is no time pressure on #752.
- **The premise of #747 needs correcting.** A chandelier fire does not send a market order. It
  sends a bounded limit anchored on the live bid (`computeBoundedFlattenLimit`, `:4787`), and the
  floor is relative to that bid, which has already gapped. So the floor is inert across a gap.
  2 of 510 recorded trail fires were overnight-gap fires. Every remedy beyond the copy shipped in
  #797 changes live trading behaviour, so **the operator must pick a fork (§5.1)**. The
  recommendation is a read-only notice before the close (Phase 4), with no change to the workflow.

---

## 1. What already exists (do not rebuild)

| item | where | shipped |
|---|---|---|
| watermark `historyLengthWatermark = 10_000L`, un-gated by design | `PositionWorkflowImpl.java:1030` (javadoc `:1016-1029`) | #794 |
| roll site at top of main loop | `:1876-1884` | #794 |
| quiet-position barrier `rollBarrierHolds()` (24 conjuncts incl. `isEveryHandlerFinished`, `tp_ratio == null`) | `:1452-1478` (javadoc `:1421-1451`) | #794, #826 |
| carry-forward input builder | `buildCarryForwardInput()` `:1486-1570` | #794 (+#738, #753, #807 fields) |
| `@WorkflowInit` hydration, carried-run marker = `carried_remaining_qty` | `:1359-1419` | #794 |
| first-fill gate bypass on carried run | `:1776` (`deferVersion >= 1 && !carriedRun`) | #794 |
| search attributes re-asserted on carried run (SDK 1.27 drops them from the CAN command) | `:1767-1772` | #794 |
| completion awaits parked handlers | `awaitHandlersFinishedBeforeClose()` `:1593-1595` | #837 |
| arm on drained position rejects | `armTrail` `:3172-3216` | #840 |
| history-length gauge + 6,000 page, Visibility-enumerated | `metrics/PositionHistoryLengthGauge.java:72,75,98` | old-plan Phase 1 |
| `Recreate` rollout strategy on orchestrator | `infra/k8s/51-orchestrator.yaml:33` | #794 |
| tests: trail round-trip, dedupe, watchlist exclusion, exit-in-flight, scheduled-retry, #807 floor carry, in-flight handler ×2, completion ×2, drained arm, below-watermark | `PositionWorkflowImplContinueAsNewTest.java:345-1035` | #794..#843 |

### 1.1 The constraints the lead asked about, each re-verified

| constraint | verdict | evidence |
|---|---|---|
| **Update-before-run() race must not lose a buffered operator Update** | Holds on both sides of the roll. **Old run:** the barrier requires `pendingForceCloses`/`pendingPartialCloses`/`pendingExits` empty and `isEveryHandlerFinished()` (`:1453, :1462-1467`), so no buffered or parked directive can be discarded. **New run:** `forceClose` (`:2516`) and `partialClose` (`:2601`) buffer when `input == null`. The main loop drains them. The deferred trim audit is gated by `VERSION_BUFFERED_OPERATOR_AUDIT` (`:2761`), which resolves to 1 on a fresh run, so the audit is emitted. `remainingQty`/`positionConfirmed` are hydrated in `@WorkflowInit`, so the `positionConfirmed && remainingQty <= 0` NOOP branches cannot misfire. | read |
| **Armed trail survives the roll** | Holds. `trailingArmed`, `peakPremium`, `givebackPct`, `trailArmedByOperator`, `entryFillPrice` and tick telemetry are carried (`:1520-1533`). Tests `rollCarriesTrail_stopStillFiresAtSamePrice` (`:345`) and the two #807 tests (`:588, :624`) pin it. `disarm_trail` clears `trailingArmed`/`trailArmedByOperator`/`peakPremium`/`givebackPct` (`:2961-2964`), and those cleared values are exactly what gets carried. | read |
| **Premium subscription across the roll (#717 interaction)** | The roll **neither fixes nor orphans** a subscription. The registry key is `(occ, positionWorkflowId)` (`SubscribePremiumActivityImpl.java:84,102-104`) and fan-out uses `newUntypedWorkflowStub(posWfId)` with no run id (`:261`). So ticks reach the new run and **the carried run never re-subscribes.** Consequence: if a market-data restart had already orphaned the subscription before the roll, the roll does **not** repair it. Repair still belongs to the #776/#784 RTH recovery sweep (`PremiumSubscriptionRecovery.java:69,291-300`). That sweep enumerates Running executions and queries `exitProximity`/`positionState` by workflow id, so it covers carried runs, because both queries read carried fields. #717 is closed. | read |
| **positionState must answer identically (#728 hazard)** | Holds. `positionState()` (`:3750-3761`) returns `contractSymbol`, `remainingQty`, `entryPremium`, `entryAt`, `partialExited`, and all of them are input fields or carried. **`trailingState()` does NOT fully match:** `lastTickObservedAt` (`:1260`, written `:2044`) is neither carried nor proven zero by the barrier, so it reads `null` until the first post-roll tick. That field is display-only today ("nothing reads it yet", `:1257`). See §3 G2. | read |
| **Exit-in-flight never rolls** | Holds (`:1456-1461`). The test the lead flagged as flaky is `exitInFlight_blocksRoll_untilDrained` (`:473`). **A failure could not be found:** none of the last 80 failed CI runs (all branches) mentions `ContinueAsNewTest`/`blocksRoll`. See §6. | CI log scan 2026-10-08 |
| **Recon/adoption and operator buttons are run-id-agnostic (#718/#726)** | Holds. A grep found no run-id-pinned stub anywhere in `services/*/src/main` (`newUntypedWorkflowStub(id, runId)` / `setRunId`: zero hits). Every Visibility consumer filters `ExecutionStatus='Running'`: `AccountPnlActivitiesImpl:140,193`, `VisibilityPortfolioSnapshot:203`, `VisibilityPositionCounter:27`, `FloorBreachAlertLoop:137`, `PositionLookupActivitiesImpl:71,86`, `OpenPositionWorkflowChecker:34`, `PositionsController:77,167`, BFF `PositionsReader:64`, `ProximityReader:153`, audit `TemporalOpenPositionSource:28`. So a `ContinuedAsNew` run is never counted as an open or closed position. The recon running-probe describes by id only (`PositionLookupActivitiesImpl:176-185`), and the latest run reports RUNNING. | grep + read |
| **WORKFLOW_ID_REUSE_POLICY / #923 precedent** | Not applicable to the roll. Continue-as-new is not a new start, so the reuse policy is never consulted. #923 (`ALLOW_DUPLICATE` + describe-first) applies to *re-creating* a closed id. For positions, recon re-adoption mints a **new** id (#718), so no id collision path exists. No change needed. | `git show 608f764d` |

---

## 2. Live state, measured 2026-10-08 (read-only)

- `temporal workflow count --query "WorkflowType='PositionWorkflow' AND ExecutionStatus='ContinuedAsNew'"` → **0**.
- 23 running `PositionWorkflow`s. Oldest: 5× `SMCI 261120C00050000`, started 2026-08-25, history 92-130.
  Largest: `GOOGL 261016C00360000` prod_real at 195. **`trailingState.armed=false` on all checked.**
- Every running position started **after** `chandelier-breakeven-floor-v1` (#807, merged 08-22) and
  `chandelier-trail-on-bid-v1` (#811, merged 08-23) deployed. This matters for G1.
- `PositionHistoryLengthGauge` is registering gauges in prod (orchestrator log, 12:10Z today).
- Temporal server **1.31.0** (homelab), Java SDK **1.27.0** (`pom.xml:27`).
- Gap fires in `audit_log` (`ChandelierTrailFired` before 09:40 ET whose prior tick was from a
  previous session): **2 of 510** fires.

  | date | contract | giveback cfg | peak → threshold | fill | realized giveback |
  |---|---|---|---|---|---|
  | 2026-08-19 | SPY 260825P00760000 ×3 tenants | 25% | 1.99 → 1.4925 | 1.06 | ~47% |
  | 2026-09-01 | DRAM 270319C00100000 (prod_real, prod-jinchul) | 45% | 3.525 → 1.939 | 1.68 | ~52% |

  The other 10 early-session fires (06-26, 06-30, 07-30) crossed intraday with ≤2% slippage past
  the threshold. They were not gaps.

---

## 3. Findings: the real delta for #752

**G1. A carried run silently upgrades every top-of-run version gate to max.** Severity: latent
(zero live exposure today, but it bites the next gate that changes trail semantics).
A new run's history is empty, so `Workflow.getVersion` returns max at `:1616-1631`. That includes
`breakevenFloorVersion` (`:1626`) and `trailOnBidVersion` (`:1630`), and both change **when the stop
fires**:
- A run that started pre-#811 ratcheted `peakPremium` in **mid** space. After the roll, `processTick`
  compares **bids** (`:2058`) against `peak_mid × (1−g)`. The stop moves up by roughly half the
  spread with no audit, which hits hardest on exactly the illiquid LEAPs that live long enough to roll.
- A pre-#807 **auto**-armed trail gets the breakeven floor (`:2827`) at the roll. If the bid is
  already under cost, the next tick fires.

Both cases *tighten* the stop, so neither is the "loosening" class #752 warned about. Neither
happens today, because every running position post-dates both gates (§2). **But a gap-protection
gate added for #747 (Phase 5 Fork G) would be a *loosening* gate, and a pre-gate position that rolls
later would silently switch to it.** The old plan's §5.1 rule 4 ("safe because it is the new run")
holds for replay but not for behaviour. Phase 1 makes this a build-time decision.

**G2. The barrier's claim to cover every non-carried field has drifted.** The javadoc at `:1426-1427`
says "Every field `buildCarryForwardInput` does NOT carry is proven zero by a conjunct here". After
#794, `lastTickObservedAt` (`:1260`) was added. It is written on the copytrade path (`:2044`), it is
not carried, and the barrier does not prove it zero. It is harmless today (observation-only). But
the comment at `:2038-2043` names a future feed-staleness backstop as its reader, and a staleness
check that sees `null` right after a roll reads it as "never heard a tick". `lastBid` (`:1244`) only
looks like a second violation: its only writer is `processExitTick` (`:3578`), the watchlist path,
which the `tp_ratio == null` conjunct excludes. Nothing in the build stops the next field from
drifting the same way. Phase 1 adds that check.

**G3. No replay fixture covers a *carried* run.** The `if (carriedRun)` upsert (`:1767`) and the
first-fill bypass (`:1776`) are described as "fresh history only, no gate needed". That is true only
until the first real roll. After it, carried-run histories exist in prod, and any later edit to
those branches needs a version gate. Nothing would catch a missing one. Phase 2.

**G4. Nothing has proven search-attribute and Update/Signal behaviour at the roll boundary against
a real server.** All roll tests run on the in-memory `TestWorkflowEnvironment`. The
"SDK 1.27 drops search attributes on CAN" finding (`:1877-1882`) comes from that environment. The
upsert at `:1767` covers either behaviour, except for a **window**: from the moment the new run
starts until its first workflow task completes, it may carry no `TenantStrategy` search attribute.
During that window every `TenantStrategy`-filtered Visibility consumer cannot see it. That includes
`AccountPnlActivitiesImpl` (account loss cap, where an invisible position fails *open*) and
`OpenPositionWorkflowChecker` (tenant-delete guard). Normally the window is sub-second. It is
unbounded if no worker is polling at the moment of the roll, for example when the roll coincides
with an orchestrator pod `Recreate`. How the 1.31 server treats an Update that is *admitted but not
yet accepted* when the CAN command commits is also unverified. Phase 3.

---

## 4. P0: operator / no-code

1. **Nothing urgent.** No armed trail on the book, max history 195, gauge live. No interim action
   for #752.
2. **Decide the #747 fork (§5.1).** It gates whether Phase 4 and/or Phase 5 exist.
3. **Decide the #752 close criterion:** (a) close after Phase 3 with a Deploy-Verified waiver in the
   style of PR #126/#127, with real-server evidence standing in for a natural prod roll, or (b) keep
   #752 open until the first natural prod roll. That may never happen unless a long-dated trail is
   armed again. **Recommendation: (a).** The gauge's 6,000 page is the trigger for re-running the
   post-roll checklist on the first natural roll.
4. **Before Phase 5 deploys (if chosen):** re-run the §2 queries. Confirm no running position has an
   armed trail that started before the new gate, or accept the G1 behaviour for it explicitly.
5. **Memory hygiene (done alongside this plan):** two memory notes said "no continue-as-new" and
   "trail arms stops below entry". Both are stale (#794; #807 for the auto path).

---

## 5. #747: design forks (operator decision required)

### 5.1 The forks

The mechanism: `fireChandelier` (`:3719-3729`) → `flattenRemaining("chandelier_trail")` → the
bounded path (not in the `immediacy` set at `:4609-4617`) → `computeBoundedFlattenLimit` (`:4787`).
That computes `limit = max(anchor, floor)` (`:4870`), where `floor = max(exit_floor_abs,
anchor × exit_floor_pct)` with **anchor = the live bid** (`:4829, :4972-4985`). The order rests
at the gapped bid. It is a marketable limit, not a market order, but across a gap that makes no
difference. Gap detection, if any fork needs it, must use the BID (it already does at `:2058`
under `trailOnBidVersion ≥ 1`; #690).

| fork | what | trading behaviour change | replay / CAN cost | recommendation |
|---|---|---|---|---|
| **S** status quo | Close #747 as "surfaced" by #797 (confirm dialog + badge tooltip say the stop is best-effort overnight). | none | none | acceptable |
| **N** pre-close notice | At ~15:40 ET, post one Discord notice per **armed-trail position that will be held overnight**, with bid, stop, distance-to-stop and the measured gap record. The operator then flattens, tightens or accepts. This is issue option 2 as a prompt, not an automation. | none (read-only) | none (outside Temporal) | **recommended** (Phase 4) |
| **G** open grace window | When the first tick of a new session crosses the threshold, don't fire. Re-evaluate on the bid N minutes later and fire only if it is still at or under the threshold. Optional hard backstop: fire at once if the bid falls below `threshold × (1 − k)`. | **loosens** the stop for N minutes after a gap | new timer → new gate + new barrier conjunct + G1 carry decision | only if the operator wants automation (Phase 5-G) |
| **L** gap limit cap | On a gap fire, rest the sell at `max(bid, threshold × (1 − cap))` instead of the bid. If unfilled it stays open via the existing late-fill / next-session retry. | refuses to sell below a level, so it can **hold a losing position with no stop** | limit price alone is activity input (no gate); any new audit is a command (gate); must also bound the stepped reprice walk (`VERSION_EXIT_STEPPED_REPRICE`, `:601`) or the walk crosses the cap | not recommended |
| **R** refuse overnight arm unless opted in | Issue option 3. | blocks arming of long-dated trails like DRAM's | new Update field + config + gate | **not recommended**: contradicts the measured +EV of overnight holds (memory: n=23, mean +10.8%, worst −44.5%) |

**Why N over G/L:** the evidence base is **n=2 gap fires**. That is too few to show that waiting N
minutes or capping the price beats selling at the open. The repo's own rule is that a signal claim
needs a fit/test split, and two events cannot be split. N turns every overnight armed hold into an
explicit operator decision at zero replay risk, which is what the issue body itself identified as
the operator's call. G or L can follow N once enough gap events accumulate to measure.

**Interactions to note, not scope (per the lead):** the #807 breakeven floor raises auto-armed
thresholds to cost. That makes it more likely a position closes near its stop, and therefore more
likely to gap-fire at the open. It does not change the gap fill. Operator-armed trails stay exempt
(`:2827`). Do not redesign the trail here.

---

## Phase 1: Carry-forward classification guard (#752, G1 + G2)

**Goal:** make it a **build failure** to add a `PositionWorkflowImpl` field or version gate without
deciding what happens to it at the roll.

**Changes (anchors):**
- New test `services/orchestrator/src/test/java/com/ohmytradeagent/orchestrator/workflows/PositionWorkflowCarryForwardClassificationTest.java`.
  It reflects over every non-static declared field of `PositionWorkflowImpl` and asserts each one
  appears in **exactly one** of these explicit sets:
  `CARRIED` (hydrated at `:1365-1418`), `BARRIER_ZERO` (pinned by a `rollBarrierHolds` conjunct
  `:1453-1477`), `WATCHLIST_ONLY` (excluded by `input.getTpRatio() == null`, `:1477`; e.g.
  `exitStopLevel`, `lastBid`), `DERIVED_AT_RUN` (`input`, `exec`, `brokerTarget`, `expectedQty`,
  timers, `initGatesResolved`, …), `RESETS_AT_ROLL_ACCEPTED` (`lastTickObservedAt`, justified
  display-only), and `VERSION_RESOLVES_MAX_ON_CARRIED_RUN` (every `int *Version` field, e.g.
  `breakevenFloorVersion` `:1099`, `trailOnBidVersion` `:1102`, `entryGrowthVersion` `:1084`,
  `fillClearVersion` `:1087`). The failure message must say: *"classify this field. If it is a
  version gate that changes WHEN a stop fires, a carried run must keep the ORIGINAL run's version.
  Carry it and take min(carried, resolved) in run(). See PLAN-2026-10-08 §3 G1."*
- `PositionWorkflowImpl.java:1421-1428`: correct the javadoc claim to name the classification test
  as the source of truth. No code change.
- `PositionWorkflowImpl.java:1347-1358`: add one paragraph to the `@WorkflowInit` javadoc saying a
  carried run resolves every `getVersion` to max (G1), and why that is accepted today: every running
  position post-dates the two trail gates, verified 2026-10-08.

**Replay safety:** comments + test only. No command shape change. No gate.
**Not done (KISS):** no carry of `lastTickObservedAt` and no version carrying today. Both have zero
readers or zero exposure now. The guard forces the decision onto the PR that creates exposure
(the staleness backstop, or Phase 5).

**Tests (TDD):**
- `everyFieldIsClassifiedExactlyOnce`. Mutation check, run with `mvn test`, never `surefire:test`:
  add a dummy `private boolean probe;` to `PositionWorkflowImpl` → the test must FAIL. Remove it.
- `versionFieldsAreExactlyTheIntVersionFields`: every `int` field whose name ends in `Version` must
  be in `VERSION_RESOLVES_MAX_ON_CARRIED_RUN`.
- `classifiedNamesAllExist`: no stale names in the sets after a field rename or removal.

**Verify:**
```sh
cd /home/ridopark/src/oh-my-tradeagent
mvn -q -pl services/orchestrator -am spotless:apply
mvn -q -pl services/orchestrator -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='PositionWorkflowCarryForwardClassificationTest+PositionWorkflowImplContinueAsNewTest'
mvn -q -pl services/orchestrator -am spotless:check
```
Success: BUILD SUCCESS. The mutation check above fails as described, and that result is recorded in
the PR body.
**PR:** `Refs #752`. Touches the trading-critical file (javadoc only), so it needs **operator merge**.

---

## Phase 2: Carried-run replay fixture (#752, G3)

**Goal:** the first real prod roll will create carried-run histories. Before it does, there must be
a tripwire that fails any un-gated edit to the carried-only branches.

**Changes (anchors):**
- `PositionWorkflowImplLegacyReplayTest.java`: add a fixture and a replay test that mirror the
  existing pattern (`:300-338`, regenerator `:340-398`). The fixture is
  `src/test/resources/temporal/replay/position-carried-run-history.json`. It is the history of the
  **second** run of a rolled position: carried input with `trailingArmed=true`, the upsert
  (`:1767`), several ticks, one ratchet, and a fire with its flatten. Record it once with the
  regenerator, using the lowered-watermark technique from `PositionWorkflowImplContinueAsNewTest:117`.
- Fixture discipline (memory: replay fixtures here have been toothless twice): **never re-record**
  after merge. A future change that breaks it needs a gate, not a new fixture.

**Replay safety:** test-only.

**Tests (TDD), falsified both ways, with results in the PR body:**
- `carriedRunHistoryReplaysAgainstCurrentImplWithoutNonDeterminism` passes on `main`.
- Falsify 1: delete the `if (carriedRun) { upsert… }` block (`:1767-1772`) → the test MUST fail with
  `NonDeterministicException`.
- Falsify 2: drop `!carriedRun` from `:1776`, so a carried run awaits a first fill → the test MUST fail.
- Confirm the recorded input really has `carried_remaining_qty` set. A fixture whose input lacks the
  marker replays the parent path and proves nothing.

**Verify:**
```sh
mvn -q -pl services/orchestrator -am spotless:apply
mvn -q -pl services/orchestrator -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='PositionWorkflowImplLegacyReplayTest'
mvn -q -pl services/orchestrator -am spotless:check
```
**PR:** `Refs #752`. Test-only, so normal review.

---

## Phase 3: Real-server roll proof + post-deploy verification doc (#752, G4)

**Goal:** prove on a real Temporal server what has only been proven in-memory. Write the
verification artifact the old plan required and nobody wrote.

**Changes (anchors):**
- New opt-in test `PositionWorkflowContinueAsNewRealServerIT.java` (orchestrator test tree), annotated
  `@EnabledIfSystemProperty(named = "temporal.it.target", matches = ".+")` so default CI skips it.
  Build the env with `TestEnvironmentOptions.newBuilder().setUseExternalService(true).setTarget(target)`.
  Confirm those builder methods exist in SDK 1.27 first; if they don't, build
  `WorkflowServiceStubs` + `WorkerFactory` directly. Reuse the fixture helpers from
  `PositionWorkflowImplContinueAsNewTest` (`:140-300`), copied or extracted only if a third user appears.
  The server is the dev server in docker, a version matched to homelab 1.31 where possible:
  ```sh
  docker run --rm -p 7233:7233 temporalio/temporal:latest server start-dev --ip 0.0.0.0 \
    --search-attribute TenantStrategy=Keyword --search-attribute ContractSymbol=Keyword
  ```
- New `docs/ops/post-deploy-verification/issue-752-position-history.md`, structured like
  `issue-127-killswitch-history.md` (Check / Command / Expected / Actual / Verdict). Part A records
  the real-server results from this phase. Part B is the natural-roll checklist from the old plan
  (§6 Phase 2 "Post-deploy verification" items 1-6), triggered by the gauge's 6,000 page.

**Tests (all against the real server):**
1. `realServer_searchAttributesPresentOnNewRun`: after the roll, describe → `TenantStrategy` and
   `ContractSymbol` are present. **Measure the window:** poll `TenantStrategy='t-dev/s-copytrade-v1'
   AND ExecutionStatus='Running'` every 50 ms across the roll and record the longest gap with zero
   hits. This settles G4: if the server inherits search attributes there is no window, and the PR
   body says so. If it doesn't, the gap is measured.
2. `realServer_trailAndPositionStateIdenticalAcrossRoll`: `trailingState` peak/threshold/armed and
   `positionState` (all 5 fields) are equal before and after. Then a tick at the threshold fires the flatten.
3. `realServer_updateRacingTheRoll_isNeverLost`: push history to the watermark with a tick burst
   while concurrently sending `partial_close` (fraction 0.5) and `disarm_trail`. Each Update must
   return a non-error result (ACCEPTED/DISARMED), its effect must be visible on the final run
   (`remainingQty` halved, `armed=false`), and its audit row must appear **exactly once**.
4. `realServer_signalRacingTheRoll_processedOnce`: an STC `partialExit` sent in the same burst
   produces exactly one `placeOrder`, with dedupe through the carried `processedSignalIds`.

If test 3 shows the server aborting an admitted Update across CAN, **stop and escalate**. The fix
(client retry in the BFF Update path, or an extra barrier conjunct) is a new phase, not part of this one.

**Verify:**
```sh
mvn -q -pl services/orchestrator -am spotless:apply
mvn -q -pl services/orchestrator -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='PositionWorkflowContinueAsNewRealServerIT' -Dtemporal.it.target=localhost:7233
mvn -q -pl services/orchestrator -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='PositionWorkflowContinueAsNewRealServerIT'     # without the property: skipped, not failed
mvn -q -pl services/orchestrator -am spotless:check
```
**PR:** `Closes #752` if the operator picked close criterion (a) (§4.3), otherwise `Refs #752`.
Test + doc only, so normal review. Paste the IT output into the PR body. CI cannot run it.

---

## Phase 4: Pre-close overnight-armed-trail notice (#747, Fork N, recommended)

**Goal:** before every close, the operator sees each armed trail that will be held overnight and
what a gap would cost. Read-only and outside Temporal.

**Changes (anchors):**
- New package `services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/alert/overnighttrail/`,
  modelled on `FloorBreachAlertLoop` (`alert/floorbreach/FloorBreachAlertLoop.java:39-61`: `@Component`,
  `@Profile("!test")`, tryLock, best-effort everywhere, emits an **audit event only**, and the page
  rides the `OrderFailureAlerter` after-commit funnel).
  - `OvernightTrailNoticeLoop`: `@Scheduled(cron = "0 40 15 * * MON-FRI", zone = "America/New_York")`.
    Enumerate via the same `registry.list()` + per-pair Running Visibility query
    (`FloorBreachAlertLoop:137-140`) and query `trailingState` + `positionState`. A position
    qualifies when `armed == true`, `OccSymbol.expiryOf(contract)` is after today (ET), and the
    strategy does not `eod_force_flatten` (read it the way `FloorBreachThresholdResolver` reads
    strategy config). Fetch the live bid through `MarketDataOptionQuoteClient`. Emit one
    `OvernightTrailHeld` audit per position per ET date, with subject: contract, remaining_qty,
    bid, threshold, giveback_pct, `distance_pct = (bid − threshold) / bid`, `trail_armed_by` if
    available, and a fixed note: *"stop is best-effort across the overnight gap; realized givebacks
    on recorded gap fires: 47% (cfg 25%), 52% (cfg 45%)"*.
  - Known limits, stated in the javadoc and not engineered around: on half-days, 15:40 is after the
    13:00 close. On market holidays the cron still runs and the notice is redundant but harmless.
- `services/audit/src/main/java/com/ohmytradeagent/audit/AuditEventKinds.java`: add
  `"OvernightTrailHeld"` to `ALL_KINDS` (neighbour `"TrailDisarmed"` `:478`). Otherwise
  `KindRegistryGuardTest` blocks the push.
- `OrderFailureAlerter.java:136-140` `DEFAULT_FAILURE_KINDS` **and** `application.yml:130`
  `failure-kinds` image default: add `OvernightTrailHeld`. Both are needed, because the env is
  unset on homelab and deploy never applies it. Add a YELLOW embed builder next to
  `buildTrailDisarmedEmbed` (`OrderFailureAlerter.java:389`).
- `application.yml` `alert.overnight-trail.enabled: ${ALERT_OVERNIGHT_TRAIL_ENABLED:true}` (image default).
- Add the package to a no-trading-action source-scan guard. Either extend
  `FloorBreachNoTradingActionGuardTest` to cover `alert/overnighttrail/` or add a sibling. The
  package must place, modify and cancel nothing.

**Replay safety:** no workflow code touched.

**Tests (TDD):**
- `OvernightTrailNoticeLoopTest`:
  - `armedTrailHeldOvernight_emitsOneNotice`: incident reproduction using the 2026-08-18 SPY
    shape: armed, peak 1.99, gb 0.25, expiry 2026-08-25, close at 15:40 → one `OvernightTrailHeld`
    whose threshold is 1.4925.
  - `unarmed_noNotice`, `expiresToday_noNotice`, `eodForceFlatten_noNotice`
  - `secondTickSameEtDate_noDuplicate`
  - `quoteUnavailable_stillNotifiesWithNullBid` (the notice matters more than the number)
  - `queryFailure_skipsThatPositionOnly`
- `OrderFailureAlerterTest`: `overnightTrailHeld_rendersYellowEmbed`.
- `KindRegistryGuardTest` / `AuditEventKindsTest` stay green.

**Verify:**
```sh
mvn -q -pl services/audit,services/orchestrator -am spotless:apply
mvn -q -pl services/audit -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='KindRegistryGuardTest+AuditEventKindsTest'
mvn -q -pl services/orchestrator -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='OvernightTrailNoticeLoopTest+OrderFailureAlerterTest+FloorBreachNoTradingActionGuardTest'
mvn -q -pl services/audit,services/orchestrator -am spotless:check
```
**Post-deploy:** the audit service must roll **before or with** orchestrator, or the new kind lands
as `UnknownKind`. On the first trading day with an armed overnight trail, check that the Discord
notice arrives at 15:40 ET. If none is armed, no notice is expected and the deploy is a no-op.
**PR:** `Closes #747` if the operator picks N alone, `Refs #747` if G follows. Read-only, but it
pages operators by default, so it needs **operator ack** on merge.

---

## Phase 5: Gap grace window in the workflow (#747, Fork G, ONLY if the operator chooses it)

Shown in detail only so the cost is visible when the operator chooses. **Trading-critical, operator merge.**

**Goal:** a threshold cross on the first tick of a new session waits `GAP_GRACE` minutes and
re-evaluates on the bid before firing.

**Changes (anchors):**
- New gate `VERSION_CHANDELIER_GAP_GRACE = "chandelier-gap-grace-v1"`, read into a field **above**
  `this.initGatesResolved = true;` (`:1633`, "append later markers ABOVE this line") and after
  `trailOnBidVersion` (`:1630`). Marker order is part of the replay contract.
- **G1 carry, mandatory:** this gate *loosens* a stop. Add optional `carried_gap_grace_version`
  to `contract/schemas/position-workflow-input.json`, set it in `buildCarryForwardInput`
  (`:1486-1570`), and in `run()` assign `min(resolved, carried)` when `carriedRun`. Then
  regenerate the Python model (`contract/python/regen.sh`), keep the field out of `required`, and
  `mvn -q -pl contract/java -am install`. Phase 1's guard forces this classification.
- `processTick` (`:2804-2838`): capture the *previous* `lastTickAt` **before** `:2810` overwrites
  it. If `gapGraceVersion ≥ 1`, the previous tick is on an earlier ET date than
  `tick.getRetrievedAt()`, and the tick crosses the threshold: set `gapGracePending = true` instead
  of latching `chandelierFireRequested`. Ticks are already bid-space here under
  `trailOnBidVersion ≥ 1` (`:2058`). Because `lastTickAt` is carried, detection survives a roll.
- Main loop: when `gapGracePending` latches, start a `Workflow.newTimer(GAP_GRACE)` (a new command,
  gated). Add `gapGraceTimerFired` to the await predicate (`:1885-1934`). On wake, latch the fire
  if `lastTickPremium ≤ threshold`; otherwise clear and resume. Optional operator sub-fork: a hard
  backstop `GAP_HARD_STOP_PCT`, under which a tick fires immediately even inside the grace window.
- `rollBarrierHolds()` (`:1452-1478`): add `&& !gapGracePending`. A timer does not survive
  continue-as-new, the same reasoning as `partialPlaceRetryPending` (`:1429-1437`).
- `GAP_GRACE` and `GAP_HARD_STOP_PCT` are hard constants (precedent: `RISK_BREACH_EXEMPT_DTE_DAYS`,
  `:897`). A StrategyConfig field would regenerate three artifacts for a value nobody has tuned yet.
- New audit kinds `ChandelierGapGraceStarted` / `ChandelierGapGraceCleared` must be registered in
  `ALL_KINDS`. They are informational, so they do not page.

**Tests (TDD):**
- `PositionWorkflowImplTest` (or a new `…GapGraceTest`):
  - `gapOpenCross_waitsGrace_thenFiresIfStillBelow`: incident reproduction, SPY 08-19 shape:
    prior tick 2026-08-18 15:59 bid 1.79, next tick 2026-08-19 09:30 bid 1.065 → no `placeOrder` until
    the timer, then one flatten.
  - `gapOpenCross_recoversAboveThreshold_noFire`
  - `intradayCross_firesImmediately` (no regression on the 508 non-gap fires)
  - `hardBackstop_firesInsideGrace` (if the sub-fork is taken)
- `PositionWorkflowImplContinueAsNewTest`:
  - `gapGracePending_blocksRoll`
  - `carriedRun_keepsOriginalGapGraceVersion` (pre-gate run rolls → still fires immediately on a gap)
- `PositionWorkflowImplLegacyReplayTest` + the Phase 2 carried fixture: both stay green, and both
  are falsified by moving the new marker above `trailOnBidVersion`.

**Verify:**
```sh
mvn -q -pl contract/java -am install
mvn -q -pl services/orchestrator -am spotless:apply
mvn -q -pl services/orchestrator -am test     # full module; the flake list in §6 applies
mvn -q -pl services/audit -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='KindRegistryGuardTest'
mvn -q -pl services/orchestrator -am spotless:check
git diff --stat main -- contract/schemas/position-workflow-input.json   # additions only
```
**Pre-deploy gate:** §4.4.

---

## 6. Known flakes (instruct re-run, do not fix inside these phases)

- `KillSwitchWorkflowImplTest`: known flake, re-run.
- `PositionWorkflowImplTest`: 8/10 of measured CI re-runs (memory, #805).
- `PositionWorkflowImplContinueAsNewTest.exitInFlight_blocksRoll_untilDrained` (`:473`): **reported
  flaky, not reproduced in CI logs** (0 of the last 80 failed runs). A plausible mechanism, untested:
  the test's exact-count wait `waitForPlaceOrderCount(1)` (`:315-326`, `times(1)`) can be overtaken
  if the default exit-fill TTL elapses under time-skipping and a `:retry` `placeOrder` makes the
  count 2. Then `times(1)` never matches and the 50 s deadline fails. Do **not** fix it speculatively.
  If it recurs, capture the surefire report and run it 30× locally
  (`for i in $(seq 30); do mvn -q -pl services/orchestrator -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='PositionWorkflowImplContinueAsNewTest#exitInFlight_blocksRoll_untilDrained' || break; done`).
  If the mechanism is confirmed, the fix is `waitForPlaceOrderAtLeast(1)` (`:302`), as #798 did
  with its delta assertion. That would be its own test-only PR.

## 7. Ship order & gating

1. **Phase 1** (classification guard): ships first. It changes no runtime behaviour, and it is the
   prerequisite that makes Phase 5's carried-run semantics a forced decision rather than a silent default.
2. **Phase 2** (carried-run fixture): must merge before the first natural prod roll (gauge page
   at 6,000 gives ≥ ~20 trading days of warning).
3. **Phase 3** (real-server proof + verification doc): closes #752 under close criterion (a).
4. **Phase 4** (overnight notice, Fork N): independent of 1-3 and can ship in parallel with 2/3.
   It runs outside Temporal and is read-only. It is ordered after Phase 1 only because it has a
   (small) live-paging blast radius.
5. **Phase 5** (Fork G): **only if chosen**, and last. It is the only phase that changes command
   shape on the trading-critical workflow. It requires Phases 1 and 2 merged.

Every phase: TDD; `spotless:apply` on every touched module; its own PR; `Closes #` only where noted;
PR bodies set at create time (`gh pr edit --body` is broken here, so use
`gh api -X PATCH repos/<owner>/<repo>/pulls/<n>`); never touch `.github/workflows/*.yml`.
No phase touches `tenants/dev/*` (no ConfigMap concern; the tenants ConfigMap is retired anyway) or
live-tenant YAML. Trading-critical / operator merge: **Phase 1** (javadoc in the trading-critical
file), **Phase 5**. Operator ack: **Phase 4** (pages by default).
