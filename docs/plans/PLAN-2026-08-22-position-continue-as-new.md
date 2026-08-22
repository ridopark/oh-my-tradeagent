# PLAN 2026-08-22 — PositionWorkflow continue-as-new (issue #752)

Closes https://github.com/ridopark/oh-my-tradeagent/issues/752

**Status:** plan only. No code changes are authorised by this document.

**Trading-critical.** Every phase touches, or observes, the file that runs live
real-money positions on accounts 847309116 (prod_real), 313392388 (staging /
kipark path) and 310056593 (prod-kipark). Read §"Replay contract" before
touching `PositionWorkflowImpl.java`.

---

## 1. Problem

`PositionWorkflowImpl` has no `continueAsNew`. Grep is unambiguous — the only
`continueAsNew` call sites in the orchestrator are
`KillSwitchWorkflowImpl.java:285`, `AccountKillSwitchWorkflowImpl.java:556` and
`WatchlistTriggerWorkflowImpl.java:465`. `PositionWorkflowImpl.java` has zero.

That never mattered while every position was short-dated. It matters now
because an operator can arm a trailing stop on any position, including a LEAP
(`armTrail`, `PositionWorkflow.java:160`, shipped in #689), and one such trail
is live on real money: `DRAM  270319C00100000` on prod_real, expiring
2027-03-19.

### 1.1 The fuse, measured

From the issue body (counted against the live Temporal history 2026-08-19,
cross-checked against the `trailingState` query's `ticksReceived` — both said
101, so both sources are trustworthy):

| quantity | value |
|---|---|
| history events at 2026-08-19 | 473 (started 2026-08-18T09:40:02Z) |
| `chandelierTick` signals | 101 |
| events per tick | ~4.7 |
| ticks per RTH day | 59, then 41 |
| events per RTH day | ~200 |

```
(10_000 - 473) / 200  ≈  48 trading days  ≈  late October 2026
```

The contract expires 2027-03-19. An armed trail held toward expiry crosses the
watermark roughly four months early.

### 1.2 Why ~4.7 events per tick is a floor that cannot be lowered in-workflow

`chandelierTick` buffers only (`PositionWorkflowImpl.java:1884-1892`) — the
handler appends to `pendingTicks` and returns. The main loop drains it through
`processTick` (`:2204-2221`), which ratchets `peakPremium` and latches
`chandelierFireRequested`. `processTick` emits **no** audit and schedules **no**
activity. So a non-firing tick costs exactly the Temporal-mandated quartet:
`WorkflowExecutionSignaled` + `WorkflowTaskScheduled` + `WorkflowTaskStarted` +
`WorkflowTaskCompleted`.

There is no in-workflow saving available. The only levers are *fewer signals*
(option A below) or *a shorter history* (option B).

### 1.3 Why the throttle cannot absorb this

`premium-emit-delta-pct` (1%, #690) discards ~99.9% of polls: ~46,800 polls per
RTH day become ~50 emitted ticks. The throttle and the history ceiling are
therefore the same knob — any request to make the stop react faster multiplies
history growth on a workflow that cannot roll. Today that knob cannot be turned
at all.

Note also `SubscribePremiumActivityImpl.java:238-241`: the dedup REUSE path
deliberately does **not** reset the emit baseline, with the comment "#776
recovery re-subscribes the whole armed book at once — that is a burst into
workflows with no continue-as-new". The absence of this fix is already load
bearing elsewhere in the estate.

### 1.4 Failure mode

Not a clean failure. Replay cost on every worker restart grows; then the
execution approaches Temporal's hard limits with an open real-money lot and an
armed stop attached. The blast radius is a position that cannot be exited
through its own workflow.

---

## 2. What the ecosystem does and does not care about the run id

Continue-as-new preserves the **workflow id** and mints a new **run id**. Every
consumer of a `PositionWorkflow` was checked. All of them key on workflow id
alone, so all of them survive the roll:

| consumer | evidence | verdict |
|---|---|---|
| premium tick fan-out | `SubscribePremiumActivityImpl.java:261` — `newUntypedWorkflowStub(posWfId)`, no run id | signals route to the latest run. Survives. |
| in-process subscription registry | dedup key is `(occSymbol, positionWorkflowId)`, `SubscribePremiumActivityImpl.java:102-104`; `throttles` keyed on `posWfId` (`:290`) | untouched by the roll. The subscription and its emit baseline both survive, so no re-subscribe and **no tick burst** on roll. |
| #784 RTH recovery sweep | `PremiumSubscriptionRecovery.java:68-69` — `WorkflowType = 'PositionWorkflow' AND ExecutionStatus = 'Running'`; then `newUntypedWorkflowStub(workflowId)` at `:291` | the new run is Running, the old is ContinuedAsNew. Found exactly once. Survives. |
| recon / STC running-probe | `PositionLookupActivitiesImpl.java:176-185` — `DescribeWorkflowExecution` with **workflow id only** (`:179`) | returns the latest run → RUNNING. No false orphan, no double adoption. Survives. |
| STC dispatch lookup | `PositionLookupActivities.java:21,42` — returns a workflow id; Redis pointer + `ContractSymbol` Visibility | survives **iff** search attributes carry across the roll. See §6.2 — this is the one item that must be proven, not assumed. |
| floor-breach alert loop | `FloorBreachAlertLoop.java:183` — `newUntypedWorkflowStub(wfId).query("positionState", …)` | survives. |
| dashboard `/live` buttons | Updates addressed by workflow id (`force_close`, `partial_close`, `arm_trail`) | survive. |

`PositionsController.java:266` exposes `run_id` in an API response. It is
display-only; nothing routes on it.

**Conclusion:** the subscription problem the issue flagged as needing proof is
in fact a non-problem, and the reason is structural rather than lucky — #776
already moved the registry key to `(occ, workflowId)` and #784 already made
recovery workflow-id-driven. The genuinely open item is search attributes.

---

## 3. Options considered

### Option A — reduce signal volume into the workflow

**A1. Raise `premium-emit-delta-pct` for long-dated contracts.**
Buys a linear factor. Rejected: it loosens a live real-money stop's resolution
to buy a history budget, which is the trade the issue explicitly says must not
be made, and it does not remove the fuse — a volatile month blows through a
wider band just as a quiet month stretches a narrow one.

**A2. Move trail evaluation out of the workflow.** market-data would hold
`peakPremium` + `givebackPct` locally and signal only on a new high or a
breach, collapsing ~50 ticks/day to a handful. Real volume win, and technically
reachable (`exitProximity` already returns `peakPremium`, so a re-subscribe
could re-seed it).

Rejected, and this is the most important rejection in the plan. It relocates
the single most safety-critical number in the system — the peak that anchors a
real-money stop — from a durable Temporal history into the in-process registry
that #717 proved is lost on **any** market-data restart and that #784 only
partially mitigates. A stale or unseeded peak re-anchors the trail at the
current premium, which loosens a live stop without telling anyone. And after
all that, the fuse is still there: history still grows from exits, timers and
audits, just more slowly. More risk, for a constant factor, on the wrong side
of a durability boundary.

**A3. Conflate ticks at the workflow boundary.** Not possible. Every signal is
an event by construction; a signal cannot be dropped after the server has
recorded it.

### Option B — continue-as-new at a safe barrier

Removes the fuse structurally. The cost is a carry-forward input that must
round-trip ~15 scalars and two small collections, on the most trading-critical
file in the repo.

That cost is much smaller than it first appears, because the *barrier* does the
work. If the roll is only permitted when the position is genuinely quiet — no
exit in flight, every pending deque empty, no unresolved fill, no latched
fire, no watchlist exit armed — then most of the workflow's state is provably
at its zero value at the moment of the roll and does not need to be carried at
all. See §5.2.

### Option C — alert before the watermark, no roll

A `@Scheduled` gauge over running `PositionWorkflow` history lengths plus a
Discord page. `KillSwitchHistoryLengthGauge.java` is a working template for
exactly this (`describeWorkflowExecution` → `getHistoryLength()` at `:100`),
and its class javadoc already explains why the read must be out-of-band: "the
gauge cannot live inside the workflow body because `Workflow.getInfo()` is
workflow-only API; emitting a metric activity from inside the workflow would
itself add to the very history we are trying to bound."

Zero replay risk. Ships in one small PR. But it fixes nothing on its own — it
converts a silent degradation into a paged one whose only remedy is terminate +
re-adopt, and per the recon double-adoption history (#432-435) and the
adoption-mints-a-new-workflow-id trap (#718), manual re-adoption of a live
armed position is itself a hazardous operation.

### 3.1 Comparison

| | removes the fuse | risk to live positions | protects the DRAM position now | ships in |
|---|---|---|---|---|
| A1 wider emit band | no | **loosens a live stop** | no | 1 PR |
| A2 trail in market-data | no | **peak leaves durable storage** | no | 3-4 PRs |
| B continue-as-new | **yes** | contained by the barrier + version discipline | yes, once deployed | 2 PRs |
| C alert only | no | none | **yes, immediately** | 1 PR |

---

## 4. Recommendation

**Ship C, then B. Reject A entirely.**

C first is not a hedge, and not merely "buying time". It is a hard prerequisite
for B, for a reason that is easy to miss:

The watermark check is a plain state read and needs **no** `Workflow.getVersion`
gate — both precedents omit one (`KillSwitchWorkflowImpl.java:284`,
`AccountKillSwitchWorkflowImpl.java:555`), because on every history where the
branch is not taken it emits no command at all. That omission is what lets the
fix protect executions that are *already in flight*, including DRAM. A
version-gated check would resolve to `DEFAULT_VERSION` on DRAM's replay and
cache that for the life of the run, and DRAM would never roll — the fix would
not save the position that motivated the issue.

But the un-gated check has a precondition. `Workflow.getInfo().getHistoryLength()`
grows *incrementally during replay*. If a position is **already above the
watermark** when the new code deploys, then on the next worker restart it
replays, crosses 10,000 mid-replay, and emits a `ContinueAsNewWorkflowExecution`
command at a point where the recorded history continues with other events. That
is a non-determinism error, which fails the workflow task and retries forever —
a wedged live position with buffered, unprocessed exits. Fail-loud, but a wedge.

So B is safe **only while every live position is comfortably below 10,000 at
deploy time**, and C is the instrument that proves it. Hence the ordering, and
hence the hard pre-deploy gate in Phase 2.

C also remains valuable permanently, not just as scaffolding: a position that
never reaches the barrier (pathologically busy, or a watchlist-exit position
excluded by §5.2) still needs a page.

---

## 5. Design

### 5.1 Replay contract (read before touching the file)

`PositionWorkflowImpl` carries ~30 `Workflow.getVersion` change-ids
(`:254-762`). Marker **order** is part of the replay contract. The rules this
plan operates under:

1. The watermark check adds no command on the not-taken path, so it needs no
   gate. Do not add one — a gate would exclude every in-flight position,
   defeating the purpose (§4).
2. `@WorkflowInit` emits no commands. Adding one is replay-neutral.
3. Everything the *new run* does is fresh history. There is no replay
   constraint on the carry-forward hydration path at all — its only constraints
   are correctness ones.
4. A new run resolves every `getVersion` to the maximum version, because its
   history is empty. This is safe here precisely because it is the *new* run;
   it does not retro-change the old one. It is also why hydration must bypass
   the first-fill gate (§5.3).

### 5.2 The barrier

Roll only at the top of the main loop (`:1363`), and only when **all** hold:

*Position is live and confirmed*
- `remainingQty > 0`, `positionConfirmed`

*Nothing in flight*
- `!exitInFlight` (`:917`), `lastFillEvent == null` (`:919`),
  `!flattenAwaitingLateFill` (`:953`), `!partialPlaceRetryArmed` (`:1006`)

*Every queue empty*
- `pendingExits`, `pendingArms`, `pendingTicks`, `pendingRiskBreaches`,
  `pendingForceCloses`, `pendingPartialCloses`, `pendingSupersedes`
  (`:918, :1064, :1070, :1088, :1089, :1110, :1098`)

*Nothing latched*
- `!chandelierFireRequested` (`:1073`), `!exitStopFireRequested` (`:1025`),
  `!exitTimeStopFired` (`:1026`), `!exitFeedStaleFired` (`:1027`),
  `!eodFired && !expiryFired && !expiryLeadFired` (`:937, :938, :945`),
  `closeReason == null` (`:1082`)

*Not a watchlist-exit position*
- `input.getTpRatio() == null`

The queue-empty conjunct is what makes the roll safe against the classic
continue-as-new signal-loss race: a signal that arrived but has not yet been
drained keeps the barrier shut, so no buffered directive is ever discarded at
the run boundary.

The `tp_ratio == null` conjunct is a deliberate scope cut. A watchlist-exit
position carries `exitStopLevel` / `exitTargetLevel` / `exitTargetFired` /
`exitSubThresholdStreak` / `exitBidMfe` / `exitBidMae` / `exitFirstFillAt`
(`:1017-1041`) plus two timers armed relative to first fill inside
`armWatchlistExit` (`:2588-2611`) — a materially larger and more fragile carry
surface. It is also unnecessary: those strategies run a 25-45 minute
`no_progress_time_stop` and a `force_close_eod_et`, so they cannot approach the
watermark. Excluding them removes roughly half the carry-forward for zero loss
of coverage. Phase 1's alert is the backstop if that assumption is ever wrong.

### 5.3 Carry-forward

Add to `contract/schemas/position-workflow-input.json` and hydrate in a new
`@WorkflowInit` constructor, mirroring `AccountKillSwitchWorkflowImpl.java:484`.

| field | current line | why it must survive |
|---|---|---|
| `carried_remaining_qty` | `:891` | the new run must **not** await a first fill. Without this the new run takes the `deferVersion >= 1` path at `:1284-1295`, times out, emits `PositionNeverFilled` and **returns — abandoning a live lot.** This is the single most dangerous failure mode of a naive port. |
| `carried_entry_at` | `:1130` | F1 supersede correction-window guardrail; `positionState` (`:2855`) |
| `carried_partial_exited` | `:1138` | F1 guardrail; `positionState` |
| `carried_trailing_armed` | `:1044` | otherwise the stop silently disarms across the roll |
| `carried_peak_premium` | `:1045` | **the dangerous one.** A reset re-anchors the trail at the current premium — loosening a live stop with no signal to anyone. Round-trip must be asserted by a dedicated test. |
| `carried_giveback_pct` | `:1046` | threshold = `peak * (1 - giveback)` (`:2216`) |
| `carried_ticks_received` | `:1047` | operator-visible in `trailingState` (`:2843`); a reset reads as "the feed died" |
| `carried_last_tick_premium` / `_at` | `:1048, :1049` | `trailingState` / `exitProximity` staleness display |
| `carried_entry_broker_order_id` | `:926` | #738: distinguishes an entry fill from an exit fill. Losing it lets a late entry-side report be booked as an exit. |
| `carried_processed_signal_ids` | `:916` | STC dedupe. Losing it lets a redelivered STC **place a second sell order**. |
| `carried_exit_booked_by_order` | `:986` | #735 cumulative ledger; suppresses a late duplicate broker report. Bounded at 64 (`:789, :4315`). |
| `carried_flatten_retry_sessions` | `:964` | bounded retry budget (`MAX_FLATTEN_RETRY_SESSIONS`, `:652`); a reset grants extra attempts |
| `carried_partial_place_retry_sessions` / `_attempts` | `:1010, :1012` | same, and the attempt counter feeds the `:retry-N` intent-key suffix (`:1531-1541`) — a reset would mint a **duplicate `client_order_id`** |

Not carried, because the barrier proves them zero: every pending deque, every
latch, `exitInFlight`, `lastFillEvent`, `closeReason`, `fireTriggerTick`,
`fireThreshold`, `flattenBookedKey`/`Qty`, and the whole watchlist-exit block.
Each exclusion is justified by a specific barrier conjunct in §5.2; the phase
must assert that link in a test, not just in a comment.

Timers are **not** carried and must not be: `eodTimer` / `expiryTimer` /
`flattenLeadTimer` (`:1230, :1239, :1266`) are derived purely from the OCC
expiry plus config, so the new run recomputes them correctly from the current
clock. This is a property worth stating explicitly in the code, because it
looks like an omission.

### 5.4 Rolling-deploy safety

`infra/k8s/51-orchestrator.yaml` is `replicas: 1` (`:26`) with **no**
`strategy:` block, so it defaults to RollingUpdate and `maxSurge: 25%` rounds up
to 1 — two orchestrator pods run briefly during a roll. An old pod handed a
carry-forward input containing fields it does not know may fail to deserialize.

`AccountKillSwitchWorkflowImpl.carryForwardInput` (`:677-700`) solves this with
conditional `schema_version` stamping. That discipline is real but intricate,
and here there is a simpler answer with direct in-repo precedent: set
`strategy: type: Recreate` on the orchestrator Deployment, exactly as
`53-market-data.yaml:55`, `52-exec-alpaca-paper.yaml:49` and
`52b-exec-alpaca-live.yaml:54` already do for the same `replicas: 1` /
`maxSurge` reason (#741). No overlap, no cross-version deserialization, no
conditional-stamping logic to get wrong. Workflows are durable, so the brief
worker gap costs nothing.

Orchestrator lacking `Recreate` while its three siblings have it is arguably a
standing gap; this plan closes it as a scoped one-line prerequisite rather than
as a drive-by.

---

## 6. Phases

Each phase is one independently shippable PR.

### Phase 1 — observe and alert (no workflow change)

**Scope.** A Prometheus gauge + Discord page for `PositionWorkflow` history
length. No change to any workflow body.

**Files**
- new `services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/metrics/PositionHistoryLengthGauge.java`
- new test alongside it
- `infra/k8s/` dashboards/alerts only if the estate already declares them there

**Design.** Copy `KillSwitchHistoryLengthGauge` structurally
(`@Component`, `@Profile("!test")`, `@Scheduled`, `DescribeWorkflowExecution`,
`NOT_FOUND_VALUE = -1`). Two differences: enumerate via the Visibility list
query rather than the tenants dir — reuse the exact string from
`PremiumSubscriptionRecovery.java:68-69`, `WorkflowType = 'PositionWorkflow'
AND ExecutionStatus = 'Running'` — and page once per workflow when the length
crosses a warn level. Tag `workflow_type`, `tenant_id`, `strategy_id`,
`contract_symbol`.

Warn at **6,000**, not 9,000: at ~200 events/RTH day that is ~20 trading days
of runway, which is enough to plan a supervised intervention rather than react
to one. Cadence 5 minutes (not the kill-switch gauge's 60s — nothing here moves
that fast, and the estate has one Temporal frontend).

Re-register gauges as the running set changes, and de-register on close, or the
meter registry leaks one gauge per closed position for the pod's life. This is
the one place Phase 1 differs materially from its template, which iterates a
fixed tenant list.

**Replay analysis.** None required. No workflow code is touched. State this
explicitly in the PR body so the trading-critical review gate can be satisfied
quickly.

**Success criteria** (verbatim)

```sh
cd /home/ridopark/src/oh-my-tradeagent
mvn -q -pl services/orchestrator -am spotless:apply
mvn -q -pl services/orchestrator -am test -Dtest=PositionHistoryLengthGaugeTest
```
Expect: BUILD SUCCESS, tests run > 0, failures 0.

```sh
mvn -q -pl services/orchestrator -am spotless:check
```
Expect: BUILD SUCCESS (no diff). Run `spotless:apply` on every touched module
before committing — the impl environment skips it and CI fails otherwise.

Post-deploy, on the homelab:
```sh
kubectl -n copytrade exec deploy/orchestrator -- \
  curl -s localhost:8080/actuator/prometheus \
  | grep 'temporal_workflow_history_length.*PositionWorkflow'
```
Expect: at least one series, tagged with the DRAM contract, value > 0 and well
below 6000. **Record the DRAM value — it is the pre-deploy gate for Phase 2.**

**Rollback.** Delete the bean. Nothing depends on it.

---

### Phase 2 — continue-as-new at the barrier

**Scope.** The watermark check, the barrier predicate, the carry-forward input,
`@WorkflowInit` hydration, the `Recreate` strategy.

**Files**
- `services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/workflows/PositionWorkflowImpl.java`
- `contract/schemas/position-workflow-input.json`
- `infra/k8s/51-orchestrator.yaml`
- `services/orchestrator/src/test/java/com/ohmytradeagent/orchestrator/workflows/PositionWorkflowImplContinueAsNewTest.java` (new)
- `services/orchestrator/src/test/java/com/ohmytradeagent/orchestrator/workflows/PositionWorkflowImplLegacyReplayTest.java` (extend)

**Shape.**
```java
static long historyLengthWatermark = 10_000L;   // package-private, non-final, test-lowerable
                                                // — mirrors KillSwitchWorkflowImpl:190
```
At the top of the main loop (`:1363`), before `Workflow.await`: if
`Workflow.getInfo().getHistoryLength() > historyLengthWatermark` **and** the
§5.2 barrier holds, `Workflow.continueAsNew(buildCarryForwardInput())`.
`continueAsNew` throws `DestroyWorkflowThreadError`; nothing below it is
reachable, and it must not sit inside a `catch (RuntimeException)` — see the
comment at `KillSwitchWorkflowImpl.java:280-287`.

**Replay analysis.**
- The watermark check emits no command when not taken. No `getVersion` gate, by
  design (§4). Both precedents omit one.
- `@WorkflowInit` emits no commands.
- New schema fields are additive and optional; absent on every existing input.
- Because the check is un-gated, in-flight executions *can* roll — that is the
  point. The hazard this creates is the already-above-watermark replay
  divergence in §4, handled by the pre-deploy gate below.
- Every `getVersion` in the new run resolves to max. Hydration bypasses the
  first-fill gate, which is the only place that difference bites (§5.3).

**Tests.** Lower `historyLengthWatermark` reflectively (the precedent's stated
reason for keeping the field package-private and non-final) and assert:

1. **`peakPremium` round-trips.** Arm a trail, ratchet the peak above the arm
   anchor, force a roll, then assert the new run's `trailingState()` reports the
   **same** `peakPremium` and the **same** `thresholdPremium`. Then feed a tick
   below the carried threshold and assert it fires. A test that only checks the
   field is equal is not enough — the stop must still *fire at the same price*.
2. **Every carried field round-trips.** One assertion per row of the §5.3 table.
3. **The barrier holds shut.** For each barrier conjunct, construct the busy
   state (exit in flight, non-empty deque, latched fire, `tp_ratio != null`),
   push history past the watermark, and assert **no** roll occurs and the
   pending work still drains correctly. This is the test that proves the §5.3
   exclusions are safe.
4. **No first-fill gate on the carried run.** Assert the new run does not emit
   `PositionNeverFilled` and does not return, with no `onFill` ever delivered.
5. **Legacy replay is unbroken.** Extend `PositionWorkflowImplLegacyReplayTest`
   against `position-pre-276-legacy-history.json` — no
   `NonDeterministicWorkflowError`. ⚠ Per the repo's own history, a replay
   fixture here can be toothless: confirm the fixture **fails** against a
   deliberately broken build (e.g. move the watermark check above a
   `getVersion` marker) before trusting a green run. A fixture that passes both
   ways proves nothing.
6. **Search attributes carry.** See §6.2.

**Success criteria** (verbatim)

```sh
cd /home/ridopark/src/oh-my-tradeagent
mvn -q -pl contract/java -am install
mvn -q -pl services/orchestrator -am spotless:apply
mvn -q -pl services/orchestrator -am test \
  -Dtest='PositionWorkflowImplContinueAsNewTest+PositionWorkflowImplLegacyReplayTest'
```
Expect: BUILD SUCCESS, failures 0, errors 0.

```sh
mvn -q -pl services/orchestrator -am test
mvn -q -pl services/orchestrator -am spotless:check
```
Expect: BUILD SUCCESS both. The full-module run guards against a carry-forward
change breaking an unrelated PositionWorkflow test.

```sh
git diff --stat main -- contract/schemas/position-workflow-input.json
```
Expect: additions only. No property removed, no `required` entry added — a
removed schema field wedges in-flight workflows on replay (#649); deprecate in
place.

```sh
grep -n 'type: Recreate' infra/k8s/51-orchestrator.yaml
```
Expect: one hit.

**Pre-deploy gate (hard, blocking).**
```sh
kubectl exec -n temporal deploy/temporal-admintools -- \
  temporal --namespace copytrade --address temporal-frontend:7233 \
  workflow list --query "WorkflowType = 'PositionWorkflow' AND ExecutionStatus = 'Running'"
```
then, for each id returned:
```sh
kubectl exec -n temporal deploy/temporal-admintools -- \
  temporal --namespace copytrade --address temporal-frontend:7233 \
  workflow describe --workflow-id '<id>' | grep -i historyLength
```
**Every** running position must be below 10,000. If any is at or above it, do
**not** deploy — that execution will diverge on replay and wedge (§4). Remediate
that position first (supervised terminate + re-adopt, per the recon runbook)
and re-check. Phase 1's gauge should make this a formality; run it anyway.

**Post-deploy verification.** Write
`docs/ops/post-deploy-verification/issue-752-position-history.md` in the style of
`issue-127-killswitch-history.md` (same structure: Check / Command / Expected /
Actual / Verdict). At minimum:
1. No `history count exceeds limit` warnings in orchestrator logs, 24h.
2. `HistoryLength` on the DRAM position resets after the first roll.
3. `trailingState` reports the **same** `peakPremium` and `thresholdPremium`
   either side of the roll. Capture both, verbatim.
4. `positionState` reports the same `remainingQty` either side.
5. The premium subscription is still live after the roll — ticks continue to
   arrive (`ticksReceived` advances on the new run, from its carried base).
6. Search attributes survive (§6.2).

Because the first real roll on DRAM will not happen until the history crosses
10,000 (forecast late October 2026), this verification will need a
Deploy-Verified waiver on the PR, exactly as PR #126 took for issue #127. Say
so in the PR body rather than leaving the checklist open.

**Rollback.** Revert the PR. Already-rolled executions keep running: the new run
is a normal `PositionWorkflow` whose input carries extra optional fields, which
the reverted code ignores — **except** that it would then take the first-fill
gate at `:1284` and emit `PositionNeverFilled`. So a revert after any roll has
occurred is **not** safe on its own. If a rollback is needed post-roll, the
carried run must be terminated and re-adopted under supervision. State this on
the PR; it is the phase's sharpest edge.

---

### 6.2 Search attributes — the one unproven item

`PositionWorkflow` is started as a child with
`ChildWorkflowOptions.setSearchAttributes(sa)` carrying `TenantStrategy` and
`ContractSymbol` (`CopytradeSignalWorkflowImpl.java:1256-1265`,
`AdoptionWorkflowImpl.java:160-168`). `ContractSymbol` is the Visibility key STC
dispatch falls back to on a Redis cache miss
(`PositionLookupActivities.java:34-40, :88, :108`).

Temporal is expected to carry search attributes across continue-as-new when the
command does not override them. **Do not ship on that expectation.** Prove it:

```java
// in PositionWorkflowImplContinueAsNewTest
DescribeWorkflowExecutionResponse after = /* describe the post-roll run */;
assertThat(after.getWorkflowExecutionInfo().getSearchAttributes()
    .getIndexedFieldsMap()).containsKeys("TenantStrategy", "ContractSymbol");
```
and again post-deploy:
```sh
kubectl exec -n temporal deploy/temporal-admintools -- \
  temporal --namespace copytrade --address temporal-frontend:7233 \
  workflow describe --workflow-id '<dram-id>' | grep -A5 -i 'search'
```
Expect: both attributes present, `ContractSymbol` equal to the padded OCC.

If they do **not** carry, the fix is to pass them explicitly:
`Workflow.continueAsNew(ContinueAsNewOptions.newBuilder().setSearchAttributes(sa).build(), input)`,
rebuilding `sa` from `input.getTenantId()` / `getStrategyId()` /
`getContractSymbol()`. Budget for this branch; do not discover it in production.

Losing `ContractSymbol` would not fail loudly. It would degrade STC dispatch to
the Redis pointer alone — which works until the cache misses, and then an
author's SELL silently finds no position. That is the failure this check exists
to prevent.

---

## 7. Non-goals

- **No disarm path for a trail.** `processArm` no-ops when `trailingArmed`
  (`:2225-2228`) and `armTrail` returns `ALREADY_ARMED` by design, so a
  double-click can never loosen a live stop. Adding an un-arm control is a
  separate decision with its own risk; out of scope.
- **No change to `premium-emit-delta-pct` or the poll cadence.** Option A is
  rejected (§3). This plan is the prerequisite that makes a future tightening
  of that band *discussable*; it does not tighten it.
- **No continue-as-new for watchlist-exit positions** (`tp_ratio != null`).
  Deliberately excluded by the barrier (§5.2). Phase 1's alert is the backstop.
- **No fix for #717.** A market-data restart still orphans armed trails; #784's
  RTH sweep is the mitigation. Independent failure, same subsystem — neither
  fix may assume the other.
- **No change to the 10,000 watermark for other workflows.**
- **No new operator control, dashboard field, or config knob.** The watermark
  stays a package-private constant, per the precedent's explicit KISS rationale
  (`KillSwitchWorkflowImpl.java:186-189`).

---

## 8. Risks

| risk | severity | mitigation |
|---|---|---|
| `peakPremium` resets across the roll, silently loosening a live stop | **critical** | dedicated round-trip test that asserts the stop still *fires at the same price*, not just that the field matches; post-deploy `trailingState` capture either side of the roll |
| new run awaits a first fill that never comes → `PositionNeverFilled` → live lot abandoned | **critical** | hydration bypasses the gate; test 4 asserts it with no `onFill` ever delivered |
| a position already above the watermark at deploy diverges on replay and wedges | **critical** | hard pre-deploy gate (Phase 2); Phase 1 ships first specifically to make this observable |
| `processedSignalIds` lost → redelivered STC places a second sell | high | carried; round-trip asserted |
| `partialPlaceRetryAttempts` lost → duplicate `client_order_id` on a retry | high | carried; round-trip asserted |
| `ContractSymbol` search attribute lost → STC silently misses on cache miss | high | §6.2, proven in test **and** post-deploy, with an explicit fallback |
| a signal buffered but undrained is discarded at the run boundary | high | the barrier requires every deque empty |
| replay fixture is toothless and green means nothing | medium | confirm the fixture fails against a deliberately broken build before trusting it |
| rollback after a roll re-introduces the first-fill gate on a carried run | medium | documented on the PR; post-roll rollback requires supervised terminate + re-adopt |
| old orchestrator pod cannot deserialize the new input mid-roll | medium | `strategy: Recreate` (§5.4), matching three sibling deployments |
| tick burst into the new run on roll | low | `throttles` is keyed on workflow id and is untouched by the roll (`SubscribePremiumActivityImpl.java:298`); the emit baseline survives |
| `ticksReceived` no longer equals the history event count after a roll | low (docs) | the issue's own measurement method relies on this identity; note it in the verification doc so a future investigator is not misled |
