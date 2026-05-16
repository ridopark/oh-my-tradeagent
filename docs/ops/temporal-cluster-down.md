# Runbook: Temporal cluster down or unreachable

## When to use

The shared Temporal cluster (frontend gRPC at
`temporal-frontend.temporal.svc.cluster.local:7233`, k8s namespace
`temporal`) is not responding. All copy-trade workers across
orchestrator/exec/market-data/audit are failing health checks; no workflow
tasks make progress. By design, `risk.check_entry` Activity fails CLOSED
when the kill switch workflow cannot be queried — so even if Temporal came
back partially up, new entries would be rejected with
`kill_switch_unavailable` until everything's healthy.

**Phase 5b.E consolidation note:** the Temporal frontend was moved out of
the `copytrade` k8s namespace onto the shared `temporal/temporal-frontend`
cluster. Operations against the Temporal Deployment itself happen in the
`temporal` k8s namespace; operations against copy-trade workers
(orchestrator, exec, audit, market-data, api-gateway, signal-source-discord)
still happen in `copytrade`. **Any rollout/restart of `temporal-frontend`
affects every tenant of the shared cluster, not just copy-trade.**

## Symptoms

- All copy-trade service Deployments restart-looping or stuck `NotReady`.
- `temporal-frontend` pod (k8s ns `temporal`) is `CrashLoopBackOff` or `Error`.
- Postgres pod healthy, but `kubectl -n temporal logs deploy/temporal-frontend`
  shows schema-mismatch / connection-refused / OOM errors.
- Sidecar logs show `temporalio.client.UnknownService` or gRPC `UNAVAILABLE` errors on
  every `start_workflow` attempt.

## Detection

```sh
ssh ridopark@192.168.10.123

# Copy-trade worker pod status:
kubectl -n copytrade get pods

# Temporal frontend logs (shared cluster, lives in `temporal` ns since 5b.E):
kubectl -n temporal logs deploy/temporal-frontend --tail=200

# Temporal UI reachable?
kubectl -n temporal logs deploy/temporal-ui --tail=50

# Can we reach the frontend at all? Schedule the probe in the `temporal` ns
# so the short-name service DNS resolves:
kubectl -n temporal run --rm -it temporal-probe --restart=Never \
  --image=temporalio/admin-tools:1.29 -- \
  temporal operator cluster health --address temporal-frontend:7233
```

## Diagnose first, then act

### Postgres unhealthy (shared Temporal cluster's Postgres)

The shared Temporal cluster runs its own Postgres in the `temporal` ns —
separate from the copy-trade Postgres in `copytrade`. Check both depending
on the symptom:

```sh
# Temporal's Postgres (5b.E shared cluster):
kubectl -n temporal get pods -l app=postgres
kubectl -n temporal logs <postgres-pod-name> --tail=100
```

If disk full: this is the long-running risk on the cluster's PVC. Escalate
to the operator of the shared cluster.

### Schema mismatch after a Temporal version bump

The shared cluster's Temporal manifests live outside this repo. To check the
running image:

```sh
kubectl -n temporal get deploy/temporal-frontend \
  -o jsonpath='{.spec.template.spec.containers[0].image}'
```

If we rolled back the image but kept the schema: the shared-cluster operator
re-applies the previous-good image. **This affects every tenant** of the
shared Temporal cluster, not just copy-trade.

### Cluster just won't start

```sh
# Force a clean frontend cycle without touching Postgres data. This affects
# every tenant of the shared cluster — coordinate with the operator first.
kubectl -n temporal delete pod -l app=temporal-frontend
# Watch the new pod come up:
kubectl -n temporal logs -f deploy/temporal-frontend
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
# Roll back the shared cluster's frontend to the previous image
# (from kubectl rollout history). Coordinate with the shared-cluster
# operator — every tenant of `temporal` is affected.
kubectl -n temporal rollout undo deployment/temporal-frontend
kubectl -n temporal rollout status deployment/temporal-frontend --timeout=300s
```

If the persisted schema is incompatible with the rolled-back image, restore the
shared cluster's Postgres from a recent snapshot. (No automated backup in v0 —
flagged as a Phase 6 prerequisite.)

## Post-incident verification

```sh
# Temporal frontend reachable:
kubectl -n temporal run --rm -it temporal-probe --restart=Never \
  --image=temporalio/admin-tools:1.29 -- \
  temporal operator cluster health --address temporal-frontend:7233

# Custom Search Attributes still registered on the copytrade Temporal namespace.
# scripts/ops/temporal-copytrade-namespace-bootstrap.sh is idempotent — re-run
# to repair if either SA went missing:
kubectl -n temporal run --rm -it temporal-probe --restart=Never \
  --image=temporalio/admin-tools:1.29 -- \
  temporal --address temporal-frontend:7233 --namespace copytrade \
    operator search-attribute list

# All copy-trade workers healthy:
kubectl -n copytrade get pods

# KillSwitchWorkflow Running (in the `copytrade` Temporal namespace):
kubectl -n temporal run --rm -it temporal-probe --restart=Never \
  --image=temporalio/admin-tools:1.29 -- \
  temporal --address temporal-frontend:7233 --namespace copytrade \
    workflow list --query "WorkflowType='KillSwitchWorkflow' AND ExecutionStatus='Running'"
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
