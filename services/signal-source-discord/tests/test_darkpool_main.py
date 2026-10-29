"""Cycle / loop tests for the dark-pool collector (PLAN-2026-10-29-darkpool-prints, phase 2).

Alpaca's REST data API and the BFF ingest route are both ``httpx.MockTransport`` fakes. Nothing
here opens a socket of any kind.
"""

from __future__ import annotations

import json
import logging
import os
import pathlib
from datetime import datetime, timedelta, timezone

import httpx
import pytest

from ohmytradeagent_sidecar import darkpool_main
from ohmytradeagent_sidecar.darkpool_collector import AlpacaDataClient, Cursor, bucket5m, parse_ts
from ohmytradeagent_sidecar.darkpool_main import (
    DarkpoolCollector,
    DarkpoolIngestClient,
    in_poll_window,
    parse_dry_run,
)

LOG = logging.getLogger("test.darkpool_main")
UTC = timezone.utc
TOKEN = "ingest-token"
NOW = datetime(2026, 10, 29, 13, 40, tzinfo=UTC)  # 09:40 ET
PRINT_KEYS = [
    "trade_id",
    "symbol",
    "ts",
    "price",
    "size",
    "notional",
    "exchange",
    "conditions",
    "tape",
    "bid",
    "ask",
    "quote_ts",
    "side",
]
BAR_KEYS = [
    "symbol",
    "bucket",
    "dp_volume",
    "dp_trades",
    "total_volume",
    "total_trades",
    "buy_volume",
    "sell_volume",
    "large_count",
    "large_volume",
    "dp_vwap",
]


def raw(ts: str, i: int, *, p: float = 500.0, s: int = 100, x: str = "V") -> dict:
    return {"t": ts, "p": p, "s": s, "x": x, "i": i, "c": ["@"], "z": "C"}


class FakeAlpaca:
    """One trades page per symbol; ``fail[symbol]`` injects statuses/exceptions in order."""

    def __init__(self) -> None:
        self.trades: dict[str, list[dict]] = {}
        self.quotes: dict[str, list[dict]] = {}
        self.fail: dict[str, list] = {}
        self.requests: list[httpx.Request] = []
        self.sleeps: list[float] = []
        self.respect_start = True  # False: return rows before `start` too (a lax server)

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        sym = request.url.params["symbols"]
        if self.fail.get(sym):
            nxt = self.fail[sym].pop(0)
            if isinstance(nxt, Exception):
                raise nxt
            return httpx.Response(nxt, json={"message": "injected"})
        if request.url.path == "/v2/stocks/trades":
            start = parse_ts(request.url.params["start"])
            rows = [
                r
                for r in self.trades.get(sym, [])
                if not self.respect_start or parse_ts(r["t"]) >= start
            ]
            return httpx.Response(200, json={"trades": {sym: rows}, "next_page_token": None})
        if request.url.path == "/v2/stocks/quotes":
            end = parse_ts(request.url.params["end"])
            rows = sorted(
                (r for r in self.quotes.get(sym, []) if parse_ts(r["t"]) <= end),
                key=lambda r: r["t"],
                reverse=True,
            )[:1]
            return httpx.Response(200, json={"quotes": {sym: rows}, "next_page_token": None})
        return httpx.Response(404)

    def client(self, **kw) -> AlpacaDataClient:
        async def fake_sleep(secs: float) -> None:
            self.sleeps.append(secs)

        return AlpacaDataClient(
            key_id="kid",
            secret="sec",
            log=LOG,
            client=httpx.AsyncClient(
                transport=httpx.MockTransport(self.handler), base_url="https://data.test"
            ),
            sleep=fake_sleep,
            **kw,
        )


