"""Unit tests for the watcher's pure-logic surface.

The watcher's I/O paths (Playwright DOM scraping, file-based state) are
covered by integration tests against a snapshot HTML fixture in later
phases. Here we exercise:

- the bounded LRU eviction
- the payload-build mapping from ParsedSignal -> CopytradeSignalPayload
- the emit-one branching (dedup vs new)

The seams the public API exposes are the only things tested. Internal
helpers are reached via the class's documented constants and methods.
"""

from __future__ import annotations

import logging
import pathlib
from datetime import date

import pytest

from ohmytradeagent_sidecar.emitter import InMemoryEmitter
from ohmytradeagent_sidecar.parser import ParsedSignal
from ohmytradeagent_sidecar.watcher import Watcher, _BoundedSeenLRU


def _make_watcher(tmp_path: pathlib.Path) -> Watcher:
    return Watcher(
        channel_url="https://discord/channel/x",
        state_dir=tmp_path,
        emitter=InMemoryEmitter(),
        tenant_id="dev",
        strategy_id="copytrade-v1",
        log=logging.getLogger("test"),
        poll_interval_secs=1.0,
        lru_capacity=3,
    )


def test_bounded_lru_evicts_oldest_when_full() -> None:
    lru = _BoundedSeenLRU(capacity=3)
    for k in ["a", "b", "c"]:
        lru.add(k)
    assert "a" in lru
    lru.add("d")
    # "a" is the oldest and should be evicted.
    assert "a" not in lru
    assert {"b", "c", "d"} == set(iter(lru._items))  # type: ignore[attr-defined]


def test_bounded_lru_promotes_on_re_add() -> None:
    lru = _BoundedSeenLRU(capacity=3)
    for k in ["a", "b", "c"]:
        lru.add(k)
    lru.add("a")  # re-add promotes "a" to most-recent
    lru.add("d")  # evicts "b" (now oldest), not "a"
    assert "b" not in lru
    assert {"a", "c", "d"} == set(iter(lru._items))  # type: ignore[attr-defined]


def test_bounded_lru_rejects_invalid_capacity() -> None:
    with pytest.raises(ValueError):
        _BoundedSeenLRU(capacity=0)


def test_build_payload_maps_parsed_signal_fields(tmp_path: pathlib.Path) -> None:
    w = _make_watcher(tmp_path)
    parsed = ParsedSignal(
        action="BTO",
        ticker="NVDA",
        expiry=date(2026, 5, 16),
        strike=140.0,
        right="C",
        price=2.30,
        tail="small",
        raw_line="BTO NVDA 5/16 140C @ 2.30 small",
    )

    payload = w._build_payload(  # type: ignore[attr-defined]
        message_id="msg-1",
        line_index=0,
        author="ridopark",
        posted_at_iso="2026-05-16T13:35:00Z",
        sig=parsed,
    )

    assert payload.schema_version == 1
    assert payload.tenant_id == "dev"
    assert payload.strategy_id == "copytrade-v1"
    assert payload.signal_id == "msg-1:0"
    assert payload.ticker == "NVDA"
    assert payload.action.value == "BTO"
    assert payload.right.value == "C"
    assert payload.expiry == date(2026, 5, 16)


async def test_emit_one_logs_emitted_and_deduped_paths(
    tmp_path: pathlib.Path,
    caplog: pytest.LogCaptureFixture,
) -> None:
    w = _make_watcher(tmp_path)
    parsed = ParsedSignal(
        action="BTO",
        ticker="NVDA",
        expiry=date(2026, 5, 16),
        strike=140.0,
        right="C",
        price=2.30,
        tail="",
        raw_line="BTO NVDA 5/16 140C @ 2.30",
    )
    payload = w._build_payload(  # type: ignore[attr-defined]
        message_id="msg-1",
        line_index=0,
        author="ridopark",
        posted_at_iso="2026-05-16T13:35:00Z",
        sig=parsed,
    )

    caplog.set_level(logging.INFO, logger="test")
    await w._emit_one(payload)  # type: ignore[attr-defined]
    await w._emit_one(payload)  # type: ignore[attr-defined]  # second call -> deduped path

    messages = "\n".join(r.message for r in caplog.records)
    assert "emitted BTO NVDA" in messages
    assert "deduped msg-1:0" in messages


