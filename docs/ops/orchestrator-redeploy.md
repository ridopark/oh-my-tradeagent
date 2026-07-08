# Runbook: orchestrator-svc redeploy with running PositionWorkflows

## When to use

You're rolling out a new orchestrator-svc image (bug fix, dependency bump, schema_version
bump, etc.) and there are open paper positions that have live `PositionWorkflow`s waiting
on exit signals. Naive `kubectl rollout restart` will stop the worker mid-poll, and the
in-flight workflow tasks will eventually retry against the new image — but if the new
image's workflow code has a non-trivially-different shape, replay may diverge and the
workflow can fail with `NonDeterministicException`.

## Symptoms

- Trying to deploy a new orchestrator image but want to keep current positions safe.
- After a redeploy, Temporal UI shows `PositionWorkflow` tasks failing with
  `NonDeterministicException` or `IncompatibleHistoryReplayException`.
- Pods restart-loop because a workflow keeps timing out the WFT.

## Detection

```sh
# Open PositionWorkflows on the deployment target tenant/strategy:
ssh ridopark@192.168.10.123
kubectl -n copytrade exec deploy/orchestrator -- \
  curl -sf http://localhost:8080/actuator/health/readiness && echo

# Count running PositionWorkflows from Temporal (via the api-gateway):
curl -s http://copytrade.homelab.local/positions | jq '.positions | length'

# Or via temporal CLI from the admin-tools image. Phase 5b.E: schedule the
# probe in the `temporal` k8s namespace where the shared frontend lives.
kubectl -n temporal run --rm -it temporal-cli --restart=Never \
  --image=temporalio/admin-tools:1.29 -- \
  temporal --address temporal-frontend:7233 --namespace copytrade workflow list \
    --query "WorkflowType='PositionWorkflow' AND ExecutionStatus='Running'" \
    --limit 20
```

## Choose the redeploy path

| Path | When to use | Cost |
|---|---|---|
| **Drain** | Workflow-shape change (new Signal, new Activity, branch reordering) | Wait for positions to flat — could be hours; EOD timer at 15:55 ET force-closes anyway |
| **Pin** (rolling) | Bug fix that preserves workflow history shape; `Workflow.getVersion` already wraps the change-point | Seconds |

### Drain path

1. Stop new entries: trip the kill switch.
   ```sh
   curl -X POST http://copytrade.homelab.local/killswitch/trip \
     -H 'X-Operator: <your-handle>' \
     -d '{"reason":"manual:operator_initiated","actor":"operator:<your-handle>"}'
   ```
2. Wait for all `PositionWorkflow`s to complete (force-close on EOD or via STC signals).
3. Verify queue is drained:
   ```sh
   curl -s http://copytrade.homelab.local/positions | jq '.positions | length'
   # should print 0
   ```
4. Deploy the new image:
   ```sh
   kubectl -n copytrade set image deployment/orchestrator \
     orchestrator=ghcr.io/ridopark/oh-my-tradeagent-orchestrator:<new-sha>
   kubectl -n copytrade rollout status deployment/orchestrator --timeout=180s
   ```
5. Reset kill switch (single-operator; the `X-Operator-Id` header is `approver_id_1`):
   ```sh
   curl -X POST http://copytrade.homelab.local/killswitch/reset \
     -H 'X-Operator-Id: operator:<you>' \
     -d '{"note":"orchestrator redeploy"}'
   ```

### Pin path (rolling)

Only safe if the change-point is wrapped in `Workflow.getVersion(...)`. Verify before
proceeding:

```sh
grep -n 'Workflow.getVersion' services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/workflows/
```

If the change-point exists and is versioned:

```sh
kubectl -n copytrade set image deployment/orchestrator \
  orchestrator=ghcr.io/ridopark/oh-my-tradeagent-orchestrator:<new-sha>
kubectl -n copytrade rollout status deployment/orchestrator --timeout=180s
```

The rolling-update default is `maxSurge: 25% maxUnavailable: 25%`. With 1 replica, that
means the new pod starts before the old one terminates. `Workflow.getVersion` keeps each
workflow on the version it started with; new workflows pick up the new branch.

## Rollback

```sh
# Roll back to the previously-deployed image:
kubectl -n copytrade rollout undo deployment/orchestrator
kubectl -n copytrade rollout status deployment/orchestrator --timeout=180s
```

If the bad image caused workflow failures, `temporal workflow reset` will roll the
affected workflows back to the last good WFT — see Temporal docs.

## Post-incident verification

- All `PositionWorkflow`s that were running before the deploy still complete cleanly (no
  `NonDeterministicException` in the orchestrator pod logs).
- `/actuator/health/readiness` returns 200 within 30s of the new pod starting.
- `temporal operator search-attribute list --namespace copytrade` (run from a
  pod in the `temporal` k8s ns) still shows `TenantStrategy` and `ContractSymbol`
  — `scripts/ops/temporal-copytrade-namespace-bootstrap.sh` is idempotent but
  worth confirming after any cluster-wide change.
- Audit log shows a `KillSwitchResetApproved` event carrying `approver_id_1` + `via`
  (if the drain path was used).
- If the redeploy included an audit-log schema migration, confirm the orchestrator login
  role still has membership in `orchestrator_app` (see
  [`docs/ops/audit-retention.md`](audit-retention.md) §4 "DB role posture"). Without it,
  audit INSERTs will fail post-rollout.
  > ⚠️ The role grant alone is not sufficient if the orchestrator login role is a Postgres superuser. See `docs/ops/audit-retention.md §4` for the verification procedure that confirms the immutability constraint is engaged.

## Why this exists

The plan's Phase 5b "Done when" criterion calls out *"A green redeploy of orchestrator-svc
with 3+ running PositionWorkflows completes with zero workflow stalls"* (PLAN.md). This
runbook is the operator's contract for satisfying that criterion across the two redeploy
shapes the design supports.