class FakeBff:
    def __init__(self) -> None:
        self.requests: list[httpx.Request] = []
        self.fail_next: list = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if self.fail_next:
            nxt = self.fail_next.pop(0)
            if isinstance(nxt, Exception):
                raise nxt
            return httpx.Response(nxt, json={"error": "injected"})
        assert request.url.path == "/internal/darkpool/ingest"
        assert request.headers["Authorization"] == f"Bearer {TOKEN}"
        body = json.loads(request.content)
        return httpx.Response(
            200, json={"stored_prints": len(body["prints"]), "stored_bars": len(body["bars"])}
        )

    def bodies(self) -> list[dict]:
        return [json.loads(r.content) for r in self.requests]

    def client(self, **kw) -> DarkpoolIngestClient:
        async def no_sleep(_s: float) -> None:
            return None

        return DarkpoolIngestClient(
            base_url="http://bff:8083",
            token=TOKEN,
            log=LOG,
            client=httpx.AsyncClient(
                transport=httpx.MockTransport(self.handler), base_url="http://bff:8083"
            ),
            sleep=no_sleep,
            **kw,
        )


class Clock:
    def __init__(self, now: datetime) -> None:
        self.now = now

    def __call__(self) -> datetime:
        return self.now


def make_collector(
    alpaca: FakeAlpaca,
    bff: FakeBff,
    universe: list[str],
    *,
    dry_run: bool = False,
    clock: Clock | None = None,
    max_requests: int = 2000,
    heartbeat=None,
    data: AlpacaDataClient | None = None,
) -> DarkpoolCollector:
    return DarkpoolCollector(
        data=data or alpaca.client(max_requests=max_requests),
        ingest=bff.client(),
        universe=universe,
        min_notional=200_000,
        min_size=10_000,
        lookback_minutes=10,
        dry_run=dry_run,
        log=LOG,
        clock=clock or Clock(NOW),
        heartbeat=heartbeat,
    )


def seed_kept_print(alpaca: FakeAlpaca, sym: str = "QQQ") -> None:
    """One lit trade + one kept D print (notional 200,000) with an NBBO 1 s before it."""
    alpaca.trades[sym] = [
        raw("2026-10-29T13:38:00Z", 1, x="V"),
        raw("2026-10-29T13:38:10Z", 2, p=500.0, s=400, x="D"),
    ]
    alpaca.quotes[sym] = [{"t": "2026-10-29T13:38:09Z", "bp": 499.98, "ap": 500.00}]


# ---------------------------------------------------------------------------
# dry-run
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "raw_env,expected",
    [(None, True), ("", True), ("true", True), ("FALSE", False), ("false", False), ("0", True)],
)
def test_parse_dry_run_only_literal_false_posts(raw_env, expected):
    assert parse_dry_run(raw_env) is expected


async def test_dry_run_posts_nothing_but_logs_the_summary(caplog):
    alpaca, bff = FakeAlpaca(), FakeBff()
    seed_kept_print(alpaca)
    collector = make_collector(alpaca, bff, ["QQQ"], dry_run=True)
    with caplog.at_level(logging.INFO, logger="test.darkpool_main"):
        stats = await collector.cycle()
    assert bff.requests == []
    assert (stats.symbols, stats.kept_prints, stats.bars) == (1, 1, 1)
    assert stats.requests == 2  # 1 trades page + 1 NBBO lookup
    line = [r.message for r in caplog.records if r.message.startswith("darkpool cycle")]
    assert len(line) == 1
    assert "symbols=1" in line[0]
    assert "requests=2" in line[0]
    assert "kept_prints=1" in line[0]
    assert "bars=1" in line[0]
    assert "dry_run=True" in line[0]


# ---------------------------------------------------------------------------
# live cycle: payload shape, chunking, isolation, budget, 429
# ---------------------------------------------------------------------------