async def test_emit_signal_fans_out_to_all_targets(tmp_path: pathlib.Path) -> None:
    emitter = InMemoryEmitter()
    w = Watcher(
        channel_url="https://discord/channel/x",
        state_dir=tmp_path,
        emitter=emitter,
        tenant_id="prod_real",
        strategy_id="copytrade-v1",
        additional_targets=[("staging_paper", "copytrade-v1")],
        log=logging.getLogger("test"),
        poll_interval_secs=1.0,
    )
    parsed = ParsedSignal(
        action="BTO",
        ticker="NVDA",
        expiry=date(2026, 5, 16),
        strike=140.0,
        right="C",
        price=2.30,
        tail="",
        raw_line="BTO NVDA 5/16 140C @ 2.30",
    )

    await w._emit_signal(  # type: ignore[attr-defined]
        message_id="msg-1",
        line_index=0,
        author="ridopark",
        posted_at_iso="2026-05-16T13:35:00Z",
        sig=parsed,
    )

    # One parsed signal -> one workflow per target, same signal_id, distinct tenants.
    assert sorted(p.tenant_id for p in emitter.emitted) == ["prod_real", "staging_paper"]
    assert all(p.signal_id == "msg-1:0" for p in emitter.emitted)
    assert all(p.ticker == "NVDA" and p.action.value == "BTO" for p in emitter.emitted)


def test_parse_additional_targets() -> None:
    from ohmytradeagent_sidecar.main import _parse_additional_targets

    assert _parse_additional_targets("") == []
    assert _parse_additional_targets("  ") == []
    assert _parse_additional_targets("staging_paper:copytrade-v1") == [
        ("staging_paper", "copytrade-v1")
    ]
    assert _parse_additional_targets("a:x, b:y") == [("a", "x"), ("b", "y")]
    with pytest.raises(SystemExit):
        _parse_additional_targets("noseparator")
    with pytest.raises(SystemExit):
        _parse_additional_targets("tenant:")


def test_watchlist_targets_uses_dedicated_var_when_set(monkeypatch) -> None:
    from ohmytradeagent_sidecar.main import (
        _parse_additional_targets,
        _watchlist_targets,
    )

    signal_targets = _parse_additional_targets(
        "staging_paper:copytrade-v1,staging_paper:watchlist-trigger-v1"
    )
    monkeypatch.setenv(
        "WATCHLIST_MIRROR_ADDITIONAL_TARGETS", "staging_paper:watchlist-trigger-v1"
    )
    assert _watchlist_targets(signal_targets) == [
        ("staging_paper", "watchlist-trigger-v1")
    ]
    # Signal fan-out list is untouched.
    assert signal_targets == [
        ("staging_paper", "copytrade-v1"),
        ("staging_paper", "watchlist-trigger-v1"),
    ]


def test_watchlist_targets_unset_falls_back_to_signal_targets(monkeypatch) -> None:
    from ohmytradeagent_sidecar.main import _watchlist_targets

    monkeypatch.delenv("WATCHLIST_MIRROR_ADDITIONAL_TARGETS", raising=False)
    sig = [("staging_paper", "copytrade-v1")]
    assert _watchlist_targets(sig) == sig


def test_watchlist_targets_empty_string_is_empty_list(monkeypatch) -> None:
    from ohmytradeagent_sidecar.main import _watchlist_targets

    monkeypatch.setenv("WATCHLIST_MIRROR_ADDITIONAL_TARGETS", "")
    assert _watchlist_targets([("x", "y")]) == []


