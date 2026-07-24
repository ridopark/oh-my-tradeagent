"""Unit tests for the registry-driven copytrade fan-out (Phase B2).

Covers the isolated HTTP client (`FanoutRegistryClient`) and the fail-safe
refresh loop (`FanoutRefresher`) that swaps the watcher's target set, plus the
watcher's atomic `update_targets` union-with-primary + dedupe.

No real network: httpx.MockTransport drives the client. No real sleeps: the
refresher exposes a single-cycle `refresh_once()` the tests drive directly.
"""

from __future__ import annotations

import logging
import pathlib

import httpx
import pytest

from ohmytradeagent_sidecar.emitter import InMemoryEmitter
from ohmytradeagent_sidecar.fanout_registry import (
    WATCHLIST_FANOUT_TARGETS_PATH,
    FanoutRefresher,
    FanoutRegistryClient,
    parse_targets,
)
from ohmytradeagent_sidecar.watcher import Watcher

LOG = logging.getLogger("test-fanout")


def _watcher(tmp_path: pathlib.Path) -> Watcher:
    return Watcher(
        channel_url="https://discord/channel/x",
        state_dir=tmp_path,
        emitter=InMemoryEmitter(),
        tenant_id="prod_real",
        strategy_id="copytrade-v1",
        log=LOG,
        poll_interval_secs=1.0,
    )


def _client(handler) -> FanoutRegistryClient:
    transport = httpx.MockTransport(handler)
    ac = httpx.AsyncClient(transport=transport, base_url="http://gw:8082")
    return FanoutRegistryClient(base_url="http://gw:8082", token="tok", client=ac)


# --------------------------------------------------------------------------- #
# parse_targets                                                               #
# --------------------------------------------------------------------------- #
def test_parse_targets_accepts_wrapped_body() -> None:
    body = {
        "targets": [
            {"tenant_id": "a", "strategy_id": "copytrade-v1"},
            {"tenant_id": "b", "strategy_id": "copytrade-v1"},
        ],
        "count": 2,
    }
    assert parse_targets(body) == [("a", "copytrade-v1"), ("b", "copytrade-v1")]


def test_parse_targets_malformed_raises() -> None:
    with pytest.raises(ValueError):
        parse_targets({"targets": [{"tenant_id": "a"}]})  # missing strategy_id
    with pytest.raises(ValueError):
        parse_targets({"targets": [{"tenant_id": "", "strategy_id": "x"}]})  # empty
    with pytest.raises(ValueError):
        parse_targets({"count": 0})  # no 'targets' key
    with pytest.raises(ValueError):
        parse_targets("not-json-object")  # type: ignore[arg-type]


# --------------------------------------------------------------------------- #
# FanoutRegistryClient                                                        #
# --------------------------------------------------------------------------- #
async def test_client_fetch_targets_sends_bearer_and_parses() -> None:
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["auth"] = request.headers.get("Authorization", "")
        seen["path"] = request.url.path
        return httpx.Response(
            200,
            json={"targets": [{"tenant_id": "a", "strategy_id": "copytrade-v1"}], "count": 1},
        )

    c = _client(handler)
    targets = await c.fetch_targets()
    await c.aclose()

    assert targets == [("a", "copytrade-v1")]
    assert seen["auth"] == "Bearer tok"
    assert seen["path"] == "/internal/copytrade-fanout-targets"


async def test_client_non_2xx_raises() -> None:
    c = _client(lambda req: httpx.Response(500, text="boom"))
    with pytest.raises(httpx.HTTPStatusError):
        await c.fetch_targets()
    await c.aclose()


async def test_client_custom_path_hits_watchlist_endpoint() -> None:
    # Phase 2: the same client reads the watchlist registry when given its path.
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["path"] = request.url.path
        return httpx.Response(
            200,
            json={"targets": [{"tenant_id": "kip", "strategy_id": "watchlist-trigger-v1"}]},
        )

    transport = httpx.MockTransport(handler)
    ac = httpx.AsyncClient(transport=transport, base_url="http://gw:8082")
    c = FanoutRegistryClient(
        base_url="http://gw:8082", token="tok", client=ac, path=WATCHLIST_FANOUT_TARGETS_PATH
    )
    targets = await c.fetch_targets()
    await c.aclose()

    assert seen["path"] == "/internal/watchlist-fanout-targets"
    assert targets == [("kip", "watchlist-trigger-v1")]


# --------------------------------------------------------------------------- #
# watcher.update_targets — union(primary, registry) deduped, order-stable      #
# --------------------------------------------------------------------------- #
def test_update_targets_unions_primary_and_dedupes(tmp_path: pathlib.Path) -> None:
    w = _watcher(tmp_path)
    # registry list includes the primary itself + a dup — must collapse to one each.
    w.update_targets(
        [
            ("prod_real", "copytrade-v1"),  # == primary
            ("staging_paper", "copytrade-v1"),
            ("staging_paper", "copytrade-v1"),  # dup
        ]
    )
    assert w.targets == [
        ("prod_real", "copytrade-v1"),
        ("staging_paper", "copytrade-v1"),
    ]


def test_update_targets_empty_keeps_primary_only(tmp_path: pathlib.Path) -> None:
    w = _watcher(tmp_path)
    w.update_targets([])
    assert w.targets == [("prod_real", "copytrade-v1")]


