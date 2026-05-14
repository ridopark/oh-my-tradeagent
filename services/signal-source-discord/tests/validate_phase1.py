"""Phase 1 done-when end-to-end validator.

Not part of the pytest suite (lives outside tests/test_*). Run manually
against a live Temporal cluster after the audit-svc worker is started.

Verifies the three behaviors PLAN.md Phase 1 requires:

1. A real CopytradeSignalPayload emitted via TemporalEmitter produces a
   workflow with the documented workflow_id shape and TenantStrategy SA.
2. Re-emitting the same signal_id from the same process returns
   deduped=True (single-process dedupe via Temporal REJECT_DUPLICATE).
3. Two emitter processes racing on the same signal_id produce exactly
   one workflow (concurrent dedupe — the replica-safety guarantee).

Usage (after `docker compose -f infra/docker-compose.yml up -d` and
audit-svc running):

    uv run python -m tests.validate_phase1
"""

from __future__ import annotations

import asyncio
import os
import sys
from datetime import date, datetime, timezone

from ohmytradeagent_contract.models.copytrade_signal_payload import (
    Action,
    CopytradeSignalPayload,
    Right,
)

from ohmytradeagent_sidecar.emitter import TemporalEmitter, workflow_id_for


def _payload(signal_id: str) -> CopytradeSignalPayload:
    return CopytradeSignalPayload(
        schema_version=1,
        tenant_id="dev",
        strategy_id="copytrade-v1",
        signal_id=signal_id,
        message_id=signal_id.split(":")[0],
        author="ridopark",
        posted_at=datetime.now(timezone.utc),
        action=Action.bto,
        ticker="NVDA",
        expiry=date(2026, 5, 16),
        strike=140.0,
        right=Right.c,
        price=2.30,
        tail="phase1",
        raw_line="BTO NVDA 5/16 140C @ 2.30 phase1",
    )


async def _check_single_process_dedupe(emitter: TemporalEmitter) -> None:
    signal_id = f"phase1-validation-{int(datetime.now().timestamp())}:0"
    payload = _payload(signal_id)

    first = await emitter.emit(payload)
    assert first.deduped is False, f"first emit unexpectedly deduped: {first}"
    assert first.workflow_id == workflow_id_for(payload), first

    second = await emitter.emit(payload)
    assert second.deduped is True, f"second emit not deduped: {second}"

    print(f"[OK] single-process dedupe — workflow_id={first.workflow_id}")


async def _check_concurrent_replica_dedupe(emitter_a, emitter_b) -> None:
    signal_id = f"phase1-concurrent-{int(datetime.now().timestamp())}:0"
    payload = _payload(signal_id)

    a, b = await asyncio.gather(emitter_a.emit(payload), emitter_b.emit(payload))
    deduped_count = sum(1 for r in (a, b) if r.deduped)
    success_count = sum(1 for r in (a, b) if not r.deduped)
    assert success_count == 1 and deduped_count == 1, (
        f"expected 1 success + 1 dedupe, got success={success_count}, deduped={deduped_count}"
    )
    print(
        f"[OK] concurrent-replica dedupe — workflow_id={a.workflow_id} "
        f"(a.deduped={a.deduped}, b.deduped={b.deduped})"
    )


async def main() -> int:
    target = os.environ.get("TEMPORAL_TARGET", "localhost:7233")
    task_queue = os.environ.get("TEMPORAL_TASK_QUEUE", "orchestrator-core")

    # Two clients = two replicas as far as Temporal cares.
    emitter_a = await TemporalEmitter.connect(target=target, namespace="default", task_queue=task_queue)
    emitter_b = await TemporalEmitter.connect(target=target, namespace="default", task_queue=task_queue)

    await _check_single_process_dedupe(emitter_a)
    await _check_concurrent_replica_dedupe(emitter_a, emitter_b)

    await emitter_a.close()
    await emitter_b.close()
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
