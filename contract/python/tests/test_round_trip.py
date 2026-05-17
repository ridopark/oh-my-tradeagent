"""Round-trip parity test for generated pydantic models.

The fixtures in contract/fixtures/*.json are the source of truth. Both the
Java DTOs (jsonschema2pojo) and these pydantic models must losslessly
deserialize them and serialize back to a structurally-identical JSON
document. Failure here means contract drift between the two languages.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from ohmytradeagent_contract.models.arm_chandelier_payload import ArmChandelierPayload
from ohmytradeagent_contract.models.audit_event import AuditEvent
from ohmytradeagent_contract.models.copytrade_signal_payload import (
    Action,
    CopytradeSignalPayload,
    Right,
)
from ohmytradeagent_contract.models.partial_exit_request import PartialExitRequest
from ohmytradeagent_contract.models.pre_trade_check_request import (
    PreTradeCheckRequest,
    Side,
)
from ohmytradeagent_contract.models.pre_trade_check_result import (
    PdtStatus,
    PreTradeCheckResult,
)
from ohmytradeagent_contract.models.premium_tick import PremiumTick
from ohmytradeagent_contract.models.strategy_config import StrategyConfig
from ohmytradeagent_contract.models.subscribe_premium_request import SubscribePremiumRequest
from ohmytradeagent_contract.models.subscribe_premium_result import (
    Status,
    SubscribePremiumResult,
)
from ohmytradeagent_contract.types.broker_target import BrokerTarget

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


def test_pre_trade_check_request_round_trips() -> None:
    original = _load("pre-trade-check-request.json")

    model = PreTradeCheckRequest.model_validate(original)

    assert model.schema_version == 1
    assert model.tenant_id == "dev"
    assert model.strategy_id == "copytrade-v1"
    assert model.broker_target == BrokerTarget.paper
    assert model.side == Side.buy
    assert model.qty == 1
    assert model.estimated_notional == 230.0

    serialized = json.loads(model.model_dump_json(by_alias=True))
    assert serialized == original


def test_pre_trade_check_result_round_trips() -> None:
    original = _load("pre-trade-check-result.json")

    model = PreTradeCheckResult.model_validate(original)

    assert model.schema_version == 1
    assert model.allowed is True
    assert model.buying_power == 50000.0
    assert model.pdt_status == PdtStatus.ok
    assert model.margin_sufficient is True

    # reject_reason is an optional field — drop None values to compare against the fixture.
    serialized = json.loads(model.model_dump_json(by_alias=True, exclude_none=True))
    assert serialized == original


_STRATEGY_CONFIG_BASE = {
    "schema_version": 1,
    "tenant_id": "dev",
    "strategy_id": "copytrade-v1",
    "broker_target": "paper",
    "author_whitelist": ["acme_trader"],
    "max_signal_age_bto_secs": 30,
    "max_signal_age_stc_secs": 60,
    "max_positions": 5,
    "capital_weight": 0.2,
    "min_contracts": 1,
    "max_contracts": 5,
}


def test_strategy_config_trail_fields_positive() -> None:
    data = {**_STRATEGY_CONFIG_BASE, "trail_debounce_ticks": 1, "trail_disarm_minutes_before_close": 0}
    model = StrategyConfig.model_validate(data)
    assert model.trail_debounce_ticks == 1
    assert model.trail_disarm_minutes_before_close == 0
    # round-trip: both fields survive serialise → parse
    reloaded = StrategyConfig.model_validate_json(model.model_dump_json(by_alias=True, exclude_none=True))
    assert reloaded.trail_debounce_ticks == 1
    assert reloaded.trail_disarm_minutes_before_close == 0


def test_strategy_config_trail_debounce_ticks_zero_rejected() -> None:
    data = {**_STRATEGY_CONFIG_BASE, "trail_debounce_ticks": 0}
    with pytest.raises(ValidationError) as exc_info:
        StrategyConfig.model_validate(data)
    assert "trail_debounce_ticks" in str(exc_info.value)
