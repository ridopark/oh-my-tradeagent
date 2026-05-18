# Harness — green-redeploy proof (Phase 5b.D)

Two scripts that together capture the evidence PLAN.md Phase 5b's "Done when"
criterion asks for:

> "A green redeploy of orchestrator-svc with 3+ running PositionWorkflows
> completes with zero workflow stalls."

```
scripts/harness/
├── inject_synthetic_positions.py   # spawns N PositionWorkflows
├── green-redeploy-proof.sh         # full end-to-end proof script
└── README.md                       # this file
```

## Why direct PositionWorkflow start (not via CopytradeSignalWorkflow)

The redeploy-proof question is narrow: *do existing PositionWorkflows survive
an orchestrator restart?* Driving through the full BTO funnel (Discord →
sidecar → CopytradeSignalWorkflow → risk → contract → broker → fill →
PositionWorkflow start) would bring in unrelated failure modes (broker stub
fills, signal-age veto, contract symbol resolution, etc.). Going straight to
`PositionWorkflow.start(input)` isolates the redeploy-resilience surface.

## Prerequisites

- Homelab k3s cluster running the manifests from `infra/k8s/` (Phase 5b.B).
- `kubectl` access on the homelab via `ssh ridopark@192.168.10.123`.
- Local Python 3.12+ (project standard) with `temporalio` installed.
- The orchestrator Deployment runs at `replicas: 1` (the BEFORE/AFTER pod-name
  assertion in `green-redeploy-proof.sh` assumes a single pod). Phase 5b's
  manifests ship with `replicas: 1`; revisit when scaling out.

Quickest setup for `temporalio` + the shared contract package (the harness
scripts now import `SearchAttributeKey` constants from
`ohmytradeagent_contract.search_attributes` to keep the SA names in sync with
the sidecar — see issue #45):

```sh
python3 -m venv /tmp/copytrade-harness-venv
source /tmp/copytrade-harness-venv/bin/activate
# Pin to the 1.9.x series — server is 1.27.x and the SDK 1.9.x line was the
# last one verified compatible at the time this harness landed.
pip install 'temporalio>=1.9,<2'
pip install -e contract/python
```

## Single-step proof

```sh
./scripts/harness/green-redeploy-proof.sh
```

Reads from env vars (all optional):

| Var                    | Default                            | Purpose                                                        |
|------------------------|------------------------------------|----------------------------------------------------------------|
| COUNT                  | `3`                                | Number of PositionWorkflows to spawn                           |
| HOMELAB                | `ridopark@192.168.10.123`          | SSH target for kubectl operations                              |
| NAMESPACE              | `copytrade`                        | k8s namespace where the copy-trade Deployments live            |
| TEMPORAL_K8S_NAMESPACE | `temporal`                         | k8s namespace where the shared Temporal frontend runs (5b.E)   |
| TEMPORAL_SVC           | `temporal-frontend`                | Temporal frontend Service name on the shared cluster (5b.E)    |
| TEMPORAL_NAMESPACE     | `copytrade`                        | Temporal-level namespace for copy-trade workflows (5b.E)       |
| TENANT                 | `dev`                              | tenant_id for the test workflows                               |
| STRATEGY               | `copytrade-v1`                     | strategy_id for the test workflows                             |

What the script asserts:

1. Spawned `COUNT` PositionWorkflows are all Running before the restart.
2. `kubectl rollout restart deployment/orchestrator` completes within 180s.
3. The exact same set of `PositionWorkflow` IDs is Running after the restart.
4. The orchestrator pod name changed (i.e. the rollout actually replaced it).
5. The new pod's last 500 log lines contain zero `NonDeterministicException`.

On PASS, the script emits a `Deploy-Verified: <pod>@<sha> healthy <ISO-ts>`
line on stdout. Paste that into the PR body so the autonomous-ship 1d gate
recognizes the deploy evidence.

## Manual variant (debugging)

If the full script fails, run the steps by hand:

```sh
# 1. Port-forward Temporal from the cluster. After 5b.E the frontend lives
#    in the `temporal` k8s namespace under Service `temporal-frontend`.
ssh -L 7234:127.0.0.1:7234 ridopark@192.168.10.123 \
  'kubectl -n temporal port-forward svc/temporal-frontend 7234:7233' &

# 2. Spawn 3 workflows.
python3 scripts/harness/inject_synthetic_positions.py \
  --count 3 --temporal-host 127.0.0.1:7234 --namespace copytrade

# 3. List running PositionWorkflows.
ssh ridopark@192.168.10.123 \
  'kubectl -n temporal run --rm -i temporal-cli --restart=Never \
     --image=temporalio/admin-tools:1.29 -- \
     temporal --address temporal-frontend:7233 --namespace copytrade workflow list \
       --query "WorkflowType=\"PositionWorkflow\" AND ExecutionStatus=\"Running\"" \
       --output json' | jq '.[].execution.workflowId'

# 4. Trigger restart manually. (orchestrator-svc Deployment is still in
#    the copytrade k8s namespace; only the Temporal pods moved.)
ssh ridopark@192.168.10.123 \
  'kubectl -n copytrade rollout restart deployment/orchestrator && \
   kubectl -n copytrade rollout status deployment/orchestrator --timeout=180s'

# 5. Re-list and compare.
```

## Cleanup

The harness workflows sit Running until EOD/expiry timers fire or you cancel
them manually. The `orchestrator` container is Spring Boot — no `temporal`
CLI on board — so use a throwaway admin-tools pod the same way
`list_running()` does:

```sh
LIST_OUT=$(ssh ridopark@192.168.10.123 \
  'kubectl -n temporal run --rm -i temporal-cli-list --restart=Never \
     --image=temporalio/admin-tools:1.29 -- \
     temporal --address temporal-frontend:7233 --namespace copytrade workflow list \
       --query "WorkflowType=\"PositionWorkflow\" AND TenantStrategy=\"t-dev/s-copytrade-v1\" AND ExecutionStatus=\"Running\"" \
       --output json' | jq -r '.[].execution.workflowId')

for wf in $LIST_OUT; do
  ssh ridopark@192.168.10.123 \
    "kubectl -n temporal run --rm -i temporal-cli-term --restart=Never \
       --image=temporalio/admin-tools:1.29 -- \
       temporal --address temporal-frontend:7233 --namespace copytrade workflow terminate \
         --workflow-id $wf --reason 'harness-cleanup'"
done
```

## Limitations

- The harness uses a synthetic OCC (`HRN<i>...`) that no real broker would
  recognize. The orchestrator's `PositionWorkflow` accepts the input verbatim;
  any STC signal arriving for this OCC will route correctly via
  `TenantStrategy + ContractSymbol` SAs.
- No fill events fire because the orchestrator never placed a real order.
  PositionWorkflow sits at its first await (EOD timer or `partial_exit`
  signal). That's enough for the redeploy-proof contract.
- For full end-to-end validation including the BTO funnel, see 5b.D
  follow-ups (deferred — not on the Phase 5b "Done when" path).
