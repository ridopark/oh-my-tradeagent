"""Unit tests for the Emitter contract.

Exercises the InMemoryEmitter against the Protocol surface. TemporalEmitter
is exercised end-to-end against a live cluster in Phase 1 validation, not
here — keeping unit tests fast and dependency-free (KISS).
"""

from __future__ import annotations

from datetime import date, datetime, timezone

import pytest
from ohmytradeagent_contract.models.copytrade_signal_payload import (
    Action,
    CopytradeSignalPayload,
    Right,
)

from ohmytradeagent_sidecar.emitter import (
    Emitter,
    InMemoryEmitter,
    tenant_strategy_sa,
    workflow_id_for,
)


def _payload(signal_id: str = "1234567890123456789:0") -> CopytradeSignalPayload:
    return CopytradeSignalPayload(
        schema_version=1,
        tenant_id="dev",
        strategy_id="copytrade-v1",
        signal_id=signal_id,
        message_id="1234567890123456789",
        author="ridopark",
        posted_at=datetime(2026, 5, 16, 13, 35, 0, tzinfo=timezone.utc),
        action=Action.bto,
        ticker="NVDA",
        expiry=date(2026, 5, 16),
        strike=140.0,
        right=Right.c,
        price=2.30,
        tail="small",
        raw_line="BTO NVDA 5/16 140C @ 2.30 small",
    )


def test_workflow_id_shape_matches_plan() -> None:
    assert workflow_id_for(_payload()) == "t-dev/s-copytrade-v1/sig/1234567890123456789:0"


def test_tenant_strategy_sa_shape() -> None:
    assert tenant_strategy_sa(_payload()) == "t-dev/s-copytrade-v1"


async def test_first_emit_is_not_deduped() -> None:
    emitter: Emitter = InMemoryEmitter()
    result = await emitter.emit(_payload())
    assert result.deduped is False
    assert result.workflow_id == "t-dev/s-copytrade-v1/sig/1234567890123456789:0"


async def test_second_emit_with_same_signal_id_is_deduped() -> None:
    emitter: Emitter = InMemoryEmitter()
    await emitter.emit(_payload())
    second = await emitter.emit(_payload())
    assert second.deduped is True
    # The deduped emit does NOT count as another emitted payload.
    assert len(emitter.emitted) == 1  # type: ignore[attr-defined]


async def test_different_signal_ids_both_emit() -> None:
    emitter: Emitter = InMemoryEmitter()
    a = await emitter.emit(_payload("1234567890123456789:0"))
    b = await emitter.emit(_payload("1234567890123456789:1"))
    assert a.deduped is False
    assert b.deduped is False
    assert {a.workflow_id, b.workflow_id} == {
        "t-dev/s-copytrade-v1/sig/1234567890123456789:0",
        "t-dev/s-copytrade-v1/sig/1234567890123456789:1",
    }
