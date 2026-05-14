"""Round-trip parity test for generated pydantic models.

The fixtures in contract/fixtures/*.json are the source of truth. Both the
Java DTOs (jsonschema2pojo) and these pydantic models must losslessly
deserialize them and serialize back to a structurally-identical JSON
document. Failure here means contract drift between the two languages.
"""

from __future__ import annotations

import json
from pathlib import Path

from ohmytradeagent_contract.models.audit_event import AuditEvent
from ohmytradeagent_contract.models.copytrade_signal_payload import (
    Action,
    CopytradeSignalPayload,
    Right,
)

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures"


def _load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text())


def test_copytrade_signal_payload_round_trips() -> None:
    original = _load("copytrade-signal-payload-bto.json")

    model = CopytradeSignalPayload.model_validate(original)

    assert model.schema_version == 1
    assert model.tenant_id == "dev"
    assert model.strategy_id == "copytrade-v1"
    assert model.action == Action.bto
    assert model.ticker == "NVDA"
    assert model.right == Right.c

    serialized = json.loads(model.model_dump_json(by_alias=True))
    assert serialized == original


def test_audit_event_round_trips() -> None:
    original = _load("audit-event.json")

    model = AuditEvent.model_validate(original)

    assert model.schema_version == 1
    assert model.tenant_id == "dev"
    assert model.kind == "SignalReceived"
    assert model.subject["signal_id"] == "1234567890123456789:0"

    serialized = json.loads(model.model_dump_json(by_alias=True))
    assert serialized == original
