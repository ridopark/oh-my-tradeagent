# Temporal consolidation — post-cutover teardown (Phase 5b.E)

After the copy-trade services have been repointed at the shared
`temporal/temporal-frontend` cluster (Phase 5b.E PR) and the smoke test
described below has passed, the operator runs this runbook to tear down the
old in-`copytrade` Temporal Deployment and reclaim its Postgres database.

**Do not run this until the smoke verification below has passed.** The
in-`copytrade` Temporal stack is the rollback path until the consolidated
cluster has handled at least one synthetic BTO end-to-end.

## 0. Pre-flight: namespace bootstrap

Run this **before** rolling out the PR-5b.E manifests on a fresh cluster:

```sh
./scripts/ops/temporal-copytrade-namespace-bootstrap.sh
```

This creates the `copytrade` Temporal namespace on the shared cluster and
registers `TenantStrategy` + `ContractSymbol` as Keyword Search Attributes.
Idempotent — safe to re-run.

Verify:

```sh
ssh ridopark@192.168.10.123 \
  'kubectl -n temporal run --rm -i temporal-cli --restart=Never \
     --image=temporalio/admin-tools:1.29 -- \
     temporal --address temporal-frontend:7233 --namespace copytrade \
       operator search-attribute list'
```

Both `TenantStrategy` and `ContractSymbol` must appear in the output.

## 1. Roll out the PR-5b.E manifests

```sh
scp -r infra/k8s ridopark@192.168.10.123:~/copytrade-k8s
ssh ridopark@192.168.10.123 'kubectl apply -f copytrade-k8s/'
```

Note: the secret template lives at `infra/secrets-template/secrets.template.yaml`
on purpose — it is **not** in `infra/k8s/`, so the glob-apply above cannot
overwrite live secrets with `REPLACE_ME` placeholders. If you need to
(re-)apply real secrets, do that as a separate `kubectl apply -f` of your
filled-in `secrets.local.yaml`.

Wait until the workers reconnect to the new target:

```sh
ssh ridopark@192.168.10.123 'kubectl -n copytrade get pods'
ssh ridopark@192.168.10.123 \
  'kubectl -n copytrade logs deploy/orchestrator --tail=50' | grep -i 'temporal'
```

Look for the orchestrator log line `created Reconciliation Schedule
id=recon-t-dev-s-copytrade-v1-alpaca-paper ...` (or `... already exists
(warm boot)` if you're re-running). That confirms the new namespace is
reachable and the Schedule was bootstrapped.

## 2. Smoke: synthetic BTO

In one terminal, port-forward the shared Temporal frontend:

```sh
ssh -L 7234:127.0.0.1:7234 ridopark@192.168.10.123 \
  'kubectl -n temporal port-forward svc/temporal-frontend 7234:7233'
```

In another:

```sh
python3 scripts/harness/inject_synthetic_bto.py \
  --temporal-host 127.0.0.1:7234 \
  --namespace copytrade \
  --author TradingTheTrend
```

Watch the workflow at <http://temporal.192.168.10.123.nip.io/> — pick
namespace `copytrade` from the dropdown. The workflow must:

- start (`CopytradeSignalWorkflow`),
- pass risk gates,
- reach `PlaceOrder` on the Alpaca paper broker task queue,
- complete with `WorkflowExecutionCompleted`.

Confirm a `PositionWorkflow` was started by the signal workflow and the
Alpaca paper account shows the order.

If any step fails: do NOT continue with the teardown. Roll back the env
vars to the in-`copytrade` Temporal target, restart the affected
Deployments, and triage.

## 3. Verify the reconciliation Schedule fires

Wait 5 minutes from cutover (the Schedule interval), then:

```sh
ssh ridopark@192.168.10.123 \
  'kubectl -n temporal run --rm -i temporal-cli --restart=Never \
     --image=temporalio/admin-tools:1.29 -- \
     temporal --address temporal-frontend:7233 --namespace copytrade \
       schedule describe --schedule-id recon-t-dev-s-copytrade-v1-alpaca-paper'
```

Expect a non-null `lastActionTime` within the last 5 minutes.

## 3a. Pre-cutover for existing clusters

If the target cluster pre-dates PR #106 (i.e. the Postgres PV already
exists from before the orchestrator DB split landed), the dedicated
`orchestrator` database does **not** yet exist on disk. `postgres-init`
only fires on a fresh PV (see comment at `infra/k8s/10-postgres.yaml:35`:
"`/docker-entrypoint-initdb.d` only fires on a fresh data volume"), so
the `create_db_if_missing orchestrator` line in `10-postgres.yaml` will
not be re-executed on an upgrade. Without this manual step,
`orchestrator-svc` (and `api-gateway`, which shares the same datasource
per section 4 below) will fail to boot with a Postgres DataSource error
on the first `kubectl apply` of the PR-5b.E manifests.

Run this **once**, before re-deploying `orchestrator-svc` /
`api-gateway` against an existing cluster:

```sh
kubectl exec -n copytrade postgres-0 -- psql -U postgres -c 'CREATE DATABASE orchestrator OWNER temporal;'
```

Fresh clusters can skip this step — `postgres-init` will create the
`orchestrator` database automatically on first boot of the StatefulSet.

## 4. Tear down the in-`copytrade` Temporal stack

Only proceed once steps 2 and 3 have passed.

```sh
# Stop the local Temporal Deployment + UI + bootstrap Job. The manifests
# remain in-repo until the follow-up PR removes them; `delete -f` here just
# clears the resources from the live cluster.
ssh ridopark@192.168.10.123 \
  'kubectl -n copytrade delete -f copytrade-k8s/30-temporal.yaml \
                            -f copytrade-k8s/31-temporal-bootstrap.yaml'

# The Postgres StatefulSet (10-postgres.yaml) is shared with the
# orchestrator + api-gateway databases — DO NOT delete it. Only drop the
# two Temporal-specific databases inside it.
#
# Since issue #56 item 9, orchestrator-svc and api-gateway own a dedicated
# `orchestrator` Postgres database (audit_log + option_symbol_cache live
# there), so `DROP DATABASE temporal` no longer destroys their data — it
# only touches Temporal's own auto-setup tables. That's what makes this
# step safe to re-run.
ssh ridopark@192.168.10.123 \
  'kubectl -n copytrade exec -it sts/postgres -- psql -U temporal -c "DROP DATABASE temporal;"'
ssh ridopark@192.168.10.123 \
  'kubectl -n copytrade exec -it sts/postgres -- psql -U temporal -c "DROP DATABASE temporal_visibility;"'

# Confirm the temporal resources are gone:
ssh ridopark@192.168.10.123 'kubectl -n copytrade get deploy,svc,job | grep temporal' || \
  echo "no temporal resources remain in copytrade (expected)"
```

## 5. Follow-up PR (out of scope for 5b.E)

In a subsequent PR, delete `infra/k8s/30-temporal.yaml` and
`infra/k8s/31-temporal-bootstrap.yaml` from the repo and update
`infra/k8s/README.md`'s layout block. That PR is intentionally separate so
this consolidation PR can be reverted cleanly if the smoke regresses.