def test_watchlist_targets_malformed_raises(monkeypatch) -> None:
    from ohmytradeagent_sidecar.main import _watchlist_targets

    monkeypatch.setenv("WATCHLIST_MIRROR_ADDITIONAL_TARGETS", "noseparator")
    with pytest.raises(SystemExit):
        _watchlist_targets([("x", "y")])


# ---- de-risk cue integration (PLAN-2026-08-04-copytrade-derisk-followup-cue) ----

from datetime import date as _date  # noqa: E402

from ohmytradeagent_sidecar.emitter import InMemoryDeriskEmitter  # noqa: E402


def _make_derisk_watcher(
    tmp_path: pathlib.Path,
    derisk_emitter: InMemoryDeriskEmitter,
    additional_targets: list[tuple[str, str]] | None = None,
) -> Watcher:
    return Watcher(
        channel_url="https://discord/channel/x",
        state_dir=tmp_path,
        emitter=InMemoryEmitter(),
        tenant_id="dev",
        strategy_id="copytrade-v1",
        additional_targets=additional_targets,
        log=logging.getLogger("test"),
        poll_interval_secs=1.0,
        derisk_emitter=derisk_emitter,
    )


def _intc_bto() -> ParsedSignal:
    return ParsedSignal(
        action="BTO",
        ticker="INTC",
        expiry=_date(2026, 8, 3),
        strike=95.0,
        right="C",
        price=1.34,
        tail="risky",
        raw_line="BTO INTC 8/03 95c @ 1.34 risky",
    )


def _aapl_bto() -> ParsedSignal:
    return ParsedSignal(
        action="BTO",
        ticker="AAPL",
        expiry=_date(2026, 8, 3),
        strike=230.0,
        right="C",
        price=2.10,
        tail="",
        raw_line="BTO AAPL 8/03 230c @ 2.10",
    )


def test_watcher_disabled_has_no_tracker(tmp_path: pathlib.Path) -> None:
    w = _make_watcher(tmp_path)  # no derisk_emitter
    assert w._tracker is None  # type: ignore[attr-defined]


@pytest.mark.asyncio
async def test_derisk_cue_after_bto_emits_trim(tmp_path: pathlib.Path) -> None:
    dm = InMemoryDeriskEmitter()
    w = _make_derisk_watcher(tmp_path, dm)
    w._record_btos(  # type: ignore[attr-defined]
        author="TradingTheTrend",
        message_id="bto-1",
        posted_at_iso="2026-07-31T17:46:00Z",
        parsed=[_intc_bto()],
    )
    handled = await w._handle_derisk_cue(  # type: ignore[attr-defined]
        message_id="cue-1",
        author="TradingTheTrend",
        posted_at_iso="2026-07-31T17:56:00Z",
        content="I'm cool with going 0 or hero on these. Feel free to use your own stop",
    )
    assert handled is True
    assert len(dm.emitted) == 1
    p = dm.emitted[0]
    assert p.ticker == "INTC"
    assert p.signal_id == "cue-1:derisk"
    assert p.target_bto_signal_id == "bto-1:0"
    assert float(p.target_entry_premium) == 1.34
    assert p.matched_cue == "0 or hero"


@pytest.mark.asyncio
async def test_derisk_attributes_to_same_author_skipping_interleaved(
    tmp_path: pathlib.Path,
) -> None:
    dm = InMemoryDeriskEmitter()
    w = _make_derisk_watcher(tmp_path, dm)
    # TTT posts the INTC BTO...
    w._record_btos(  # type: ignore[attr-defined]
        author="TradingTheTrend",
        message_id="bto-1",
        posted_at_iso="2026-07-31T17:46:00Z",
        parsed=[_intc_bto()],
    )
    # ...another author posts a BTO in between...
    w._record_btos(  # type: ignore[attr-defined]
        author="SomeoneElse",
        message_id="bto-2",
        posted_at_iso="2026-07-31T17:50:00Z",
        parsed=[_aapl_bto()],
    )
    # ...then TTT posts the de-risk cue → must attribute to TTT's INTC, not the interleaved AAPL.
    await w._handle_derisk_cue(  # type: ignore[attr-defined]
        message_id="cue-1",
        author="TradingTheTrend",
        posted_at_iso="2026-07-31T17:56:00Z",
        content="0 or hero on these",
    )
    assert len(dm.emitted) == 1
    assert dm.emitted[0].ticker == "INTC"