async def test_live_cycle_posts_snake_case_prints_and_bars():
    alpaca, bff = FakeAlpaca(), FakeBff()
    seed_kept_print(alpaca)
    collector = make_collector(alpaca, bff, ["QQQ"])
    stats = await collector.cycle()
    assert stats.posted is True
    (body,) = bff.bodies()
    assert list(body) == ["prints", "bars"]
    (p,) = body["prints"]
    assert list(p) == PRINT_KEYS
    assert p == {
        "trade_id": 2,
        "symbol": "QQQ",
        "ts": "2026-10-29T13:38:10Z",
        "price": 500.0,
        "size": 400,
        "notional": 200000.0,
        "exchange": "D",
        "conditions": "@",
        "tape": "C",
        "bid": 499.98,
        "ask": 500.0,
        "quote_ts": "2026-10-29T13:38:09Z",
        "side": "buy",
    }
    assert isinstance(p["trade_id"], int)
    (b,) = body["bars"]
    assert list(b) == BAR_KEYS
    assert b == {
        "symbol": "QQQ",
        "bucket": "2026-10-29T13:35:00Z",
        "dp_volume": 400,
        "dp_trades": 1,
        "total_volume": 500,
        "total_trades": 2,
        "buy_volume": 400,
        "sell_volume": 0,
        "large_count": 1,
        "large_volume": 400,
        "dp_vwap": 500.0,
    }


async def test_print_without_a_quote_is_unknown_with_null_nbbo():
    alpaca, bff = FakeAlpaca(), FakeBff()
    seed_kept_print(alpaca)
    alpaca.quotes["QQQ"] = []
    collector = make_collector(alpaca, bff, ["QQQ"])
    await collector.cycle()
    (p,) = bff.bodies()[0]["prints"]
    assert (p["bid"], p["ask"], p["quote_ts"], p["side"]) == (None, None, None, "unknown")


async def test_ingest_chunks_to_5000_prints_and_5000_bars_per_post():
    bff = FakeBff()
    client = bff.client()
    prints = [{"trade_id": i, "symbol": "QQQ"} for i in range(5001)]
    bars = [{"symbol": "QQQ", "bucket": str(i)} for i in range(5001)]
    assert await client.post(prints, bars) is True
    sizes = [(len(b["prints"]), len(b["bars"])) for b in bff.bodies()]
    assert sizes == [(5000, 5000), (1, 1)]


async def test_ingest_retries_a_5xx_then_gives_up():
    bff = FakeBff()
    client = bff.client(max_attempts=2)
    bff.fail_next = [500]
    assert await client.post([], [{"symbol": "QQQ"}]) is True
    assert len(bff.requests) == 2
    bff.fail_next = [503, 503]
    assert await client.post([], [{"symbol": "QQQ"}]) is False
    assert len(bff.requests) == 4


async def test_ingest_404_and_401_are_not_retried():
    bff = FakeBff()
    client = bff.client()
    bff.fail_next = [404]
    assert await client.post([], [{"symbol": "QQQ"}]) is False
    bff.fail_next = [401]
    assert await client.post([], [{"symbol": "QQQ"}]) is False
    assert len(bff.requests) == 2


async def test_ingest_empty_batch_is_a_noop():
    bff = FakeBff()
    assert await bff.client().post([], []) is True
    assert bff.requests == []


async def test_a_500_from_one_symbol_does_not_block_the_next():
    alpaca, bff = FakeAlpaca(), FakeBff()
    seed_kept_print(alpaca, "AAA")
    seed_kept_print(alpaca, "BBB")
    alpaca.fail["AAA"] = [500]
    collector = make_collector(alpaca, bff, ["AAA", "BBB"])
    stats = await collector.cycle()
    assert stats.errors == 1
    assert stats.kept_prints == 1
    (body,) = bff.bodies()
    assert [p["symbol"] for p in body["prints"]] == ["BBB"]
    assert [b["symbol"] for b in body["bars"]] == ["BBB"]
    # AAA's cursor did not move: it is still the lookback start, so the next cycle re-reads it
    assert collector.cursors["AAA"] == Cursor.start(NOW - timedelta(minutes=10))
    assert collector.cursors["BBB"].last_ids == frozenset({2})
    # next cycle: AAA recovers and is posted; BBB has nothing new (cursor excludes the seen rows)
    stats = await collector.cycle()
    assert stats.errors == 0
    assert [p["symbol"] for p in bff.bodies()[1]["prints"]] == ["AAA"]


