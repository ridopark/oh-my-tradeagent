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

from ohmytradeagent_contract.models.account_snapshot_request import AccountSnapshotRequest
from ohmytradeagent_contract.models.arm_chandelier_payload import ArmChandelierPayload
from ohmytradeagent_contract.models.audit_event import AuditEvent
from ohmytradeagent_contract.models.broker_credential_audit_request import (
    BrokerCredentialAuditRequest,
    ChangeType,
    Outcome,
)
from ohmytradeagent_contract.models.copytrade_derisk_payload import (
    CopytradeDeriskPayload,
    Right as DeriskRight,
)
from ohmytradeagent_contract.models.copytrade_entry_status import (
    CopytradeEntryStatus,
    State as EntryState,
)
from ohmytradeagent_contract.models.copytrade_signal_payload import (
    Action,
    CloseIntent,
    CopytradeSignalPayload,
    Right,
    Source,
)
from ohmytradeagent_contract.models.fill_signal_payload import FillSignalPayload
from ohmytradeagent_contract.models.order_intent import OrderIntent
from ohmytradeagent_contract.models.partial_exit_request import PartialExitRequest
from ohmytradeagent_contract.models.position_workflow_input import PositionWorkflowInput
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
from ohmytradeagent_contract.models.arm_context import ArmContext
from ohmytradeagent_contract.models.arm_decision import ArmDecision
from ohmytradeagent_contract.models.fire_decision import FireDecision
from ohmytradeagent_contract.models.watchlist_mirror_payload import WatchlistMirrorPayload
from ohmytradeagent_contract.models.watchlist_trigger_payload import (
    Action as TriggerAction,
    Direction,
    Right as TriggerRight,
    WatchlistTriggerPayload,
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

    # close_intent/close_confidence are optional (absent in the BTO fixture) → drop None
    # values before comparing to the fixture, mirroring the strategy_config round-trip.
    serialized = json.loads(model.model_dump_json(by_alias=True, exclude_none=True))
    assert serialized == original


def test_copytrade_derisk_payload_round_trips() -> None:
    original = _load("copytrade-derisk-payload.json")

    model = CopytradeDeriskPayload.model_validate(original)

    assert model.schema_version == 1
    assert model.tenant_id == "dev"
    assert model.strategy_id == "copytrade-v1"
    assert model.author == "TradingTheTrend"
    assert model.ticker == "INTC"
    assert model.right == DeriskRight.c
    assert model.target_bto_signal_id == "1234567890123456789:0"
    assert model.target_entry_premium == Decimal("1.34")
    assert model.matched_cue == "0 or hero"

    serialized = json.loads(model.model_dump_json(by_alias=True, exclude_none=True))
    assert serialized == original


def test_copytrade_derisk_payload_required_only() -> None:
    """Optional target_entry_premium / matched_cue are omissible → default to None."""
    base = {
        "schema_version": 1,
        "tenant_id": "dev",
        "strategy_id": "copytrade-v1",
        "signal_id": "m2:derisk",
        "message_id": "m2",
        "author": "TradingTheTrend",
        "posted_at": "2026-07-31T17:56:00Z",
        "ticker": "INTC",
        "expiry": "2026-08-03",
        "strike": 95.0,
        "right": "C",
        "target_bto_signal_id": "m1:0",
        "raw_line": "0 or hero",
    }
    model = CopytradeDeriskPayload.model_validate(base)
    assert model.target_entry_premium is None
    assert model.matched_cue is None
    reloaded = CopytradeDeriskPayload.model_validate_json(
        model.model_dump_json(by_alias=True, exclude_none=True)
    )
    assert reloaded.target_entry_premium is None
    assert reloaded.matched_cue is None


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


def test_broker_credential_audit_request_round_trips() -> None:
    original = _load("broker-credential-audit-request.json")

    model = BrokerCredentialAuditRequest.model_validate(original)

    assert model.schema_version == 1
    assert model.tenant_id == "dev"
    assert model.provider == "alpaca"
    assert model.change_type == ChangeType.rotate
    assert model.outcome == Outcome.saved
    assert model.broker_account_id == "PA3FKGPFYPLH"
    assert model.credential_version == 2
    assert model.kek_version == 1
    assert model.correlation_id == "req-7f3b1d40"

    # All optional fields populated in the fixture — no exclude_none needed.
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
    # An STC partial rests a bounded limit seeded from ref_premium — market is the opt-in
    # operator-trim placement, never the STC default.
    assert model.market is False

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
    # Phase 0 watchlist-trigger fields: the fixture carries their defaults explicitly.
    assert model.entry_mode == "BREAKOUT"
    assert model.watchlist_expiry_rule == "NEAREST_WEEKLY"
    assert model.gap_tolerance_pct == 0.005
    assert model.equity_emit_delta_pct == 0.0005
    assert model.enabled is True

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


def test_order_intent_broker_account_id_optional_round_trip() -> None:
    """P4-c-b-2: OrderIntent.broker_account_id is optional; present round-trips, absent is fine."""
    base = _load("order-intent-bto.json")
    absent = OrderIntent.model_validate(base)
    assert absent.broker_account_id is None
    with_account = OrderIntent.model_validate({**base, "broker_account_id": "PA3FKGPFYPLH"})
    assert with_account.broker_account_id == "PA3FKGPFYPLH"
    reloaded = OrderIntent.model_validate_json(
        with_account.model_dump_json(by_alias=True, exclude_none=True)
    )
    assert reloaded.broker_account_id == "PA3FKGPFYPLH"


def test_account_snapshot_request_tenant_id_optional_round_trip() -> None:
    """P4-c-b: AccountSnapshotRequest.tenant_id is optional; present round-trips, absent is fine."""
    base = {"schema_version": 1, "broker_target": "alpaca-paper"}
    absent = AccountSnapshotRequest.model_validate(base)
    assert absent.tenant_id is None
    with_tenant = AccountSnapshotRequest.model_validate({**base, "tenant_id": "staging_paper"})
    assert with_tenant.tenant_id == "staging_paper"
    reloaded = AccountSnapshotRequest.model_validate_json(
        with_tenant.model_dump_json(by_alias=True, exclude_none=True)
    )
    assert reloaded.tenant_id == "staging_paper"


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


def test_strategy_config_author_whitelist_optional_round_trip() -> None:
    """#459: author_whitelist is optional — a non-copytrade (e.g. watchlist-trigger) strategy
    config must validate without it, no sentinel value required."""
    data = {k: v for k, v in _STRATEGY_CONFIG_BASE.items() if k != "author_whitelist"}
    data["strategy_id"] = "watchlist-trigger-v1"

    model = StrategyConfig.model_validate(data)

    assert model.author_whitelist is None
    reloaded = json.loads(model.model_dump_json(by_alias=True, exclude_none=True))
    assert "author_whitelist" not in reloaded


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


def test_strategy_config_broker_account_id_round_trip() -> None:
    """P4-c-a: optional broker_account_id parses, round-trips, and absent is fine."""
    data = {**_STRATEGY_CONFIG_BASE, "broker_account_id": "847309116"}
    model = StrategyConfig.model_validate(data)
    assert model.broker_account_id == "847309116"
    reloaded = StrategyConfig.model_validate_json(model.model_dump_json(by_alias=True, exclude_none=True))
    assert reloaded.broker_account_id == "847309116"

    # Absent case (the existing copytrade-v1 fixture) must still validate cleanly.
    absent = StrategyConfig.model_validate(_STRATEGY_CONFIG_BASE)
    assert absent.broker_account_id is None


def test_copytrade_signal_payload_close_intent_round_trip() -> None:
    """PLAN-2026-07-25: optional close_intent/close_confidence parse, round-trip, absent → None."""
    base = _load("copytrade-signal-payload-bto.json")
    enriched = {**base, "action": "STC", "close_intent": "full", "close_confidence": 0.92}
    model = CopytradeSignalPayload.model_validate(enriched)
    assert model.close_intent == CloseIntent.full
    assert model.close_confidence == 0.92
    reloaded = CopytradeSignalPayload.model_validate_json(
        model.model_dump_json(by_alias=True, exclude_none=True)
    )
    assert reloaded.close_intent == CloseIntent.full
    assert reloaded.close_confidence == 0.92

    # Absent case (the existing BTO fixture) validates cleanly and defaults to None.
    absent = CopytradeSignalPayload.model_validate(base)
    assert absent.close_intent is None
    assert absent.close_confidence is None

    # close_intent is a constrained enum; close_confidence is bounded [0, 1].
    with pytest.raises(ValidationError):
        CopytradeSignalPayload.model_validate({**base, "close_intent": "scale"})
    with pytest.raises(ValidationError):
        CopytradeSignalPayload.model_validate({**base, "close_confidence": 1.5})


def test_strategy_config_stc_intent_enforce_round_trip() -> None:
    """PLAN-2026-07-25: optional stc_intent_enforce parses, round-trips, absent → None (disabled)."""
    model = StrategyConfig.model_validate({**_STRATEGY_CONFIG_BASE, "stc_intent_enforce": True})
    assert model.stc_intent_enforce is True
    reloaded = StrategyConfig.model_validate_json(
        model.model_dump_json(by_alias=True, exclude_none=True)
    )
    assert reloaded.stc_intent_enforce is True

    # Absent → None (feature disabled, behavior-neutral).
    absent = StrategyConfig.model_validate(_STRATEGY_CONFIG_BASE)
    assert absent.stc_intent_enforce is None


def test_strategy_config_derisk_fields_round_trip() -> None:
    """PLAN-2026-08-04: optional derisk_on_followup_cue / derisk_keep_fraction parse, round-trip, absent -> None."""
    data = {
        **_STRATEGY_CONFIG_BASE,
        "derisk_on_followup_cue": True,
        "derisk_keep_fraction": 0.25,
    }
    model = StrategyConfig.model_validate(data)
    assert model.derisk_on_followup_cue is True
    assert model.derisk_keep_fraction == 0.25
    reloaded = StrategyConfig.model_validate_json(
        model.model_dump_json(by_alias=True, exclude_none=True)
    )
    assert reloaded.derisk_on_followup_cue is True
    assert reloaded.derisk_keep_fraction == 0.25

    # Absent -> None (feature disabled, behavior-neutral).
    absent = StrategyConfig.model_validate(_STRATEGY_CONFIG_BASE)
    assert absent.derisk_on_followup_cue is None
    assert absent.derisk_keep_fraction is None

    # derisk_keep_fraction is bounded (0, 1].
    with pytest.raises(ValidationError):
        StrategyConfig.model_validate({**_STRATEGY_CONFIG_BASE, "derisk_keep_fraction": 0})
    with pytest.raises(ValidationError):
        StrategyConfig.model_validate({**_STRATEGY_CONFIG_BASE, "derisk_keep_fraction": 1.5})


def test_strategy_config_force_close_bad_format_rejected() -> None:
    """Issue #15: HH:MM regex rejects malformed times (no second-component, no 25:00)."""
    for bad in ("15:0", "25:00", "15:60", "1500", "noon"):
        with pytest.raises(ValidationError) as exc_info:
            StrategyConfig.model_validate({**_STRATEGY_CONFIG_BASE, "force_close_0dte_et": bad})
        assert "force_close_0dte_et" in str(exc_info.value)


_POSITION_WORKFLOW_INPUT_BASE = {
    "schema_version": 1,
    "tenant_id": "dev",
    "strategy_id": "copytrade-v1",
    "entry_signal_id": "entry-1",
    "contract_symbol": "NVDA250516C00140000",
    "qty": 3,
    "entry_premium": 2.30,
}


def test_position_workflow_input_force_close_0dte_round_trip() -> None:
    """Issue #15: force_close_0dte_et parses, round-trips, and absent → None (legacy 15:30 path)."""
    data = {**_POSITION_WORKFLOW_INPUT_BASE, "force_close_0dte_et": "14:00"}
    model = PositionWorkflowInput.model_validate(data)
    assert model.force_close_0dte_et == "14:00"
    reloaded = PositionWorkflowInput.model_validate_json(
        model.model_dump_json(by_alias=True, exclude_none=True)
    )
    assert reloaded.force_close_0dte_et == "14:00"

    # Absent case (pre-change in-flight inputs) must validate cleanly and default to None.
    absent = PositionWorkflowInput.model_validate(_POSITION_WORKFLOW_INPUT_BASE)
    assert absent.force_close_0dte_et is None


def test_position_workflow_input_force_close_0dte_bad_format_rejected() -> None:
    """Issue #15: HH:MM regex rejects malformed force_close_0dte_et values."""
    for bad in ("14:0", "25:00", "14:60", "1400", "2pm"):
        with pytest.raises(ValidationError) as exc_info:
            PositionWorkflowInput.model_validate(
                {**_POSITION_WORKFLOW_INPUT_BASE, "force_close_0dte_et": bad}
            )
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


def test_watchlist_trigger_payload_round_trips() -> None:
    original = _load("watchlist-trigger-payload.json")

    model = WatchlistTriggerPayload.model_validate(original)

    assert model.schema_version == 1
    assert model.tenant_id == "dev"
    assert model.strategy_id == "watchlist-trigger-v1"
    assert model.ticker == "AAPL"
    assert model.direction == Direction.above
    assert model.trigger == 195.5
    assert model.strike == Decimal("200.0")
    assert model.right == TriggerRight.c
    assert model.action == TriggerAction.bto
    assert model.et_date.isoformat() == "2026-06-03"
    assert model.source_message_id == "1234567890123456789"

    serialized = json.loads(model.model_dump_json(by_alias=True))
    assert serialized == original


def test_arm_decision_round_trips() -> None:
    original = _load("arm-decision.json")

    model = ArmDecision.model_validate(original)

    assert model.arm is True
    assert model.size_multiplier == 1.0
    assert model.reason == "trigger_armed"

    serialized = json.loads(model.model_dump_json(by_alias=True, exclude_none=True))
    assert serialized == original


def test_fire_decision_round_trips() -> None:
    original = _load("fire-decision.json")

    model = FireDecision.model_validate(original)

    assert model.proceed is True
    assert model.size_multiplier == 0.5
    assert model.reason == "breakout_confirmed"

    serialized = json.loads(model.model_dump_json(by_alias=True, exclude_none=True))
    assert serialized == original


def test_arm_context_round_trips() -> None:
    original = _load("arm-context.json")

    model = ArmContext.model_validate(original)

    assert model.et_date.isoformat() == "2026-06-03"
    assert model.cash == 50000.0

    serialized = json.loads(model.model_dump_json(by_alias=True, exclude_none=True))
    assert serialized == original


def test_strategy_config_watchlist_only_fields_null_when_absent() -> None:
    """The four watchlist-only fields are opt-in: null when a config omits them, so
    copytrade never carries them. Only enabled (universal) keeps its default."""
    model = StrategyConfig.model_validate(_STRATEGY_CONFIG_BASE)
    assert model.entry_mode is None
    assert model.watchlist_expiry_rule is None
    assert model.gap_tolerance_pct is None
    assert model.equity_emit_delta_pct is None
    assert model.enabled is True


def test_strategy_config_create_request_account_cap_round_trip() -> None:
    """PLAN-2026-08-05: optional account_daily_loss_pct parses, round-trips, absent → None."""
    from ohmytradeagent_contract.models.strategy_config_create_request import (
        StrategyConfigCreateRequest,
    )

    base = {
        "schema_version": 1,
        "tenant_id": "new_tenant",
        "strategy_id": "copytrade-v1",
        "operator_id": "ridopark@gmail.com",
        "config": _STRATEGY_CONFIG_BASE,
    }
    m = StrategyConfigCreateRequest.model_validate({**base, "account_daily_loss_pct": 0.20})
    assert m.account_daily_loss_pct == 0.20
    reloaded = StrategyConfigCreateRequest.model_validate_json(
        m.model_dump_json(by_alias=True, exclude_none=True)
    )
    assert reloaded.account_daily_loss_pct == 0.20

    absent = StrategyConfigCreateRequest.model_validate(base)
    assert absent.account_daily_loss_pct is None

    with pytest.raises(ValidationError):
        StrategyConfigCreateRequest.model_validate({**base, "account_daily_loss_pct": 0})
    with pytest.raises(ValidationError):
        StrategyConfigCreateRequest.model_validate({**base, "account_daily_loss_pct": 1.5})


def test_copytrade_signal_payload_manual_entry_fields_round_trip() -> None:
    """PLAN-2026-08-10-live-manual-bto: optional source/qty_override parse, round-trip, absent → None."""
    base = _load("copytrade-signal-payload-bto.json")
    manual = {**base, "source": "manual", "qty_override": 3}

    model = CopytradeSignalPayload.model_validate(manual)
    assert model.source == Source.manual
    assert model.qty_override == 3

    serialized = json.loads(model.model_dump_json(by_alias=True, exclude_none=True))
    assert serialized == manual

    # Absent case (every sidecar-emitted signal, incl. the existing BTO fixture) → None. This is
    # the invariant the orchestrator's manual-entry branches key off: no Discord signal may ever
    # look manual.
    absent = CopytradeSignalPayload.model_validate(base)
    assert absent.source is None
    assert absent.qty_override is None

    # qty_override is a contract COUNT: zero and negatives are not orders.
    with pytest.raises(ValidationError):
        CopytradeSignalPayload.model_validate({**base, "qty_override": 0})
    with pytest.raises(ValidationError):
        CopytradeSignalPayload.model_validate({**base, "source": "api"})


def test_copytrade_entry_status_round_trips() -> None:
    """PLAN-2026-08-10-live-manual-bto: the entryStatus Query result."""
    pending = CopytradeEntryStatus.model_validate({"schema_version": 1, "state": "PENDING"})
    assert pending.state == EntryState.pending
    assert pending.reason_code is None

    rejected = {
        "schema_version": 1,
        "state": "REJECTED",
        "reason_code": "MANUAL_QTY_OUT_OF_BOUNDS",
        "reason_detail": "requested=99 max_contracts=5",
    }
    model = CopytradeEntryStatus.model_validate(rejected)
    assert model.state == EntryState.rejected
    assert json.loads(model.model_dump_json(by_alias=True, exclude_none=True)) == rejected

    filled = {
        "schema_version": 1,
        "state": "FILLED",
        "option_symbol": "NVDA  260821C00225000",
        "contracts": 3,
        "broker_order_id": "bo-1",
        "filled_qty": 3,
        "avg_fill_price": 2.34,
    }
    fill = CopytradeEntryStatus.model_validate(filled)
    assert fill.state == EntryState.filled
    assert fill.avg_fill_price == Decimal("2.34")
    assert json.loads(fill.model_dump_json(by_alias=True, exclude_none=True)) == filled
