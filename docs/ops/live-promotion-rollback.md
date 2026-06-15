# Runbook: live-broker promotion rollback

## When to use

A tenant + strategy has been promoted to a `*-live` broker adapter (per the Phase 7
promotion gate in `docs/plans/PLAN.md`) and you need to revert it to the paper variant.
Triggers include:

- A P0/P1 incident on the live adapter (any of the operational metrics in the Phase 7
  gate fall out of spec — discrepancy rate >= 0.1%, recon lag p99 >= 60s, audit gap, or
  unrecovered orphan).
- A scheduled **rollback drill** rehearsing the procedure before real promotion (this
  runbook must have a passing drill run logged within 30 days of any live promotion;
  see Phase 7 gate, criterion (h)).
- Operator-initiated revert during the shadow-live window if the window's metrics
  degrade before the N >= 20 trading days complete.

This runbook is itself a release artifact for the Phase 7 gate — the gate cannot clear
until this procedure has been drilled end-to-end and the drill evidence is on file.

## Symptoms (real rollback, not drill)

- Audit log shows a streak of `JournalOrphan` / `BrokerOrphan` events on the `*-live`
  adapter that recon did not auto-resolve within one full cycle.
- `/killswitch/state` shows `tripped: true` from an auto-trip on daily-loss threshold
  while the live adapter is active.
- Reconciliation latency p99 breaches the 60s Phase 7 ceiling for two consecutive
  5-min windows. See [`docs/ops/reconciliation-metrics.md`](reconciliation-metrics.md)
  for the PromQL query (`histogram_quantile(0.99, ... reconciliation_lag_seconds_bucket ...)`)
  and the audit-log SQL fallback that produces the same number when Prometheus isn't
  wired yet.
- Operator on-call escalation: live fill quality is materially worse than the shadow-live
  baseline (slippage > 2x the shadow-live observed median, or repeated PFOF-routing
  artifacts visible in fill prices).

## Pre-rollback: capture evidence

Before flipping `broker_target`, snapshot the state so post-mortem can reconstruct what
happened. The audit log retains this, but pulling it now is cheaper than querying
later.

```sh
ssh ridopark@192.168.10.123

# Snapshot kill-switch state and the most recent trip/reset events:
curl -s http://copytrade.homelab.local/killswitch/state | jq | tee /tmp/rollback-killswitch.json
curl -s 'http://copytrade.homelab.local/audit?tenant=<t>&strategy=<s>&kind=KillSwitchTripped&limit=10' \
  | jq | tee /tmp/rollback-killswitch-trips.json

# Snapshot in-flight live orders (anything still Submitted/Pending in the journal):
kubectl -n copytrade exec statefulset/postgres -- \
  psql -U "$(kubectl -n copytrade get secret postgres-credentials -o jsonpath='{.data.POSTGRES_USER}' | base64 -d)" \
       -d exec_<provider>_live \
       -c "SELECT intent_key, state, submitted_at, broker_order_id, occ FROM order_intent_journal WHERE state IN ('Submitted','Pending') ORDER BY submitted_at DESC LIMIT 50;" \
  | tee /tmp/rollback-inflight.txt

# Snapshot running PositionWorkflows on the live adapter:
kubectl -n temporal run --rm -it temporal-cli --restart=Never \
  --image=temporalio/admin-tools:1.29 -- \
  temporal workflow list --address temporal-frontend:7233 --namespace copytrade \
    --query "WorkflowType='PositionWorkflow' AND ExecutionStatus='Running' AND TenantStrategy='t-<t>/s-<s>'" \
  | tee /tmp/rollback-positions.txt

# Snapshot the most recent ReconciliationCompleted events:
curl -s 'http://copytrade.homelab.local/audit?tenant=<t>&strategy=<s>&kind=ReconciliationCompleted&limit=20' \
  | jq | tee /tmp/rollback-recon.json
```

If any of these reveal unresolved orphans, work through
`docs/ops/journal-broker-mismatch.md` **before** flipping `broker_target` — rolling back
while a live orphan is unresolved leaves a real-money order untracked by the paper
adapter.

## Path 1: stop the bleeding (trip the kill switch)

Before touching `broker_target`, trip the kill switch so no new BTOs route through the
live adapter while you're swapping config. This is cheap and reversible.

```sh
curl -X POST http://copytrade.homelab.local/killswitch/trip \
  -H 'Content-Type: application/json' \
  -d '{"reason":"rollback:live-promotion","actor":"operator:<your-handle>"}'

# Verify:
curl -s http://copytrade.homelab.local/killswitch/state | jq '.tripped'  # → true
```