# --------------------------------------------------------------------------- #
# FanoutRefresher — fail-safe last-good retention                             #
# --------------------------------------------------------------------------- #
async def test_refresh_applies_union_to_watcher(tmp_path: pathlib.Path) -> None:
    w = _watcher(tmp_path)
    c = _client(
        lambda req: httpx.Response(
            200,
            json={
                "targets": [
                    {"tenant_id": "prod_real", "strategy_id": "copytrade-v1"},
                    {"tenant_id": "staging_paper", "strategy_id": "copytrade-v1"},
                ]
            },
        )
    )
    r = FanoutRefresher(client=c, apply_targets=w.update_targets, log=LOG, refresh_secs=60)
    ok = await r.refresh_once()
    await c.aclose()

    assert ok is True
    assert w.targets == [
        ("prod_real", "copytrade-v1"),
        ("staging_paper", "copytrade-v1"),
    ]


async def test_refresh_picks_up_new_tenant_next_cycle(tmp_path: pathlib.Path) -> None:
    w = _watcher(tmp_path)
    responses = [
        {"targets": [{"tenant_id": "staging_paper", "strategy_id": "copytrade-v1"}]},
        {
            "targets": [
                {"tenant_id": "staging_paper", "strategy_id": "copytrade-v1"},
                {"tenant_id": "shadow_new", "strategy_id": "copytrade-v1"},
            ]
        },
    ]
    calls = {"n": 0}

    def handler(req: httpx.Request) -> httpx.Response:
        body = responses[min(calls["n"], len(responses) - 1)]
        calls["n"] += 1
        return httpx.Response(200, json=body)

    c = _client(handler)
    r = FanoutRefresher(client=c, apply_targets=w.update_targets, log=LOG, refresh_secs=60)

    await r.refresh_once()
    assert w.targets == [
        ("prod_real", "copytrade-v1"),
        ("staging_paper", "copytrade-v1"),
    ]

    await r.refresh_once()  # new tenant appears without restart
    await c.aclose()
    assert w.targets == [
        ("prod_real", "copytrade-v1"),
        ("staging_paper", "copytrade-v1"),
        ("shadow_new", "copytrade-v1"),
    ]


async def test_failing_poll_retains_last_good(
    tmp_path: pathlib.Path, caplog: pytest.LogCaptureFixture
) -> None:
    w = _watcher(tmp_path)
    calls = {"n": 0}

    def handler(req: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(
                200,
                json={"targets": [{"tenant_id": "staging_paper", "strategy_id": "copytrade-v1"}]},
            )
        return httpx.Response(503, text="down")

    c = _client(handler)
    r = FanoutRefresher(client=c, apply_targets=w.update_targets, log=LOG, refresh_secs=60)

    await r.refresh_once()
    good = list(w.targets)
    assert good == [("prod_real", "copytrade-v1"), ("staging_paper", "copytrade-v1")]

    caplog.set_level(logging.WARNING)
    ok = await r.refresh_once()  # now the endpoint is down
    await c.aclose()

    assert ok is False
    # LAST GOOD retained — never dropped to empty / primary-only.
    assert w.targets == good
    assert w.targets  # non-empty


async def test_empty_response_retains_last_good(tmp_path: pathlib.Path) -> None:
    """A healthy registry always contains at least the primary's own row, so an
    empty list signals a blip → treat like a failure and retain last good."""
    w = _watcher(tmp_path)
    calls = {"n": 0}

    def handler(req: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(
                200,
                json={"targets": [{"tenant_id": "staging_paper", "strategy_id": "copytrade-v1"}]},
            )
        return httpx.Response(200, json={"targets": []})

    c = _client(handler)
    r = FanoutRefresher(client=c, apply_targets=w.update_targets, log=LOG, refresh_secs=60)
    await r.refresh_once()
    good = list(w.targets)
    ok = await r.refresh_once()
    await c.aclose()

    assert ok is False
    assert w.targets == good


async def test_startup_endpoint_down_falls_back_to_primary(
    tmp_path: pathlib.Path,
) -> None:
    """First poll fails at startup → watcher keeps its constructed primary-only
    set (never empty)."""
    w = _watcher(tmp_path)
    assert w.targets == [("prod_real", "copytrade-v1")]  # constructed state

    c = _client(lambda req: httpx.Response(500, text="down"))
    r = FanoutRefresher(client=c, apply_targets=w.update_targets, log=LOG, refresh_secs=60)
    ok = await r.refresh_once()
    await c.aclose()

    assert ok is False
    assert w.targets == [("prod_real", "copytrade-v1")]  # unchanged, non-empty


async def test_repeated_failures_escalate_to_error(
    tmp_path: pathlib.Path, caplog: pytest.LogCaptureFixture
) -> None:
    w = _watcher(tmp_path)
    c = _client(lambda req: httpx.Response(500, text="down"))
    r = FanoutRefresher(
        client=c, apply_targets=w.update_targets, log=LOG, refresh_secs=60, error_threshold=3
    )
    caplog.set_level(logging.WARNING)
    await r.refresh_once()
    await r.refresh_once()
    await r.refresh_once()  # 3rd consecutive failure → ERROR
    await c.aclose()

    levels = [rec.levelno for rec in caplog.records]
    assert logging.ERROR in levels
