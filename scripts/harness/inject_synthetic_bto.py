#!/usr/bin/env python3
"""Inject a single synthetic BTO `CopytradeSignalWorkflow` start.

Bypasses the Discord sidecar so we can exercise the full Java pipeline
(risk gates → contract resolver → exec.place_order → Alpaca paper) without
needing to post to the watched channel. Risk gates still apply: the author
on the synthetic payload must be on the strategy's `author_whitelist`.

Usage:
    # Phase 5b.E: copy-trade workflows now live on the shared temporal cluster
    # under Temporal namespace `copytrade`. Port-forward the frontend from the
    # `temporal` k8s namespace and target it, e.g.:
    #
    #   ssh -L 7234:127.0.0.1:7234 ridopark@192.168.10.123 \
    #     'kubectl -n temporal port-forward svc/temporal-frontend 7234:7233' &
    #
    python scripts/harness/inject_synthetic_bto.py \
        --temporal-host 127.0.0.1:7234 \
        --namespace copytrade \
        --author TradingTheTrend \
        --ticker NVDA --expiry 2026-06-19 --strike 100 --right C --price 2.30
"""
from __future__ import annotations

import argparse
import asyncio
import datetime as dt
import sys
import uuid

from temporalio.client import Client
from temporalio.common import (
    SearchAttributeKey,
    SearchAttributePair,
    TypedSearchAttributes,
    WorkflowIDReusePolicy,
)


async def main(args) -> None:
    client = await Client.connect(args.temporal_host, namespace=args.namespace)
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    posted_at = now.isoformat().replace("+00:00", "Z")
    signal_id = f"harness-bto-{uuid.uuid4().hex[:8]}:0"
    workflow_id = f"t-{args.tenant}/s-{args.strategy}/sig/{signal_id}"

    payload = {
        "schema_version": 1,
        "tenant_id": args.tenant,
        "strategy_id": args.strategy,
        "signal_id": signal_id,
        "message_id": signal_id.split(":")[0],
        "author": args.author,
        "posted_at": posted_at,
        "action": "BTO",
        "ticker": args.ticker,
        "expiry": args.expiry,
        "strike": args.strike,
        "right": args.right,
        "price": args.price,
        "raw_line": f"BTO {args.ticker} {args.expiry} {int(args.strike)}{args.right} @ {args.price} [harness]",
    }

    ts_key = SearchAttributeKey.for_keyword("TenantStrategy")
    sa = TypedSearchAttributes(
        [SearchAttributePair(ts_key, f"t-{args.tenant}/s-{args.strategy}")]
    )

    await client.start_workflow(
        "CopytradeSignalWorkflow",
        payload,
        id=workflow_id,
        task_queue="orchestrator-core",
        id_reuse_policy=WorkflowIDReusePolicy.REJECT_DUPLICATE,
        search_attributes=sa,
    )
    print(f"started workflow_id={workflow_id}")
    print(f"  payload={payload}")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    # Default targets a local port-forward (see module docstring). The old in-
    # `copytrade` Temporal ClusterIP (10.43.38.226:7233) was removed in 5b.E.
    p.add_argument("--temporal-host", default="127.0.0.1:7234")
    p.add_argument("--namespace", default="copytrade")
    p.add_argument("--tenant", default="dev")
    p.add_argument("--strategy", default="copytrade-v1")
    p.add_argument("--author", required=True,
                   help="Must be on the strategy's author_whitelist (e.g. TradingTheTrend)")
    p.add_argument("--ticker", default="NVDA")
    p.add_argument("--expiry", default="2026-06-19",
                   help="YYYY-MM-DD; next monthly expiry by default")
    p.add_argument("--strike", type=float, default=100.0)
    p.add_argument("--right", choices=("C", "P"), default="C")
    p.add_argument("--price", type=float, default=2.30)
    args = p.parse_args()
    try:
        asyncio.run(main(args))
    except KeyboardInterrupt:
        sys.exit(130)