async def test_transport_error_is_isolated_like_a_500():
    alpaca, bff = FakeAlpaca(), FakeBff()
    seed_kept_print(alpaca, "AAA")
    seed_kept_print(alpaca, "BBB")
    alpaca.fail["AAA"] = [httpx.ConnectError("boom")]
    collector = make_collector(alpaca, bff, ["AAA", "BBB"])
    stats = await collector.cycle()
    assert stats.errors == 1
    assert [b["symbol"] for b in bff.bodies()[0]["bars"]] == ["BBB"]


async def test_request_cap_skips_the_rest_of_the_cycle_and_resumes_next_cycle():
    alpaca, bff = FakeAlpaca(), FakeBff()
    syms = ["A1", "A2", "A3", "A4", "A5"]
    for s in syms:
        alpaca.trades[s] = [raw("2026-10-29T13:38:00Z", 1)]
    data = alpaca.client(max_requests=3)
    collector = make_collector(alpaca, bff, syms, data=data)
    stats = await collector.cycle()
    assert stats.requests == 3
    assert stats.skipped == 2
    assert len(alpaca.requests) == 3
    assert [b["symbol"] for b in bff.bodies()[0]["bars"]] == ["A1", "A2", "A3"]
    assert collector.cursors["A4"] == Cursor.start(NOW - timedelta(minutes=10))
    # the budget is per cycle and the next cycle STARTS at the first skipped symbol
    # (round-robin), so a fixed universe order cannot starve its tail
    stats = await collector.cycle()
    assert stats.requests == 3 and stats.skipped == 2
    assert len(alpaca.requests) == 3 + 3  # A4, A5, then A1 (served, nothing new), then the cap
    assert [b["symbol"] for b in bff.bodies()[1]["bars"]] == ["A4", "A5"]
    # A cycle that fits the budget resets the rotation: raise the cap on the SAME collector, the
    # next cycle starts at A2 (where the last stop was) and completes, and the one after that
    # starts at the universe's head again.
    data.max_requests = 5
    assert (await collector.cycle()).skipped == 0
    assert [r.url.params["symbols"] for r in alpaca.requests[6:11]] == [
        "A2",
        "A3",
        "A4",
        "A5",
        "A1",
    ]
    assert (await collector.cycle()).skipped == 0
    assert [r.url.params["symbols"] for r in alpaca.requests[11:16]] == syms


async def test_budget_hit_mid_symbol_leaves_that_symbol_untouched():
    """Trades were fetched but the NBBO lookup was refused: nothing of that symbol is kept,
    aggregated or cursor-advanced, so the next cycle sees the same trades again."""
    alpaca, bff = FakeAlpaca(), FakeBff()
    seed_kept_print(alpaca)  # 1 trades request + 1 quote request
    collector = make_collector(alpaca, bff, ["QQQ"], max_requests=1)
    stats = await collector.cycle()
    assert (stats.skipped, stats.kept_prints, stats.bars) == (1, 0, 0)
    assert bff.requests == []
    assert collector.accumulators == {}
    assert collector.cursors["QQQ"] == Cursor.start(NOW - timedelta(minutes=10))


