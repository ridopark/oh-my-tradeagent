# Runbook: kill switch stuck (or unavailable)

## When to use

The `KillSwitchWorkflow` is in a bad state:

- The workflow is not Running (Temporal returns `kill_switch_unavailable`); risk check
  fails closed and every new BTO is rejected.
- The switch tripped automatically (daily-loss threshold) and you want to reset.
- The switch tripped but the reset path is failing validation.
- The switch is "stuck tripped" — was reset but stays tripped on next `killswitchState`
  query (rare; usually a workflow-thread crash mid-reset).

## Symptoms

- Every BTO post is audit-logged as `SignalRejected{reason='kill_switch_unavailable'}` or
  `SignalRejected{reason='killswitch_tripped'}`.
- `curl http://copytrade.homelab.local/killswitch/state` returns 503 or a 200 with
  `{"tripped":true, ...}` you didn't expect.
- Orchestrator pod logs show `WorkflowNotFoundException: KillSwitchWorkflow` repeatedly.

## Detection

```sh
ssh ridopark@192.168.10.123

# Query the kill switch state via the api-gateway (recommended path):
curl -s http://copytrade.homelab.local/killswitch/state | jq

# Or directly via Temporal (5b.E: frontend lives in `temporal` k8s ns under
# Temporal namespace `copytrade`):
kubectl -n temporal run --rm -it temporal-cli --restart=Never \
  --image=temporalio/admin-tools:1.29 -- \
  temporal workflow query --address temporal-frontend:7233 --namespace copytrade \
    --workflow-id "t-dev/s-copytrade-v1/killswitch" \
    --type killswitchState

# Find the most recent trip event in audit:
curl -s 'http://copytrade.homelab.local/audit?tenant=dev&strategy=copytrade-v1&kind=KillSwitchTripped&limit=5' | jq
```

## Path 1: workflow not running ("unavailable")

The `KillSwitchWorkflow` is meant to be long-running with `continueAsNew` daily. If it
isn't Running, no risk check passes. Re-bootstrap via the same path the cluster uses on
first deploy.

```sh
# Start the workflow (5b.E: shared cluster, copytrade Temporal namespace):
kubectl -n temporal run --rm -it temporal-cli --restart=Never \
  --image=temporalio/admin-tools:1.29 -- \
  temporal workflow start --address temporal-frontend:7233 --namespace copytrade \
    --workflow-id "t-dev/s-copytrade-v1/killswitch" \
    --workflow-type KillSwitchWorkflow \
    --task-queue orchestrator-core \
    --input '{"schemaVersion":1,"tenantId":"dev","strategyId":"copytrade-v1"}' \
    --search-attribute 'TenantStrategy="t-dev/s-copytrade-v1"'
```

Verify the next `risk.check_entry` succeeds via a synthetic non-trading signal:

```sh
# Pick a non-whitelisted author so the gate still rejects, but the rejection
# reason is `author_not_allowed` rather than `kill_switch_unavailable`.
# That confirms the kill switch is queryable again.
```

## Path 2: tripped, reset via api-gateway

Reset is single-operator: the authenticated operator is `approver_id_1` (the
`X-Operator-Id` header); there is no second approver.

```sh
curl -X POST http://copytrade.homelab.local/killswitch/reset \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: operator:alice' \
  -d '{
    "note": "manual reset after auto-trip on daily-loss threshold"
  }'
```

The reset sets `tripped=false` and starts a cooldown window (default 60s per
`reset_cooldown_secs` in the strategy YAML) during which new entries still reject. Wait
the cooldown, then verify with the state query above.

Error responses: a missing/blank `X-Operator-Id` header returns **400** (`missing_header`,
rejected at the gateway before the update). Resetting a switch that is not tripped returns
**409 Conflict** (`update_rejected`, detail `not_tripped`) from the reset validator.

## Path 3: stuck tripped despite reset

Rare. Symptom: `/killswitch/reset` returns 200 but the next state query still shows
`tripped: true`. Possible causes:

- The reset Update was admitted but the handler crashed mid-flight (Temporal would
  normally retry; check workflow history).
- A second `trip` Update raced in via the auto-trip path (the heartbeat re-trips on the
  next 60s tick if `daily_pnl <= -threshold`).

Investigate by reading the workflow history:

```sh
kubectl -n temporal run --rm -it temporal-cli --restart=Never \
  --image=temporalio/admin-tools:1.29 -- \
  temporal workflow show --address temporal-frontend:7233 --namespace copytrade \
    --workflow-id "t-dev/s-copytrade-v1/killswitch" --output table | tail -50
```

If you see the auto-trip cascade re-firing on every heartbeat, the underlying loss state
hasn't recovered. Either:

- Wait for tomorrow's trading day (the heartbeat checks `tradingDay`; auto-trip rolls
  over). Most common path.
- Investigate `DailyPnlActivities.computeRealizedPnl` — there may be a stale fill not yet
  acked through reconciliation. See `docs/ops/journal-broker-mismatch.md`.

## Rollback

Kill switch state is durable in Temporal. There is no "rollback" except to re-trip:

```sh
curl -X POST http://copytrade.homelab.local/killswitch/trip \
  -d '{"reason":"manual:operator","actor":"operator:<your-handle>"}'
```

## Post-incident verification

- `curl /killswitch/state` returns `tripped: false` (after reset path) or `tripped: true`
  (intentionally).
- The next whitelisted-author BTO post completes through risk check and reaches
  `contract.resolve` (visible in audit log).
- `KillSwitchWorkflow` is `Running` (not `Completed` / `Failed`).

See also: [`post-deploy-verification/issue-127-killswitch-history.md`](post-deploy-verification/issue-127-killswitch-history.md)
for the audit-trail evidence that the PR #126 `continueAsNew` history-cap fix
is working as designed (zero `history count exceeds limit` warnings, daily
recurrence cadence, etc.).

## Drill log

Every kill-switch drill run must be recorded in
[`docs/ops/drill-log.md`](drill-log.md) (copy the row from its
"Entry template" section). The Phase 7 live-promotion gate requires
a passing kill-switch drill within the last 30 days for the target
`<provider>-live` adapter (gate criterion (f)); freshness is enforced
mechanically by
[`scripts/ops/check_drill_freshness.py`](../../scripts/ops/check_drill_freshness.py),
which parses the drill log and exits non-zero when the kill-switch
entry is stale or missing.

## Why this exists

The kill switch is the operator's "halt now" button — and the only path through which
the reset can be enforced. PLAN.md lists this as one of the five Phase 5b
runbook drills; the failure modes are subtle (auto-trip re-firing, schema_version
rejection, not-tripped mis-timing) and the recovery requires Temporal-side action.
