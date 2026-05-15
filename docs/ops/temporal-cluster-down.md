# Runbook: Temporal cluster down or unreachable

## When to use

The Temporal cluster (frontend gRPC at `temporal:7233`) is not responding. All workers
across orchestrator/exec/market-data/audit are failing health checks; no workflow tasks
make progress. By design, `risk.check_entry` Activity fails CLOSED when the kill switch
workflow cannot be queried — so even if Temporal came back partially up, new entries
would be rejected with `kill_switch_unavailable` until everything's healthy.

## Symptoms

- All Java service Deployments restart-looping or stuck `NotReady`.
- `temporal` pod is `CrashLoopBackOff` or `Error`.
- Postgres pod healthy, but `kubectl -n copytrade logs deploy/temporal` shows
  schema-mismatch / connection-refused / OOM errors.
- Sidecar logs show `temporalio.client.UnknownService` or gRPC `UNAVAILABLE` errors on
  every `start_workflow` attempt.

## Detection

```sh
ssh ridopark@192.168.10.123

# Pod status across the namespace:
kubectl -n copytrade get pods

# Temporal frontend logs:
kubectl -n copytrade logs deploy/temporal --tail=200

# Temporal UI reachable?
kubectl -n copytrade logs deploy/temporal-ui --tail=50

# Can we reach the frontend at all (from inside the namespace)?
kubectl -n copytrade run --rm -it temporal-probe --restart=Never \
  --image=temporalio/admin-tools:1.25 -- \
  temporal operator cluster health --address temporal:7233
```

## Diagnose first, then act

### Postgres unhealthy

If `kubectl -n copytrade exec statefulset/postgres -- pg_isready` fails:

```sh
# Logs:
kubectl -n copytrade logs statefulset/postgres --tail=100

# Disk full on the PV?
kubectl -n copytrade exec statefulset/postgres -- df -h /var/lib/postgresql/data
```

If disk full: this is the long-running risk on the 10Gi PVC. See `docs/ops/disk-full.md`
(not yet authored — flagged as a follow-up runbook).

### Schema mismatch after a Temporal version bump

`temporalio/auto-setup` will refuse to start if the persisted schema is newer than the
image. Check the version pinned in `infra/k8s/30-temporal.yaml` vs the image actually
deployed:

```sh
kubectl -n copytrade get deploy/temporal -o jsonpath='{.spec.template.spec.containers[0].image}'
```

If we rolled back the image but kept the schema: re-apply the previous-good image.

### Cluster just won't start

```sh
# Force a clean pod cycle without touching Postgres data:
kubectl -n copytrade delete pod -l app=temporal
# Watch the new pod come up:
kubectl -n copytrade logs -f deploy/temporal
```

## Immediate action — emergency safe-mode

While Temporal is down, **trip the kill switch** preemptively so when it comes back, no
new BTOs fire until you've verified the cluster is consistent.

But the kill switch Workflow itself lives in Temporal. So instead:

1. Scale orchestrator down to zero replicas. New `CopytradeSignalWorkflow` starts will
   queue but never be processed.
   ```sh
   kubectl -n copytrade scale deployment/orchestrator --replicas=0
   ```
2. Scale sidecar to zero so new Discord posts don't accumulate retryable
   `start_workflow` calls.
   ```sh
   kubectl -n copytrade scale deployment/signal-source-discord --replicas=0
   ```
3. Open positions stay open in Temporal's history; they'll resume from their last WFT
   when the cluster recovers (Temporal's durability guarantee). EOD/expiry timers fire
   late but still fire.
4. Once Temporal is back up and the orchestrator pod logs show `KillSwitchWorkflow`
   running, scale sidecar back up.
   ```sh
   kubectl -n copytrade scale deployment/orchestrator --replicas=1
   kubectl -n copytrade rollout status deployment/orchestrator --timeout=180s
   kubectl -n copytrade scale deployment/signal-source-discord --replicas=1
   ```

## Rollback

If a Temporal image bump caused this:

```sh
# Roll back to the previous image (from kubectl rollout history):
kubectl -n copytrade rollout undo deployment/temporal
kubectl -n copytrade rollout status deployment/temporal --timeout=300s
```

If the persisted schema is incompatible with the rolled-back image, restore Postgres
from a recent snapshot. (No automated backup in v0 — flagged as a Phase 6 prerequisite.)

## Post-incident verification

```sh
# Temporal frontend reachable:
kubectl -n copytrade run --rm -it temporal-probe --restart=Never \
  --image=temporalio/admin-tools:1.25 -- \
  temporal operator cluster health --address temporal:7233

# Custom Search Attributes still registered (the temporal-bootstrap Job is idempotent —
# kubectl apply -f infra/k8s/31-temporal-bootstrap.yaml will re-run if needed):
temporal operator search-attribute list --address temporal:7233

# All workers healthy:
kubectl -n copytrade get pods

# KillSwitchWorkflow Running:
temporal workflow list --query "WorkflowType='KillSwitchWorkflow' AND ExecutionStatus='Running'"
```

## Prevention

- A multi-node k3s cluster would survive a single-node failure. Single-node is the v0
  homelab target by design; revisit at Phase 6.
- The 10Gi Postgres PVC is the most likely single-point-of-failure for the cluster.
  Monitor `kubectl -n copytrade top pod statefulset/postgres` weekly.

## Why this exists

PLAN.md Phase 5b lists `temporal-cluster-down` as one of the five required runbooks.
The kill-switch-by-design fail-closed posture (`risk.check_entry` rejects on
`kill_switch_unavailable`) means a Temporal outage is not catastrophic for capital — but
recovery requires a documented sequence to bring the worker fleet back without firing
queued duplicates.