async def test_429_ends_the_cycle_and_the_next_cycle_resumes_at_that_symbol():
    alpaca, bff = FakeAlpaca(), FakeBff()
    seed_kept_print(alpaca, "AAA")
    seed_kept_print(alpaca, "BBB")
    seed_kept_print(alpaca, "CCC")
    alpaca.fail["BBB"] = [429]
    collector = make_collector(alpaca, bff, ["AAA", "BBB", "CCC"])
    stats = await collector.cycle()
    assert alpaca.sleeps == [1.0]  # no Retry-After -> 1 s, then the cycle ends
    assert (stats.errors, stats.skipped) == (0, 2)
    assert stats.requests == 3  # AAA trades + NBBO, then BBB's 429
    assert [p["symbol"] for p in bff.bodies()[0]["prints"]] == ["AAA"]
    assert collector.cursors["BBB"] == Cursor.start(bucket5m(NOW - timedelta(minutes=10)))
    # next cycle starts at BBB, serves it and CCC, then AAA (nothing new)
    stats = await collector.cycle()
    assert stats.skipped == 0
    assert [p["symbol"] for p in bff.bodies()[1]["prints"]] == ["BBB", "CCC"]
    assert [r.url.params["symbols"] for r in alpaca.requests[3:5]] == ["BBB", "BBB"]


async def test_low_ratelimit_remaining_ends_the_cycle():
    alpaca, bff = FakeAlpaca(), FakeBff()
    for sym in ("AAA", "BBB"):
        alpaca.trades[sym] = [raw("2026-10-29T13:38:00Z", 1)]

    def low(req: httpx.Request) -> httpx.Response:
        alpaca.requests.append(req)
        sym = req.url.params["symbols"]
        return httpx.Response(
            200,
            json={"trades": {sym: alpaca.trades[sym]}, "next_page_token": None},
            headers={"X-Ratelimit-Remaining": "1500"},
        )

    data = AlpacaDataClient(
        key_id="kid",
        secret="sec",
        log=LOG,
        client=httpx.AsyncClient(transport=httpx.MockTransport(low), base_url="https://data.test"),
        remaining_floor=2000,
    )
    collector = make_collector(alpaca, bff, ["AAA", "BBB"], data=data)
    stats = await collector.cycle()
    assert (stats.requests, stats.skipped) == (1, 1)  # AAA's response is kept, BBB waits
    assert [b["symbol"] for b in bff.bodies()[0]["bars"]] == ["AAA"]


async def test_size_kept_print_below_large_still_gets_an_nbbo_lookup():
    """10,000 shares at $1 = $10k: kept by SIZE, not large, and still NBBO-stamped."""
    alpaca, bff = FakeAlpaca(), FakeBff()
    alpaca.trades["QQQ"] = [raw("2026-10-29T13:38:10Z", 2, p=1.0, s=10_000, x="D")]
    alpaca.quotes["QQQ"] = [{"t": "2026-10-29T13:38:09Z", "bp": 1.0, "ap": 1.01}]
    collector = make_collector(alpaca, bff, ["QQQ"])
    stats = await collector.cycle()
    assert stats.requests == 2  # trades + the NBBO lookup
    assert alpaca.requests[1].url.path == "/v2/stocks/quotes"
    (body,) = bff.bodies()
    (p,) = body["prints"]
    assert (p["size"], p["notional"], p["side"]) == (10_000, 10_000.0, "sell")
    (b,) = body["bars"]
    assert (b["large_count"], b["large_volume"], b["sell_volume"]) == (0, 0, 10_000)


