"""Round-trip parity test for generated pydantic models.

The fixtures in contract/fixtures/*.json are the source of truth. Both the
Java DTOs (jsonschema2pojo) and these pydantic models must losslessly
deserialize them and serialize back to a structurally-identical JSON
document. Failure here means contract drift between the two languages.
"""

from __future__ import annotations

import json
from decimal import Decimal
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
from ohmytradeagent_contract.models.fill_signal_payload import FillSignalPayload
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
from ohmytradeagent_contract.models.watchlist_mirror_payload import WatchlistMirrorPayload
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


def test_watchlist_mirror_payload_round_trips() -> None:
    original = _load("watchlist-mirror-payload.json")

    model = WatchlistMirrorPayload.model_validate(original)

    assert model.schema_version == 1
    assert model.tenant_id == "dev"
    assert model.strategy_id == "copytrade-v1"
    assert model.et_date.isoformat() == "2026-06-03"
    assert model.author == "TradingTheTrend"
    assert model.source_message_id == "1234567890123456789"
    # raw_text is carried verbatim, including newlines and irregular spacing.
    assert model.raw_text == original["raw_text"]

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
    assert model.premium == Decimal("2.95")

    serialized = json.loads(model.model_dump_json(by_alias=True))
    assert serialized == original


def test_arm_chandelier_payload_round_trips() -> None:
    original = _load("arm-chandelier-payload.json")

    model = ArmChandelierPayload.model_validate(original)

    assert model.schema_version == 1
    assert model.tenant_id == "dev"
    assert model.peak_premium == Decimal("2.85")
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
    assert model.max_slippage_abs == Decimal("0.05")
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
    assert model.estimated_notional == Decimal("230.0")

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


def test_strategy_config_force_close_fields_round_trip() -> None:
    """Issue #15: both force-close HH:MM ET overrides parse, round-trip, and absent is fine."""
    data = {
        **_STRATEGY_CONFIG_BASE,
        "force_close_0dte_et": "14:45",
        "force_close_eod_et": "15:45",
    }
    model = StrategyConfig.model_validate(data)
    assert model.force_close_0dte_et == "14:45"
    assert model.force_close_eod_et == "15:45"
    reloaded = StrategyConfig.model_validate_json(model.model_dump_json(by_alias=True, exclude_none=True))
    assert reloaded.force_close_0dte_et == "14:45"
    assert reloaded.force_close_eod_et == "15:45"

    # Absent case (the existing copytrade-v1 fixture) must still validate cleanly.
    absent = StrategyConfig.model_validate(_STRATEGY_CONFIG_BASE)
    assert absent.force_close_0dte_et is None
    assert absent.force_close_eod_et is None


def test_strategy_config_force_close_bad_format_rejected() -> None:
    """Issue #15: HH:MM regex rejects malformed times (no second-component, no 25:00)."""
    for bad in ("15:0", "25:00", "15:60", "1500", "noon"):
        with pytest.raises(ValidationError) as exc_info:
            StrategyConfig.model_validate({**_STRATEGY_CONFIG_BASE, "force_close_0dte_et": bad})
        assert "force_close_0dte_et" in str(exc_info.value)


def test_strategy_config_notional_cap_fields_round_trip() -> None:
    """Issue #17: both per-signal and per-day notional caps parse, round-trip, and absent is fine."""
    data = {
        **_STRATEGY_CONFIG_BASE,
        "max_notional_per_signal": 2500.0,
        "max_daily_notional_deployed": 25000.0,
    }
    model = StrategyConfig.model_validate(data)
    assert model.max_notional_per_signal == Decimal("2500.0")
    assert model.max_daily_notional_deployed == Decimal("25000.0")
    reloaded = StrategyConfig.model_validate_json(model.model_dump_json(by_alias=True, exclude_none=True))
    assert reloaded.max_notional_per_signal == Decimal("2500.0")
    assert reloaded.max_daily_notional_deployed == Decimal("25000.0")

    # Absent case (the existing copytrade-v1 fixture) must still validate cleanly — both fields are opt-in.
    absent = StrategyConfig.model_validate(_STRATEGY_CONFIG_BASE)
    assert absent.max_notional_per_signal is None
    assert absent.max_daily_notional_deployed is None


def test_strategy_config_notional_cap_non_positive_rejected() -> None:
    """Issue #17: both caps require exclusiveMinimum 0 — zero and negative must be rejected."""
    for field in ("max_notional_per_signal", "max_daily_notional_deployed"):
        for bad in (0, -1, -1000.0):
            with pytest.raises(ValidationError) as exc_info:
                StrategyConfig.model_validate({**_STRATEGY_CONFIG_BASE, field: bad})
            assert field in str(exc_info.value)


def _fill_signal_json(avg_fill_price: str, broker_order_id: str = "order-abc") -> bytes:
    """JSON-shape FillSignalPayload payload as Jackson would emit it from the Java side.

    ``avg_fill_price`` is interpolated verbatim — pass a bare number string
    (``"3.14"``) for the Jackson-shape canary, or a quoted string
    (``'"3.14"'``) for the legacy-input acceptance test.
    """
    return (
        b'{"brokerOrderId":"' + broker_order_id.encode() + b'",'
        b'"filledQty":1,'
        b'"avgFillPrice":' + avg_fill_price.encode() + b','
        b'"filledAt":"2026-05-26T13:35:00Z"}'
    )


def test_fill_signal_payload_decimal_wire_shape_canary() -> None:
    """Issue #189 wire-shape canary: bare JSON number ⇄ Decimal round-trip.

    Pydantic v2's default Decimal serialisation emits a JSON string ("3.14"),
    which would break the wire contract with the Java side's Jackson-shaped
    bare number (3.14). The regen.sh post-processor injects
    ConfigDict(json_encoders={Decimal: float}) into every model to keep the
    Python output bare-number-shaped. Any regression to string-shaped output
    will trip here before it can ship.
    """
    java_shape = _fill_signal_json("3.14")
    model = FillSignalPayload.model_validate_json(java_shape)

    assert model.avg_fill_price == Decimal("3.14")
    assert isinstance(model.avg_fill_price, Decimal)

    out_bytes = model.model_dump_json(by_alias=True).encode()

    assert out_bytes == java_shape, f"wire-shape drift: expected {java_shape!r}, got {out_bytes!r}"

    # Belt-and-braces: structural equality survives re-parse on both sides.
    assert json.loads(out_bytes) == json.loads(java_shape)


def test_decimal_field_accepts_bare_number_and_string_inputs() -> None:
    """Issue #189: Pydantic v2 Decimal fields must accept both bare-number and string JSON input.

    The Java side emits bare numbers. Some legacy audit records may carry
    string-shaped decimals. Both forms must parse to the same Decimal so
    reading historical journal rows never fails.
    """
    bare = _fill_signal_json("3.14", broker_order_id="x")
    quoted = _fill_signal_json('"3.14"', broker_order_id="x")

    m_bare = FillSignalPayload.model_validate_json(bare)
    m_quoted = FillSignalPayload.model_validate_json(quoted)

    assert m_bare.avg_fill_price == Decimal("3.14")
    assert m_quoted.avg_fill_price == Decimal("3.14")
    assert m_bare.avg_fill_price == m_quoted.avg_fill_price