New entries now reject with `killswitch_tripped` (audit-visible). Open positions are not
auto-closed by `trip_killswitch` alone — they continue under their existing exit logic
on the live adapter until you complete the rollback.

## Path 2: flip `broker_target` to the paper variant

The promotion mechanism was a per-tenant YAML edit setting `broker_target:
<provider>-live`. Rollback reverts that same key:

```sh
# Edit the tenant config in the cluster (Phase 6 puts these under tenants/<id>/):
kubectl -n copytrade edit configmap tenant-<t>-config

# Change:
#   broker_target: <provider>-live
# back to:
#   broker_target: <provider>-paper

# Apply takes effect on the next orchestrator-svc reload. Force a rolling restart so the
# change picks up immediately rather than waiting for the configured reload cadence:
kubectl -n copytrade rollout restart deployment/orchestrator-svc
kubectl -n copytrade rollout status  deployment/orchestrator-svc --timeout=120s
```

After the rollout, verify the next signal routes to the paper task queue:

```sh
# Inject a synthetic non-trading signal (or wait for a real one) and check the audit:
curl -s 'http://copytrade.homelab.local/audit?tenant=<t>&strategy=<s>&kind=OrderPlaced&limit=1' \
  | jq '.events[0].attributes.broker_target'
# → should be "<provider>-paper"
```

## Path 3: verify reconciliation catches in-flight live orders

The `*-live` adapter may have orders mid-flight when you flipped `broker_target` — the
new paper adapter does not know about them, but `ReconciliationWorkflow` still queries
the live broker because the journal entries remain bound to it via their original
`broker_target` column.

Wait one full reconciliation cycle (default 5 minutes; live cadence target is 60s per
PLAN.md Phase 6) and confirm:

```sh
# Latest ReconciliationCompleted should show zero discrepancies for the affected tenant:
curl -s 'http://copytrade.homelab.local/audit?tenant=<t>&strategy=<s>&kind=ReconciliationCompleted&limit=2' | jq

# Any orphan that surfaces is handled per docs/ops/journal-broker-mismatch.md.
# Specifically Path 3 (filled-but-not-acked) covers the case where the live broker
# fills an in-flight order after the rollback; reconciliation signals the matching
# PositionWorkflow which absorbs the fill into its state.
```

If recon surfaces an orphan that *cannot* be reconciled within one cycle (e.g. the live
broker is unreachable), do **not** declare the rollback complete — escalate to
`docs/ops/journal-broker-mismatch.md` and keep the kill switch tripped until the orphan
is resolved.

## Path 4: reset the kill switch (only after recon is clean)

Once recon shows zero discrepancies for two consecutive cycles and all in-flight live
orders have either filled-and-acked or been cancelled, reset the kill switch via the
dual-control flow (Phase 5; same dual-control as `reset_killswitch`):

```sh
curl -X POST http://copytrade.homelab.local/killswitch/reset \
  -H 'Content-Type: application/json' \
  -d '{
    "approver_id_1": "operator:alice",
    "approver_id_2": "operator:bob",
    "note": "rollback complete — broker_target reverted to <provider>-paper, recon clean"
  }'
```

Wait the reset cooldown (`reset_cooldown_secs`, default 60s) and verify a paper BTO
routes cleanly.

## Audit-log evidence to capture

The post-rollback audit query proves the rollback succeeded and is the artifact for the
incident review. Capture these into the incident ticket:

