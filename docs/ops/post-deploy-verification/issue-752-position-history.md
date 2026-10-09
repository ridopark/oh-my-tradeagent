# PositionWorkflow continue-as-new: verification (issue #752)

Closes https://github.com/ridopark/oh-my-tradeagent/issues/752 under a
**Deploy-Verified waiver**: no `PositionWorkflow` has ever rolled in production,
so Part A's real-server evidence stands in for a natural prod roll. Part B is the
checklist to run when the first natural roll happens.

## Back-references

- PR #794: continue-as-new for `PositionWorkflow` (watermark 10,000, quiet-position
  barrier, carried input). Hardened by #826/#838, #837/#841 and #840/#843.
- Plans: `docs/plans/PLAN-2026-08-22-position-continue-as-new.md` (original),
  `docs/plans/PLAN-2026-10-08-positionworkflow-continueasnew-gap.md` (this follow-up).
- PR #940 (Phase 1): build-failing field and version-gate classification for the roll.
- PR #948 (Phase 2): carried-run replay fixture, falsified both ways.
- Phase 3 (this doc's PR): `PositionWorkflowContinueAsNewRealServerIT`.
- Issue #958: carried-run STC dedupe gap found by Part A, check A4.
- Precedent: `issue-127-killswitch-history.md` (KillSwitchWorkflow, the same waiver shape).

---

## Production state at close (2026-10-08, read-only)

**Command** (homelab, `temporal-admintools`, namespace `copytrade`):

```sh
temporal workflow count --query "WorkflowType='PositionWorkflow' AND ExecutionStatus='ContinuedAsNew'"
temporal workflow count --query "WorkflowType='PositionWorkflow' AND ExecutionStatus='Running'"
# then, per running id: workflow describe (historyLength) + workflow query --type trailingState (armed)
```

**Actual:**

```
ContinuedAsNew: Total: 0
Running:        Total: 16
max historyLength 195 (GOOGL 261016C00360000 × 3 tenants), next 130, 115, 107 ...
trailingState.armed = False on all 16
```

**Verdict:** no natural roll has happened and none is close. The watermark is
10,000, and `PositionHistoryLengthGauge` pages at 6,000. That page is the trigger
for Part B.

---

## Part A: real-server evidence (Phase 3)

**Server:** Temporal CLI 1.7.0 dev server bundling **Temporal Server 1.31.0**,
the same server version as homelab. Started with:

```sh
temporal server start-dev --headless --port 17233 --ip 127.0.0.1 \
  --search-attribute TenantStrategy=Keyword --search-attribute ContractSymbol=Keyword
```

**Command:**

```sh
mvn -q -pl services/orchestrator -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='PositionWorkflowContinueAsNewRealServerIT' -Dtemporal.it.target=127.0.0.1:17233
```

Workers run in the test JVM (real `PositionWorkflowImpl`, mocked activities, and a
mock broker that fills every order ~300 ms after placement via an `onFill` signal
addressed by workflow id only). The watermark is lowered to 60 reflectively.
Every test uses a fresh tenant id, so the Visibility queries see only its workflow.
**Result: 8/8 pass on each of 4 consecutive full runs.** Without the property
the class is skipped, not failed (CI never runs it).

Two limits on what the IT enforces. The Visibility window figures are
measurements printed by the test, not asserted bounds. The "nothing more is
placed" checks after a redelivery are negative assertions over a 2-3 s wait, so
they are best-effort: a redelivery processed later than that would be missed.

### A1: search attributes on the carried run, and the Visibility window

**Expected:** the carried run carries `TenantStrategy` and `ContractSymbol`; any
period in which the position is invisible to the account-cap and tenant-delete
queries is measured.

**Actual (4 runs):**

```
server inherited TenantStrategy on the CAN start event: false        (4/4)
account-cap query    (AccountPnlActivitiesImpl form):  longestInvisibleMs = 1006 / 1010 / 1004 / 1068
tenant-delete query  (OpenPositionWorkflowChecker form): longestInvisibleMs = 1006 / 1010 / 1004 / 1068
id-only control      (WorkflowId=… AND Running):        longestInvisibleMs = 0 (4/4)
describe after the roll: TenantStrategy + ContractSymbol present        (4/4)
```

**Verdict:** PASS, with a measured residual window.

- Server 1.31 does **not** inherit search attributes across continue-as-new; the
  SDK 1.27 finding from the in-memory environment holds on a real server.
- The carried-run upsert in `run()` restores both attributes.
- For about 1 s per roll on this machine, the position is invisible to
  every `TenantStrategy`-filtered consumer. The id-only control never loses the
  workflow, so the gap is the missing attribute, not Visibility lag.
- The window lasts until the upsert, which runs after three calendar activity
  round trips. It is unbounded if no orchestrator worker is polling when the roll
  commits, for example during a pod `Recreate`.
- **Split by the id-only control:** 0 ms of the 1004-1068 ms is Visibility lag;
  all of it is the missing `TenantStrategy` attribute. So this is not an accepted
  indexing artifact; it is a real window per roll.
- **Worst-case cap-blind window:** from the continue-as-new commit until the
  carried run's upsert workflow task completes.
  - Normally: about 1 s (1068 ms max observed here), with a floor of the 3
    calendar activity round trips that precede the upsert.
  - With no orchestrator worker polling (the roll coincides with an orchestrator
    `Recreate` or outage): unbounded. It lasts until a new pod's worker picks up
    the task, so pod startup plus the 3 round trips, or longer if the pod does
    not come up.
- **Consequences during the window:**
  - The account loss cap under-counts that position's open loss for any
    evaluation that falls in the window (fails open).
  - The tenant-delete guard reads "no open position" for that strategy.
  - Both effects are bounded by the roll's rarity (none in prod to date).
- **Follow-up if wanted:** move the carried-run upsert to the top of `run()`. That
  reorders commands on carried histories, so it needs a version gate (the Phase 2
  fixture will fail without one).

### A2: trail and position state identical across the roll

**Expected:** `trailingState` armed/peak/threshold and all five `positionState`
fields equal before and after; a tick at the threshold then fires the flatten.

**Actual:** equal in 4/4 runs (peak 3.00, threshold 2.40, remaining 5, same
`entryAt`). The 2.40 tick placed `SELL 5`, and the position closed on the fill.

**Verdict:** PASS.

### A3: Update racing the roll is never lost

**Expected:** `partial_close` (fraction 0.5) and `disarm_trail`, sent concurrently
with a tick burst that crosses the watermark, both return success. Their effects
are on the final run, and each audit row exists exactly once. If the server
aborted an admitted Update across continue-as-new, the plan says to stop and
escalate.

**Actual:** parameterised at 12, 4 and 0 events short of the watermark, in 4 runs
(12 races):

```
ACCEPTED / DISARMED        12/12
final run: remainingQty 6→3, armed=false   12/12
OperatorTrimRequested ×1, TrailDisarmed ×1 (distinct event_id)   12/12
roll committed before the Updates returned: 8 of 12 races (every 12-short and 0-short race)
```

**Verdict:** PASS. No Update was aborted or lost, including when the roll
committed before the Updates returned. The barrier
(`isEveryHandlerFinished` plus the empty buffered-directive deques) holds the
roll on a real server.

### A4: Signal racing the roll is processed once

**Expected:** an STC sent in the burst that crosses the watermark places exactly
one order. A redelivery after the roll is deduped by the carried
`processedSignalIds`.

**Actual:**

```
STC in the boundary burst → exactly 1 placement                           (8/8)
redelivery once the carried run has run() (after its upsert) → deduped     (8/8)
redelivery in the carried run's FIRST workflow task → 2 placements, SAME intent key
    "<wf>:exit:sig-race qty=3", "<wf>:exit:sig-race qty=2"                 (3 of 4 runs hit the window)
```

**Verdict:** PASS for the boundary race. **Known gap #958** for the first-task
redelivery:

- `partialExit`'s `input == null` buffer branch skips the dedupe check, so the
  carried run re-processes the STC.
- It is bounded: the re-placement reuses the same intent key, which the exec
  journal (`ON CONFLICT (intent_key) DO NOTHING`) and the Alpaca `client_order_id`
  treat as the existing order. So it is not a second sell.
- It still produces a second `PartialExitRequested` audit and an exit-in-flight
  until the TTL cancels and reconciles.
- The IT pins the same-intent-key invariant
  (`realServer_redeliveryAtCarriedRunStart_neverPlacesUnderANewIntentKey`).
- Live exposure is zero until the first natural roll.

---

## Part B: natural-roll checklist (run on the first gauge page at 6,000)

Run on the first `PositionWorkflow` that rolls in prod. Record each check below in
this file with the same Command / Expected / Actual / Verdict shape.

| # | Check | Command (namespace `copytrade`) | Expected |
|---|---|---|---|
| B1 | No history-cap warnings | `kubectl -n copytrade logs deploy/orchestrator --since=24h \| grep -ci 'history count exceeds limit'` | `0` |
| B2 | History resets after the roll | `temporal workflow describe --workflow-id '<id>'` before and after | `historyLength` drops from >10,000 to a small number; a `ContinuedAsNew` run appears under `WorkflowId='<id>'` |
| B3 | Trail identical | `temporal workflow query --workflow-id '<id>' --type trailingState`, before and after | same `armed`, `peakPremium`, `thresholdPremium`; paste both verbatim |
| B4 | Position identical | `… --type positionState`, before and after | same `remainingQty` (and the other four fields) |
| B5 | Feed still live | `trailingState` twice, a few minutes apart, on the new run; the /live feed dot | `ticksReceived` advances from its carried base; dot not `NO FEED` |
| B6 | Search attributes | `temporal workflow describe --workflow-id '<id>'` (search attributes) and the account-cap query `WorkflowType='PositionWorkflow' AND TenantStrategy='t-<tenant>/s-<strategy>' AND ExecutionStatus='Running'` | both attributes present; the position is listed |
| B7 | No duplicate STC processing (#958) | `audit_log` rows `kind='PartialExitRequested'` for the workflow id, around the roll time | no signal id appears twice (only relevant until #958 ships) |

**Rollback note (from the 2026-08-22 plan, still true):** reverting #794 after
any roll is not safe on its own. The reverted code would take the first-fill
gate on a carried run and emit `PositionNeverFilled`. Terminate the carried run
and re-adopt under supervision instead.