async def test_restart_cursor_is_bucket_aligned_and_never_posts_an_earlier_bucket():
    """A restart at 10:07 with a 10-minute lookback starts at bucket5m(09:57) = 09:55, so the
    09:55 bar is rebuilt from its first trade and the upsert cannot overwrite a complete row
    with a partial one. Nothing from 09:50 is aggregated even if the API hands it back."""
    alpaca, bff = FakeAlpaca(), FakeBff()
    alpaca.respect_start = False  # lax server: returns the 09:54:30 row regardless of start
    alpaca.trades["QQQ"] = [
        raw("2026-10-29T13:54:30Z", 1),  # 09:54:30 ET -> bucket 09:50: before the floor
        raw("2026-10-29T13:55:00Z", 2),  # 09:55:00 ET: the floor itself, inclusive
        raw("2026-10-29T14:06:00Z", 3),  # 10:06 ET
    ]
    collector = make_collector(alpaca, bff, ["QQQ"], clock=Clock(parse_ts("2026-10-29T14:07:00Z")))
    stats = await collector.cycle()
    assert alpaca.requests[0].url.params["start"] == "2026-10-29T13:55:00Z"
    assert collector.floors["QQQ"] == parse_ts("2026-10-29T13:55:00Z")
    (body,) = bff.bodies()
    buckets = [b["bucket"] for b in body["bars"]]
    assert buckets == ["2026-10-29T13:55:00Z", "2026-10-29T14:05:00Z"]
    assert buckets[0] == "2026-10-29T13:55:00Z"  # first posted bucket == the aligned start
    assert "2026-10-29T13:50:00Z" not in buckets
    assert stats.bars == 2
    assert body["bars"][0]["total_trades"] == 1  # only the 09:55:00 trade, not 09:54:30
    assert ("QQQ", parse_ts("2026-10-29T13:50:00Z")) not in collector.accumulators


async def test_trades_are_fetched_up_to_now_minus_the_settle_lag():
    alpaca, bff = FakeAlpaca(), FakeBff()
    alpaca.trades["QQQ"] = [raw("2026-10-29T13:38:00Z", 1)]
    collector = make_collector(alpaca, bff, ["QQQ"])
    await collector.cycle()
    params = alpaca.requests[0].url.params
    assert params["end"] == "2026-10-29T13:39:55Z"  # NOW (13:40:00Z) - 5 s
    assert params["start"] == "2026-10-29T13:30:00Z"


async def test_heartbeat_callback_fires_once_per_symbol():
    alpaca, bff = FakeAlpaca(), FakeBff()
    for sym in ("AAA", "BBB", "CCC"):
        alpaca.trades[sym] = [raw("2026-10-29T13:38:00Z", 1)]
    alpaca.fail["BBB"] = [500]
    beats: list[int] = []
    collector = make_collector(
        alpaca, bff, ["AAA", "BBB", "CCC"], dry_run=True, heartbeat=lambda: beats.append(1)
    )
    await collector.cycle()
    assert len(beats) == 3  # the failing symbol beats too


async def test_bar_is_resent_with_cumulative_totals_across_cycles():
    alpaca, bff = FakeAlpaca(), FakeBff()
    alpaca.trades["QQQ"] = [raw("2026-10-29T13:38:00Z", 1, s=100)]
    collector = make_collector(alpaca, bff, ["QQQ"])
    await collector.cycle()
    alpaca.trades["QQQ"].append(raw("2026-10-29T13:39:00Z", 2, s=250))
    await collector.cycle()
    b1, b2 = (body["bars"][0] for body in bff.bodies())
    assert b1["bucket"] == b2["bucket"] == "2026-10-29T13:35:00Z"
    assert (b1["total_volume"], b1["total_trades"]) == (100, 1)
    assert (b2["total_volume"], b2["total_trades"]) == (350, 2)
    # a cycle with nothing new sends nothing
    stats = await collector.cycle()
    assert stats.bars == 0 and len(bff.requests) == 2


async def test_old_buckets_are_pruned_after_the_cycle():
    alpaca, bff = FakeAlpaca(), FakeBff()
    alpaca.trades["QQQ"] = [
        raw("2026-10-29T13:26:00Z", 1),
        raw("2026-10-29T13:30:00Z", 2),
        raw("2026-10-29T13:35:00Z", 3),
    ]
    collector = make_collector(alpaca, bff, ["QQQ"], clock=Clock(parse_ts("2026-10-29T13:36:00Z")))
    stats = await collector.cycle()
    assert stats.bars == 3  # all three touched bars are sent...
    assert sorted(k[1].strftime("%H:%M") for k in collector.accumulators) == ["13:30", "13:35"]