```sh
# 1. The trip event that started the rollback:
curl -s 'http://copytrade.homelab.local/audit?tenant=<t>&strategy=<s>&kind=KillSwitchTripped&limit=1' | jq '.events[0]'

# 2. The config flip. TenantConfigChangedEmitter (issue #88) emits exactly one
#    TenantConfigChanged event per changed (tenant, strategy) on the orchestrator-svc
#    boot that follows a configmap edit + rolling restart. Verify the subject identifies
#    the keys that changed and pins the trigger:
curl -s 'http://copytrade.homelab.local/audit?tenant=<t>&strategy=<s>&kind=TenantConfigChanged&limit=1' | jq '.events[0]'
# Expect:
#   .subject.changed_keys[]   # non-empty array of YAML keys that differ between snapshots
#   .subject.source           # == "configmap-reload"
#   .subject.old_values       # map of prior values (redacted-key entries omit the value)
#   .subject.new_values       # map of current values (same redaction rule)
#   .subject.loaded_at        # RFC3339 timestamp of the boot-time load that produced the diff

# 3. Recon-clean evidence (two consecutive ReconciliationCompleted with discrepancies: 0):
curl -s 'http://copytrade.homelab.local/audit?tenant=<t>&strategy=<s>&kind=ReconciliationCompleted&limit=2' | jq '.events[].attributes.discrepancies'
# → [0, 0]

# 4. The reset event with both approver IDs:
curl -s 'http://copytrade.homelab.local/audit?tenant=<t>&strategy=<s>&kind=KillSwitchResetApproved&limit=1' | jq '.events[0]'

# 5. First paper BTO post-rollback (confirms the system is back in service):
curl -s 'http://copytrade.homelab.local/audit?tenant=<t>&strategy=<s>&kind=OrderPlaced&limit=1' | jq '.events[0]'
```

Attach all five to the incident ticket and the rollback drill log (see next section).

## Drill procedure (exercise this BEFORE real promotion)

The Phase 7 gate requires a passing rollback drill within the last 30 days before
promotion. The drill rehearses the full procedure under shadow-live conditions, with
no real risk:

1. Operate the system in **shadow-live** (1 contract on the `*-live` adapter, per the
   Phase 7 shadow-live sub-phase) for at least one trading day.
2. At a quiet point in the session (mid-day, no in-flight orders), follow Path 1 through
   Path 4 of this runbook end-to-end:
   a. Capture pre-rollback evidence.
   b. Trip the kill switch.
   c. Flip `broker_target` from `<provider>-live` to `<provider>-paper`.
   d. Wait one recon cycle and verify zero discrepancies.
   e. Reset the kill switch via dual-control.
3. Inject a synthetic BTO via `scripts/harness/inject_synthetic_bto.py` (the same harness
   used in PLAN.md Phase 5b.E validation) and confirm it routes to the paper adapter.
4. Re-flip `broker_target` back to `<provider>-live` and confirm shadow-live resumes
   normally (this proves the rollback is reversible — promotion is not a one-way door
   in the drill).
5. Log the drill in [`docs/ops/drill-log.md`](drill-log.md) with the row format
   documented in that file (date, drill_type=`rollback`, tenant, strategy, adapter,
   operator, audit_refs, result). Copy the entry template from the
   "Entry template" section there. The Phase 7 freshness check
   ([`scripts/ops/check_drill_freshness.py`](../../scripts/ops/check_drill_freshness.py))
   reads this same log and rejects the promotion gate if either the kill-switch or
   the rollback drill type is missing a passing entry within 30 days for the target
   `<provider>-live` adapter.

Drill **passes** if and only if:

- Every audit event in the "Audit-log evidence to capture" section was successfully
  captured.
