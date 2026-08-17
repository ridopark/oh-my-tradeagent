#!/usr/bin/env python3
"""Spawn N synthetic PositionWorkflows for the Phase 5b.D green-redeploy proof.

The redeploy-proof question is "do existing PositionWorkflows survive an
orchestrator-svc restart without NonDeterministicException?" — not "does the
full BTO funnel work end-to-end." So this harness starts PositionWorkflows
directly via the Temporal client, bypassing CopytradeSignalWorkflow / risk
checks / contract resolver / broker stack. Simpler scaffolding, narrower
target for the proof.

Usage:
    # Default 3 workflows against a port-forwarded homelab cluster.
    # Phase 5b.E: copy-trade workflows now live on the shared temporal cluster
    # under Temporal namespace `copytrade`, so port-forward from `temporal` ns:
    #   kubectl -n temporal port-forward svc/temporal-frontend 7233:7233 &
    python scripts/harness/inject_synthetic_positions.py --count 3 --namespace copytrade

    # Specific cluster + tenant + strategy:
    python scripts/harness/inject_synthetic_positions.py \\
        --count 3 \\
        --temporal-host localhost:7233 \\
        --tenant dev \\
        --strategy copytrade-v1

Each spawned workflow is started with:
  workflow_id  = t-<tenant>/s-<strategy>/pos/<OCC>/harness-<N>
  search_attrs = TenantStrategy + ContractSymbol (the two custom SAs
                 registered by scripts/ops/temporal-copytrade-namespace-bootstrap.sh)

Workflows started by this harness will sit at their first EOD/expiry timer
await until either:
  - signaled with `forceClose` via the api-gateway (cleanest teardown), or
  - reaching the EOD/expiry timer fired by orchestrator's MarketCalendar.

Stdout protocol — one fact per line, machine-parseable:
    spawned wf_id=<workflow_id> contract=<occ>
    summary count=<N> tenant=<tenant> strategy=<strategy>
"""
from __future__ import annotations

import argparse
import asyncio
import datetime as dt
import sys

from ohmytradeagent_contract.search_attributes import (
    CONTRACT_SYMBOL_KEY,
    TENANT_STRATEGY_KEY,
)
from temporalio.client import Client
from temporalio.common import (
    SearchAttributePair,
    TypedSearchAttributes,
    WorkflowIDReusePolicy,
)


WORKFLOW_TYPE = "PositionWorkflow"
TASK_QUEUE = "orchestrator-core"


def synthetic_occ(i: int) -> str:
    """Build a synthetic OCC option symbol.

    Real OCC: <ROOT><YY><MM><DD><CALL/PUT><STRIKE*1000 padded to 8 digits>
    e.g. NVDA250516C00140000.

    Root is always 6 chars (`HRNTST`) so the orchestrator's `expiryDateFromOcc`
    parser (`substring(6, 12)` on the YYMMDD slot) sees a real-looking date and
    arms the expiry timer — exercising the timer path the redeploy needs to
    survive. Strike varies by index to keep each contract unique. `HRNTST` is
    never going to be a real US options root.
    """
    return f"HRNTST260619C{(100 + i) * 1000:08d}"


def workflow_id_for(tenant: str, strategy: str, occ: str, idx: int, run_tag: str) -> str:
    """Workflow ID embeds a run_tag so re-running the proof script does not
    collide with workflows started by a previous (still-Running) invocation
    under REJECT_DUPLICATE."""
    return f"t-{tenant}/s-{strategy}/pos/{occ}/harness-{run_tag}-{idx}"


async def main(
    count: int,
    target: str,
    namespace: str,
    tenant: str,
    strategy: str,
) -> None:
    client = await Client.connect(target, namespace=namespace)

    # Compute once per script run — keeps both the workflow_id suffix and the
    # source_signal_workflow_id timestamp internally consistent across the N
    # workflows spawned by this invocation, while staying distinct from a
    # future re-run.
    now_utc = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    run_tag = now_utc.strftime("%Y%m%dT%H%M%SZ")
    now = now_utc.isoformat().replace("+00:00", "Z")

    for i in range(count):
        occ = synthetic_occ(i)
        wf_id = workflow_id_for(tenant, strategy, occ, i, run_tag)
        payload = {
            "schema_version": 1,
            "tenant_id": tenant,
            "strategy_id": strategy,
            "entry_signal_id": f"harness-{i}",
            "contract_symbol": occ,
            "qty": 1,
            "entry_premium": 2.50,
            "source_signal_workflow_id": f"harness-driver:{now}",
        }
        sa = TypedSearchAttributes(
            [
                SearchAttributePair(TENANT_STRATEGY_KEY, f"t-{tenant}/s-{strategy}"),
                SearchAttributePair(CONTRACT_SYMBOL_KEY, occ),
            ]
        )
        await client.start_workflow(
            WORKFLOW_TYPE,
            payload,
            id=wf_id,
            task_queue=TASK_QUEUE,
            id_reuse_policy=WorkflowIDReusePolicy.REJECT_DUPLICATE,
            search_attributes=sa,
        )
        print(f"spawned wf_id={wf_id} contract={occ}", flush=True)

    print(
        f"summary count={count} tenant={tenant} strategy={strategy}",
        flush=True,
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=3)
    parser.add_argument("--temporal-host", default="localhost:7233")
    parser.add_argument("--namespace", default="copytrade")
    parser.add_argument("--tenant", default="dev")
    parser.add_argument("--strategy", default="copytrade-v1")
    args = parser.parse_args()

    try:
        asyncio.run(
            main(
                args.count,
                args.temporal_host,
                args.namespace,
                args.tenant,
                args.strategy,
            )
        )
    except KeyboardInterrupt:
        sys.exit(130)
