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
- Local Python 3.10+ with `temporalio` installed.

Quickest setup for `temporalio`:

```sh
python3 -m venv /tmp/copytrade-harness-venv
source /tmp/copytrade-harness-venv/bin/activate
pip install temporalio
```

## Single-step proof

```sh
./scripts/harness/green-redeploy-proof.sh
```

Reads from env vars (all optional):

| Var       | Default                            | Purpose                                  |
|-----------|------------------------------------|------------------------------------------|
| COUNT     | `3`                                | Number of PositionWorkflows to spawn     |
| HOMELAB   | `ridopark@192.168.10.123`          | SSH target for kubectl operations        |
| NAMESPACE | `copytrade`                        | k8s namespace                            |
| TENANT    | `dev`                              | tenant_id for the test workflows         |
| STRATEGY  | `copytrade-v1`                     | strategy_id for the test workflows       |

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
# 1. Port-forward Temporal from the cluster.
ssh -L 7234:127.0.0.1:7234 ridopark@192.168.10.123 \
  'kubectl -n copytrade port-forward svc/temporal 7234:7233' &

# 2. Spawn 3 workflows.
python3 scripts/harness/inject_synthetic_positions.py \
  --count 3 --temporal-host 127.0.0.1:7234

# 3. List running PositionWorkflows.
ssh ridopark@192.168.10.123 \
  'kubectl -n copytrade run --rm -i temporal-cli --restart=Never \
     --image=temporalio/admin-tools:1.27.2 -- \
     temporal --address temporal:7233 workflow list \
       --query "WorkflowType=\"PositionWorkflow\" AND ExecutionStatus=\"Running\"" \
       --output json' | jq '.[].execution.workflowId'

# 4. Trigger restart manually.
ssh ridopark@192.168.10.123 \
  'kubectl -n copytrade rollout restart deployment/orchestrator && \
   kubectl -n copytrade rollout status deployment/orchestrator --timeout=180s'

# 5. Re-list and compare.
```

## Cleanup

The harness workflows sit Running until EOD/expiry timers fire or you cancel
them manually:

```sh
ssh ridopark@192.168.10.123 \
  'kubectl -n copytrade exec deploy/orchestrator -- \
     temporal --address temporal:7233 workflow list \
       --query "WorkflowType=\"PositionWorkflow\" AND TenantStrategy=\"t-dev/s-copytrade-v1\" AND ExecutionStatus=\"Running\""' | \
  jq -r '.[].execution.workflowId' | \
  xargs -I{} ssh ridopark@192.168.10.123 \
    'kubectl -n copytrade exec deploy/orchestrator -- \
       temporal --address temporal:7233 workflow terminate \
         --workflow-id {} --reason "harness-cleanup"'
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