- The reset path used two distinct approver IDs (single-operator reset must be
  rejected — that's the dual-control guarantee, not a rollback failure).
- The post-rollback synthetic BTO routed to the paper adapter (no leak to live).
- Re-promotion in step 4 succeeded with no manual journal mutation required.

If any of these fail, fix the runbook (this file) before re-attempting the drill. A
runbook that doesn't survive its own drill is not a release artifact.

## Sign-off recording

Promotion sign-off is two-person dual-control (Phase 7 gate, criterion (g)). Both
approvers must:

1. Independently review the most recent **rollback drill** log entry and confirm it
   passes the four drill criteria above.
2. Independently review the shadow-live window metrics (N >= 20 trading days, zero
   P0/P1, discrepancy rate < 0.1%, recon lag p99 < 60s, audit completeness 100%).
3. Confirm the most recent **kill-switch drill** (per `docs/ops/kill-switch-stuck.md`)
   passed within 30 days.
4. **Hard precondition — drill-freshness check.** Before issuing the
   dual-control `LivePromotionApproved`, run
   [`scripts/ops/check_drill_freshness.py`](../../scripts/ops/check_drill_freshness.py)
   against [`docs/ops/drill-log.md`](drill-log.md) for the target adapter. The
   script enforces Phase 7 gate criteria (f) and (h) mechanically — it exits 0
   only when both the kill-switch and rollback drill types have a passing
   entry within the last 30 days for `<provider>-live`. **Do not proceed if
   the script exits non-zero**; re-run the stale drill type, log it, and
   re-check.

   ```sh
   python3 scripts/ops/check_drill_freshness.py --target-adapter <provider>-live
   # exit 0 → freshness contract satisfied; proceed to step 5
   # exit non-zero → stderr names the stale drill type; halt the gate
   ```
5. Record the sign-off via the dedicated dual-control endpoint
   `POST /promotion/approve` (mirror of `/killswitch/reset`, issue #87). The audit event
   is `LivePromotionApproved`, written via the orchestrator's `LivePromotionActivities`
   Activity through the hash-chain writer (PR #117); both `approver_id_1` and
   `approver_id_2` are required and must be distinct — single-approver and same-ID
   requests reject with `approvers_must_differ` and no audit row is written.

   Verify the sign-off purely by audit-log query (no out-of-band state):

   ```sh
   curl -s "http://copytrade.homelab.local/audit?kind=LivePromotionApproved" \
     -H "X-Tenant-Id: <t>" -H "X-Strategy-Id: <s>" | jq '.items[0].subject'
   # Must return a row whose subject carries two distinct approver_id_* values
   # inside the gate window. If the row is absent, the gate has NOT cleared —
   # do not flip broker_target.
   ```

**P3-b — a risk-envelope edit AFTER sign-off VOIDS the approval.** Once `LivePromotionApproved`
is recorded, any change to a risk-relevant config field (the P0c-a DANGEROUS/EXPOSURE set —
`broker_target`, `daily_loss_threshold`, the notional caps, the contract/position/capital caps,
the portfolio gates) that lands after the approval's `occurred_at` re-opens the gate: the live
dispatch verify returns `config_changed` and refuses live orders until a fresh
`POST /promotion/approve` (again two distinct approvers) is recorded. This protects the
configmap-reload path (edit YAML → restart), which the runtime config-write API guard does not
cover. The specific changed field(s) are visible in the `TenantConfigChanged` audit rows for that
(tenant, strategy) after the approval:

```sh
curl -s 'http://copytrade.homelab.local/audit?tenant=<t>&strategy=<s>&kind=TenantConfigChanged&limit=5' \
  | jq '.events[] | {occurred_at, changed_keys: .subject.changed_keys}'
# Any row whose occurred_at is after the LivePromotionApproved occurred_at AND whose
# changed_keys touches a risk field is what trips config_changed — re-approve to clear it.
```

Once `LivePromotionApproved` is in the audit log with two distinct IDs, the operator
flips `broker_target` to `<provider>-live` per the promotion procedure (mirror image of
Path 2 above). The rollback runbook stays on file as the standing recovery procedure.

## Rollback (of the rollback)

If the rollback itself goes wrong (e.g. the configmap flip didn't take, or recon
surfaced an unrecoverable orphan), the recovery is:

```sh
# Leave the kill switch tripped (it should already be). Do NOT reset until the
# underlying problem is fixed.
curl -s http://copytrade.homelab.local/killswitch/state | jq '.tripped'  # → true

# If broker_target didn't actually flip (verify the configmap):
kubectl -n copytrade get configmap tenant-<t>-config -o yaml | grep broker_target

# Re-apply and force another rollout:
kubectl -n copytrade rollout restart deployment/orchestrator-svc
```

If a real-money orphan remains unresolved after working through
`docs/ops/journal-broker-mismatch.md`, escalate. There is no autonomous recovery for an
unreconciled live position — the kill switch stays tripped until human action closes it
on the broker side.

## Post-incident verification

- `broker_target` for the affected tenant is `<provider>-paper` in both the configmap
  and the running orchestrator-svc env (`kubectl -n copytrade exec deploy/orchestrator-svc
  -- env | grep BROKER_TARGET`).
- `ReconciliationCompleted` shows `discrepancies: 0` for two consecutive cycles.
- `KillSwitchResetApproved` event exists with two distinct approver IDs, post-revert.
- A paper BTO has completed end-to-end through the journal → broker → audit path on the
  paper adapter.
- The five audit events listed under "Audit-log evidence to capture" are attached to
  the incident ticket.

## Why this exists

Live broker promotion is the highest-blast-radius transition in the system — the first
real-money trade is also the first time the full operational stack runs without a
safety net. Issue #23 (risk-manager review) flagged that the Phase 7 gate had neither a
quantitative bar nor a tested reversal procedure; this runbook is the reversal
procedure, and the Phase 7 gate's criterion (h) requires this runbook to have a passing
drill on file before any promotion is approved. A promotion path without a drilled
rollback is an irreversible step disguised as a reversible one.