# ---------------------------------------------------------------------------
# poll window + loop + heartbeat
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "utc,expected",
    [
        ("2026-10-29T07:59:00Z", False),  # 03:59 EDT
        ("2026-10-29T08:00:00Z", True),  # 04:00 EDT
        ("2026-10-29T23:59:00Z", True),  # 19:59 EDT
        ("2026-10-30T00:00:00Z", False),  # 20:00 EDT
        ("2026-12-01T09:00:00Z", True),  # 04:00 EST (DST ended)
        ("2026-12-01T08:59:00Z", False),  # 03:59 EST
        ("2026-10-31T14:00:00Z", False),  # Saturday 10:00 EDT
        ("2026-11-01T15:00:00Z", False),  # Sunday 10:00 EST
        ("2026-10-30T14:00:00Z", True),  # Friday 10:00 EDT
    ],
)
def test_in_poll_window_is_weekdays_04_to_20_et(utc, expected):
    assert in_poll_window(parse_ts(utc)) is expected


async def test_heartbeat_touched_every_cycle(tmp_path):
    alpaca, bff = FakeAlpaca(), FakeBff()
    seed_kept_print(alpaca)
    heartbeat = tmp_path / "heartbeat"
    collector = make_collector(alpaca, bff, ["QQQ"], dry_run=True)
    touches: list[int] = []

    class Stop(Exception):
        pass

    async def fake_sleep(secs: float) -> None:
        assert secs == 60.0
        touches.append(heartbeat.stat().st_mtime_ns)
        os.utime(heartbeat, (0, 0))
        if len(touches) == 3:
            raise Stop

    with pytest.raises(Stop):
        await darkpool_main.run_loop(
            collector, heartbeat, 60.0, sleep=fake_sleep, clock=Clock(NOW), log=LOG
        )
    assert len(touches) == 3 and all(t > 0 for t in touches)


async def test_loop_sleeps_outside_the_window_but_keeps_the_heartbeat(tmp_path):
    alpaca, bff = FakeAlpaca(), FakeBff()
    seed_kept_print(alpaca)
    heartbeat = tmp_path / "heartbeat"
    night = Clock(parse_ts("2026-10-29T02:00:00Z"))  # 22:00 ET
    collector = make_collector(alpaca, bff, ["QQQ"], clock=night)

    class Stop(Exception):
        pass

    async def stop_sleep(_secs: float) -> None:
        assert heartbeat.exists()
        raise Stop

    with pytest.raises(Stop):
        await darkpool_main.run_loop(
            collector, heartbeat, 60.0, sleep=stop_sleep, clock=night, log=LOG
        )
    assert alpaca.requests == [] and bff.requests == []


async def test_loop_survives_a_cycle_exception(tmp_path):
    heartbeat = tmp_path / "heartbeat"

    class Boom(DarkpoolCollector):
        def __init__(self) -> None:  # noqa: D401 - minimal stub
            pass

        async def cycle(self):
            raise RuntimeError("boom")

    class Stop(Exception):
        pass

    calls: list[float] = []

    async def fake_sleep(secs: float) -> None:
        calls.append(heartbeat.stat().st_mtime_ns)
        if len(calls) == 2:
            raise Stop

    with pytest.raises(Stop):
        await darkpool_main.run_loop(
            Boom(), heartbeat, 60.0, sleep=fake_sleep, clock=Clock(NOW), log=LOG
        )
    assert len(calls) == 2


def test_universe_file_is_required(monkeypatch):
    monkeypatch.delenv("DARKPOOL_UNIVERSE_FILE", raising=False)
    with pytest.raises(SystemExit):
        darkpool_main.universe_from_env()


def test_universe_file_is_read(monkeypatch, tmp_path: pathlib.Path):
    f = tmp_path / "u.txt"
    f.write_text("SPY\nQQQ\n")
    monkeypatch.setenv("DARKPOOL_UNIVERSE_FILE", str(f))
    assert darkpool_main.universe_from_env() == ["SPY", "QQQ"]
