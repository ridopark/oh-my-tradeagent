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
