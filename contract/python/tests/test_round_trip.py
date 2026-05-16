"""Round-trip parity test for generated pydantic models.

The fixtures in contract/fixtures/*.json are the source of truth. Both the
Java DTOs (jsonschema2pojo) and these pydantic models must losslessly
deserialize them and serialize back to a structurally-identical JSON
document. Failure here means contract drift between the two languages.
"""

from __future__ import annotations

import json
from pathlib import Path

from ohmytradeagent_contract.models.arm_chandelier_payload import ArmChandelierPayload
from ohmytradeagent_contract.models.audit_event import AuditEvent
from ohmytradeagent_contract.models.copytrade_signal_payload import (
    Action,
    CopytradeSignalPayload,
    Right,
)
from ohmytradeagent_contract.models.partial_exit_request import PartialExitRequest
from ohmytradeagent_contract.models.premium_tick import PremiumTick
from ohmytradeagent_contract.models.strategy_config import StrategyConfig
from ohmytradeagent_contract.models.subscribe_premium_request import SubscribePremiumRequest
from ohmytradeagent_contract.models.subscribe_premium_result import (
    Status,
    SubscribePremiumResult,
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


def test_partial_exit_request_round_trips() -> None:
    original = _load("partial-exit-request.json")

    model = PartialExitRequest.model_validate(original)

    assert model.schema_version == 1
    assert model.tenant_id == "dev"
    assert model.strategy_id == "copytrade-v1"
    assert model.fraction == 0.5
    assert model.reason == "stc_signal"

    serialized = json.loads(model.model_dump_json(by_alias=True))
    assert serialized == original


def test_premium_tick_round_trips() -> None:
    original = _load("premium-tick.json")

    model = PremiumTick.model_validate(original)

    assert model.schema_version == 1
    assert model.contract_symbol == "NVDA  260516C00140000"
    assert model.premium == 2.95

    serialized = json.loads(model.model_dump_json(by_alias=True))
    assert serialized == original


def test_arm_chandelier_payload_round_trips() -> None:
    original = _load("arm-chandelier-payload.json")

    model = ArmChandelierPayload.model_validate(original)

    assert model.schema_version == 1
    assert model.tenant_id == "dev"
    assert model.peak_premium == 2.85
    assert model.giveback_pct == 0.15

    serialized = json.loads(model.model_dump_json(by_alias=True))
    assert serialized == original


def test_subscribe_premium_request_round_trips() -> None:
    original = _load("subscribe-premium-request.json")

    model = SubscribePremiumRequest.model_validate(original)

    assert model.schema_version == 1
    assert model.contract_symbol == "NVDA  260516C00140000"

    serialized = json.loads(model.model_dump_json(by_alias=True))
    assert serialized == original


def test_strategy_config_round_trips() -> None:
    original = _load("strategy-config-copytrade-v1.json")

    model = StrategyConfig.model_validate(original)

    assert model.schema_version == 1
    assert model.tenant_id == "dev"
    assert model.strategy_id == "copytrade-v1"
    assert model.max_slippage_abs == 0.05
    assert model.max_slippage_pct == 0.05
    assert model.repeg_after_ms == 5000

    # StrategyConfig has many optional fields — drop None values to compare against the fixture.
    serialized = json.loads(model.model_dump_json(by_alias=True, exclude_none=True))
    assert serialized == original


def test_subscribe_premium_result_round_trips() -> None:
    original = _load("subscribe-premium-result.json")

    model = SubscribePremiumResult.model_validate(original)

    assert model.schema_version == 1
    assert model.subscription_id == "sub-7f3b1d40"
    assert model.status == Status.subscribed

    # error is an optional field — drop None values to compare against the fixture.
    serialized = json.loads(model.model_dump_json(by_alias=True, exclude_none=True))
    assert serialized == original