@pytest.mark.asyncio
async def test_derisk_ticker_named_targets_that_ticker(tmp_path: pathlib.Path) -> None:
    dm = InMemoryDeriskEmitter()
    w = _make_derisk_watcher(tmp_path, dm)
    w._record_btos(  # type: ignore[attr-defined]
        author="TradingTheTrend",
        message_id="bto-1",
        posted_at_iso="2026-07-31T17:46:00Z",
        parsed=[_intc_bto()],
    )
    w._record_btos(  # type: ignore[attr-defined]
        author="TradingTheTrend",
        message_id="bto-2",
        posted_at_iso="2026-07-31T17:50:00Z",
        parsed=[_aapl_bto()],  # more recent
    )
    # Cue names INTC explicitly → must pick INTC even though AAPL is more recent.
    await w._handle_derisk_cue(  # type: ignore[attr-defined]
        message_id="cue-1",
        author="TradingTheTrend",
        posted_at_iso="2026-07-31T17:56:00Z",
        content="0 or hero on INTC",
    )
    assert len(dm.emitted) == 1
    assert dm.emitted[0].ticker == "INTC"


@pytest.mark.asyncio
async def test_derisk_cue_unattributed_no_emit(tmp_path: pathlib.Path) -> None:
    dm = InMemoryDeriskEmitter()
    w = _make_derisk_watcher(tmp_path, dm)
    # No preceding BTO recorded for this author.
    handled = await w._handle_derisk_cue(  # type: ignore[attr-defined]
        message_id="cue-1",
        author="TradingTheTrend",
        posted_at_iso="2026-07-31T17:56:00Z",
        content="0 or hero on these",
    )
    assert handled is False
    assert dm.emitted == []


@pytest.mark.asyncio
async def test_derisk_non_cue_message_no_emit(tmp_path: pathlib.Path) -> None:
    dm = InMemoryDeriskEmitter()
    w = _make_derisk_watcher(tmp_path, dm)
    w._record_btos(  # type: ignore[attr-defined]
        author="TradingTheTrend",
        message_id="bto-1",
        posted_at_iso="2026-07-31T17:46:00Z",
        parsed=[_intc_bto()],
    )
    handled = await w._handle_derisk_cue(  # type: ignore[attr-defined]
        message_id="cue-1",
        author="TradingTheTrend",
        posted_at_iso="2026-07-31T17:56:00Z",
        content="looking strong, holding here",  # not a de-risk cue
    )
    assert handled is False
    assert dm.emitted == []


@pytest.mark.asyncio
async def test_derisk_fans_out_to_all_targets(tmp_path: pathlib.Path) -> None:
    dm = InMemoryDeriskEmitter()
    w = _make_derisk_watcher(
        tmp_path, dm, additional_targets=[("staging_paper", "copytrade-v1")]
    )
    w._record_btos(  # type: ignore[attr-defined]
        author="TradingTheTrend",
        message_id="bto-1",
        posted_at_iso="2026-07-31T17:46:00Z",
        parsed=[_intc_bto()],
    )
    await w._handle_derisk_cue(  # type: ignore[attr-defined]
        message_id="cue-1",
        author="TradingTheTrend",
        posted_at_iso="2026-07-31T17:56:00Z",
        content="0 or hero on these",
    )
    assert {(p.tenant_id, p.strategy_id) for p in dm.emitted} == {
        ("dev", "copytrade-v1"),
        ("staging_paper", "copytrade-v1"),
    }
