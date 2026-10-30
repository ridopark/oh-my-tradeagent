"""Pure-function tests for the dark-pool collector (PLAN-2026-10-29-darkpool-prints, phase 2).

Classification, side inference, ET bucketing (incl. a DST day), the hand-computed aggregate over a
40-trade fixture with 6 off-exchange prints, cursor resume, page joining and the cross-cycle
accumulator resend. Alpaca is mocked with ``httpx.MockTransport``; nothing here touches the
network or a WebSocket.
"""

from __future__ import annotations

import logging
from datetime import datetime, time, timedelta, timezone

import httpx
import pytest

from ohmytradeagent_sidecar.darkpool_collector import (
    ET,
    LARGE_NOTIONAL,
    AlpacaDataClient,
    BarAccumulator,
    BudgetExhausted,
    Cursor,
    Quote,
    RateLimited,
    Trade,
    aggregate,
    bucket5m,
    classify,
    format_ts,
    is_large,
    keep,
    parse_ts,
    prune_accumulators,
    read_universe,
    side,
)

LOG = logging.getLogger("test.darkpool")
UTC = timezone.utc


def trade(
    ts: str,
    price: float,
    size: int,
    *,
    x: str = "V",
    i: int = 1,
    symbol: str = "QQQ",
    c: tuple[str, ...] = ("@",),
    z: str = "C",
) -> Trade:
    return Trade.from_alpaca(
        symbol, {"t": ts, "p": price, "s": size, "x": x, "i": i, "c": list(c), "z": z}
    )


# ---------------------------------------------------------------------------
# classify / is_large / keep
# ---------------------------------------------------------------------------


def test_classify_off_exchange_iff_x_is_D():
    assert classify(trade("2026-10-29T13:30:10Z", 500.0, 100, x="D")) is True
    for x in ("V", "P", "Q", "N", "Z", "d", "DD", ""):
        assert classify(trade("2026-10-29T13:30:10Z", 500.0, 100, x=x)) is False, x


def test_is_large_uses_the_bff_threshold_inclusive():
    assert LARGE_NOTIONAL == 200_000
    # 500 * 400 = 200,000 exactly -> large (>=)
    assert is_large(trade("2026-10-29T13:30:10Z", 500.0, 400, x="D")) is True
    # 500 * 399 = 199,500 -> not large
    assert is_large(trade("2026-10-29T13:30:10Z", 500.0, 399, x="D")) is False
    # the threshold is a parameter so a config change cannot silently diverge from the BFF
    assert is_large(trade("2026-10-29T13:30:10Z", 500.0, 399, x="D"), min_notional=100_000)


def test_keep_requires_D_and_either_notional_or_size():
    kw = dict(min_notional=200_000, min_size=10_000)
    # notional branch: 500 * 400 = 200,000
    assert keep(trade("2026-10-29T13:30:10Z", 500.0, 400, x="D"), **kw) is True
    # size branch: 1 * 10,000 = 10,000 notional, size 10,000
    assert keep(trade("2026-10-29T13:30:10Z", 1.0, 10_000, x="D"), **kw) is True
    # neither
    assert keep(trade("2026-10-29T13:30:10Z", 500.0, 100, x="D"), **kw) is False
    # lit exchange, however large, is never a kept print
    assert keep(trade("2026-10-29T13:30:10Z", 500.0, 100_000, x="V"), **kw) is False


def test_trade_fields_from_alpaca_row():
    t = trade("2026-10-29T13:30:10.123456789Z", 500.25, 400, x="D", i=987654321, c=("@", "T"))
    assert t.trade_id == 987654321
    assert t.ts == datetime(2026, 10, 29, 13, 30, 10, 123456, tzinfo=UTC)
    assert t.ts_raw == "2026-10-29T13:30:10.123456789Z"  # sent verbatim: the unique key
    assert t.notional == pytest.approx(200_100.0)
    assert t.conditions == ("@", "T")
    assert t.tape == "C"
    assert t.exchange == "D"


def test_parse_ts_accepts_any_fractional_precision():
    assert parse_ts("2026-10-29T13:30:10Z") == datetime(2026, 10, 29, 13, 30, 10, tzinfo=UTC)
    assert parse_ts("2026-10-29T13:30:10.5Z") == datetime(
        2026, 10, 29, 13, 30, 10, 500000, tzinfo=UTC
    )
    assert parse_ts("2026-10-29T13:30:10.123456789Z").microsecond == 123456
    assert parse_ts("2026-10-29T09:30:10-04:00") == datetime(2026, 10, 29, 13, 30, 10, tzinfo=UTC)


# ---------------------------------------------------------------------------
# side
# ---------------------------------------------------------------------------

P = datetime(2026, 10, 29, 13, 30, 10, tzinfo=UTC)


def q(bid: float, ask: float, age_s: float = 1.0) -> Quote:
    return Quote(bid=bid, ask=ask, ts=P - timedelta(seconds=age_s))


def test_side_buy_at_or_above_ask():
    assert side(500.00, q(499.98, 500.00), P) == "buy"
    assert side(500.01, q(499.98, 500.00), P) == "buy"


def test_side_sell_at_or_below_bid():
    assert side(499.98, q(499.98, 500.00), P) == "sell"
    assert side(499.97, q(499.98, 500.00), P) == "sell"


def test_side_mid_strictly_between():
    assert side(499.99, q(499.98, 500.00), P) == "mid"


def test_side_unknown_without_a_quote():
    assert side(500.00, None, P) == "unknown"


def test_side_unknown_on_a_one_sided_or_crossed_nbbo():
    # no bid: a print at the ask would otherwise read as a buy
    assert side(500.00, q(0.0, 500.00), P) == "unknown"
    # no ask: a print above the bid would otherwise read as a mid
    assert side(500.00, q(499.98, 0.0), P) == "unknown"
    # crossed (ask < bid): a print between them satisfies BOTH comparisons
    assert side(499.99, q(500.00, 499.98), P) == "unknown"
    # locked (ask == bid) is a real book: at the price it is a buy (>= ask wins)
    assert side(500.00, q(500.00, 500.00), P) == "buy"


def test_side_unknown_when_the_quote_is_stale_or_after_the_print():
    # exactly 5 s before is still usable; 5.001 s is not
    assert side(500.00, q(499.98, 500.00, age_s=5.0), P) == "buy"
    assert side(500.00, q(499.98, 500.00, age_s=5.001), P) == "unknown"
    # a quote AFTER the print never classifies it
    assert side(500.00, q(499.98, 500.00, age_s=-0.5), P) == "unknown"


# ---------------------------------------------------------------------------
# side — off-NBBO band guard (issue #1013)
# ---------------------------------------------------------------------------


def test_side_issue_1013_three_spy_prints_far_above_ask_are_unknown():
    """SPY 126,311 sh @ 771.8561 while NBBO 768.19/768.20 must be unknown.
    Band: spread=0.01, mid=768.195, 10 bp of mid=0.768195, D=0.768195,
    upper bound=768.968195; 771.8561 >> 768.968195 -> unknown (issue #1013)."""
    nbbo = Quote(bid=768.19, ask=768.20, ts=P - timedelta(seconds=1))
    # three prints at the same off-NBBO price (different instants / IDs)
    assert side(771.8561, nbbo, P) == "unknown"
    assert side(771.8561, nbbo, P + timedelta(seconds=1)) == "unknown"
    assert side(771.8561, nbbo, P + timedelta(seconds=2)) == "unknown"


def test_side_within_nbbo_band_still_classifies_buy_and_sell():
    """A print at ask + half a spread is within D (D ≈ 10 bp >> half-spread) -> buy.
    A print at bid - half a spread -> sell. Neither must become unknown."""
    nbbo = Quote(bid=499.98, ask=500.00, ts=P - timedelta(seconds=1))
    # spread=0.02, half-spread=0.01; D=max(0.02, 0.49999)=0.49999
    assert side(500.01, nbbo, P) == "buy"   # ask + half-spread, well within D
    assert side(499.97, nbbo, P) == "sell"  # bid - half-spread, well within D


def test_side_at_nbbo_band_boundary():
    """Price exactly at the upper/lower edge of D stays classified;
    one float-epsilon outside -> unknown."""
    nbbo = Quote(bid=499.98, ask=500.00, ts=P - timedelta(seconds=1))
    # spread=0.02, mid=499.99, 10bp=0.49999; D=0.49999
    # upper = 500.00 + 0.49999 = 500.49999; lower = 499.98 - 0.49999 = 499.48001
    assert side(500.49999, nbbo, P) == "buy"      # at upper bound (inclusive)
    assert side(500.50001, nbbo, P) == "unknown"  # just outside upper bound
    assert side(499.48001, nbbo, P) == "sell"     # at lower bound (inclusive)
    assert side(499.47999, nbbo, P) == "unknown"  # just outside lower bound


def test_aggregate_off_nbbo_print_counts_in_dp_and_large_but_not_buy_sell():
    """An off-band KEPT print (side='unknown') must appear in dp_volume / large_count /
    large_volume but NOT in buy_volume / sell_volume (issue #1013: only the buy/sell
    split changes, totals are unchanged)."""
    # 126311 @ 771.8561 notional ≈ 97.6 M >> 200 k -> KEPT and large
    t = trade("2026-10-29T13:30:10Z", 771.8561, 126311, x="D", i=1013, symbol="SPY")
    nbbo = Quote(bid=768.19, ask=768.20, ts=parse_ts("2026-10-29T13:30:09Z"))
    nbbo_side = side(771.8561, nbbo, parse_ts("2026-10-29T13:30:10Z"))
    assert nbbo_side == "unknown"
    accs: dict = {}
    aggregate([t], {1013: nbbo_side}, accs, min_notional=200_000, min_size=10_000)
    (bar,) = [acc.to_wire() for acc in accs.values()]
    assert bar["dp_volume"] == 126311
    assert bar["large_count"] == 1
    assert bar["large_volume"] == 126311
    assert bar["buy_volume"] == 0
    assert bar["sell_volume"] == 0


# ---------------------------------------------------------------------------
# bucket5m / format_ts
# ---------------------------------------------------------------------------


def test_bucket5m_floors_to_five_minutes_and_returns_an_aware_utc_instant():
    # 09:34:59.999 ET on 2026-10-29 (EDT, UTC-4) -> 09:30 ET = 13:30Z
    b = bucket5m(datetime(2026, 10, 29, 13, 34, 59, 999000, tzinfo=UTC))
    assert b == datetime(2026, 10, 29, 13, 30, tzinfo=UTC)
    assert b.tzinfo is not None and b.utcoffset() == timedelta(0)
    assert format_ts(b) == "2026-10-29T13:30:00Z"
    # the next microsecond opens the next bucket
    assert bucket5m(datetime(2026, 10, 29, 13, 35, tzinfo=UTC)) == datetime(
        2026, 10, 29, 13, 35, tzinfo=UTC
    )
    # an input in another zone is the same instant, so the same bucket
    assert bucket5m(datetime(2026, 10, 29, 9, 34, tzinfo=timezone(timedelta(hours=-4)))) == b


def test_bucket5m_session_start_is_the_0930_bucket_on_a_dst_and_a_standard_day():
    """The 09:30 ET session start is 14:30Z on 2026-03-06 (EST, UTC-5) and 13:30Z on
    2026-03-09 (EDT, UTC-4; DST began 03-08). Both must land in a bucket whose ET clock reads
    09:30 — i.e. the bucket is computed on the ET clock through the offset in force that day,
    not through a fixed offset or a naive timestamp."""
    est = bucket5m(datetime(2026, 3, 6, 14, 31, 12, tzinfo=UTC))
    edt = bucket5m(datetime(2026, 3, 9, 13, 31, 12, tzinfo=UTC))
    assert est.astimezone(ET).time() == time(9, 30)
    assert edt.astimezone(ET).time() == time(9, 30)
    assert (format_ts(est), format_ts(edt)) == ("2026-03-06T14:30:00Z", "2026-03-09T13:30:00Z")
    # the two session starts are an hour apart in UTC and the same on the ET clock
    assert edt - est == timedelta(days=3, hours=-1)
    # 2026-11-01: DST ends; 01:30 ET occurs twice. Both instants are distinct buckets.
    first = bucket5m(datetime(2026, 11, 1, 5, 32, tzinfo=UTC))  # 01:32 EDT
    second = bucket5m(datetime(2026, 11, 1, 6, 32, tzinfo=UTC))  # 01:32 EST
    assert first != second
    assert first.astimezone(ET).time() == second.astimezone(ET).time() == time(1, 30)


def test_format_ts_is_rfc3339_utc_z():
    assert format_ts(datetime(2026, 10, 29, 9, 30, tzinfo=timezone(timedelta(hours=-4)))) == (
        "2026-10-29T13:30:00Z"
    )
    assert format_ts(datetime(2026, 10, 29, 13, 30, 0, 250000, tzinfo=UTC)) == (
        "2026-10-29T13:30:00.250000Z"
    )


# ---------------------------------------------------------------------------
# aggregate — 40-trade fixture, 6 off-exchange prints, hand-computed bars
# ---------------------------------------------------------------------------

MIN = dict(min_notional=200_000, min_size=10_000)


def fixture_trades() -> list[Trade]:
    """QQQ, two buckets (09:30 and 09:35 ET on 2026-10-29 = 13:30Z / 13:35Z), 40 trades.

    Bucket A (13:30-13:35Z): 16 lit trades of 100 + 4 D prints
      D1 13:30:10 500.00 x 400   notional 200,000 -> KEPT (notional), NBBO -> buy, large
      D2 13:31:00 500.10 x 100   notional  50,010 -> unkept; prior dp VWAP 500.00 -> buy
      D3 13:32:00 499.90 x 200   notional  99,980 -> unkept; prior dp VWAP
                                 (200000+50010)/500 = 500.02 -> sell
      D4 13:33:00 499.50 x 12000 notional 5,994,000 -> KEPT (size+notional), NBBO -> mid, large
    Bucket B (13:35-13:40Z): 18 lit trades of 100 + 2 D prints
      D5 13:36:00 499.00 x 300   notional 149,700 -> unkept; FIRST D print of the bar: no
                                 prior VWAP -> neither buy nor sell
      D6 13:37:00 498.00 x 10000 notional 4,980,000 -> KEPT, no quote -> unknown, large
    """
    out: list[Trade] = []
    tid = 1000
    # Bucket A lit trades: 16 x 100 at 13:30:00 + k*15s (k = 0..15 -> 13:30:00..13:33:45)
    for k in range(16):
        tid += 1
        out.append(
            trade(
                f"2026-10-29T13:{30 + (k * 15) // 60:02d}:{(k * 15) % 60:02d}Z",
                500.0 + 0.01 * k,
                100,
                x="V",
                i=tid,
            )
        )
    out.append(trade("2026-10-29T13:30:10Z", 500.00, 400, x="D", i=1))  # D1
    out.append(trade("2026-10-29T13:31:00Z", 500.10, 100, x="D", i=2))  # D2
    out.append(trade("2026-10-29T13:32:00Z", 499.90, 200, x="D", i=3))  # D3
    out.append(trade("2026-10-29T13:33:00Z", 499.50, 12000, x="D", i=4))  # D4
    # Bucket B lit trades: 18 x 100 at 13:35:00 + k*15s
    for k in range(18):
        tid += 1
        out.append(
            trade(
                f"2026-10-29T13:{35 + (k * 15) // 60:02d}:{(k * 15) % 60:02d}Z",
                499.0 + 0.01 * k,
                100,
                x="P",
                i=tid,
            )
        )
    out.append(trade("2026-10-29T13:36:00Z", 499.00, 300, x="D", i=5))  # D5
    out.append(trade("2026-10-29T13:37:00Z", 498.00, 10000, x="D", i=6))  # D6
    # Alpaca returns trades sorted by t; the collector relies on that for the running VWAP.
    out.sort(key=lambda t: (t.ts, t.trade_id))
    assert len(out) == 40
    return out


FIXTURE_SIDES = {1: "buy", 4: "mid", 6: "unknown"}  # NBBO side per KEPT print (trade_id)


def test_fixture_keeps_exactly_the_three_expected_prints():
    kept = [t.trade_id for t in fixture_trades() if keep(t, **MIN)]
    assert kept == [1, 4, 6]
    assert sum(classify(t) for t in fixture_trades()) == 6


def test_aggregate_hand_computed_bars():
    accs: dict = {}
    touched = aggregate(fixture_trades(), FIXTURE_SIDES, accs, **MIN)
    a_key = ("QQQ", datetime(2026, 10, 29, 13, 30, tzinfo=UTC))
    b_key = ("QQQ", datetime(2026, 10, 29, 13, 35, tzinfo=UTC))
    assert touched == {a_key, b_key}
    assert set(accs) == touched

    a = accs[a_key].to_wire()
    # dp_volume = 400+100+200+12000 = 12700; total = 16*100 + 12700 = 14300
    # buy = D1 400 + D2 100 = 500; sell = D3 200; D4 is 'mid' -> neither
    # large = D1 (200,000) + D4 (5,994,000) -> count 2, volume 12400
    # dp_vwap = (200000 + 50010 + 99980 + 5994000) / 12700 = 6343990/12700 = 499.52677... -> 499.5268
    assert a == {
        "symbol": "QQQ",
        "bucket": "2026-10-29T13:30:00Z",
        "dp_volume": 12700,
        "dp_trades": 4,
        "total_volume": 14300,
        "total_trades": 20,
        "buy_volume": 500,
        "sell_volume": 200,
        "large_count": 2,
        "large_volume": 12400,
        "dp_vwap": 499.5268,
    }

    b = accs[b_key].to_wire()
    # dp_volume = 300 + 10000 = 10300; total = 18*100 + 10300 = 12100
    # D5 first-in-bar (no prior VWAP) -> neither; D6 unknown -> neither => buy 0, sell 0
    # large = D6 only -> count 1, volume 10000
    # dp_vwap = (149700 + 4980000) / 10300 = 5129700/10300 = 498.02912... -> 498.0291
    assert b == {
        "symbol": "QQQ",
        "bucket": "2026-10-29T13:35:00Z",
        "dp_volume": 10300,
        "dp_trades": 2,
        "total_volume": 12100,
        "total_trades": 20,
        "buy_volume": 0,
        "sell_volume": 0,
        "large_count": 1,
        "large_volume": 10000,
        "dp_vwap": 498.0291,
    }


def test_aggregate_bar_without_off_exchange_prints_has_null_vwap():
    accs: dict = {}
    aggregate([trade("2026-10-29T13:30:10Z", 500.0, 100, x="V", i=7)], {}, accs, **MIN)
    (bar,) = [acc.to_wire() for acc in accs.values()]
    assert bar["dp_volume"] == 0 and bar["dp_trades"] == 0
    assert bar["total_volume"] == 100 and bar["total_trades"] == 1
    assert bar["dp_vwap"] is None


def test_aggregate_large_uses_notional_not_size():
    # 10,000 shares at $1 = $10,000 notional: KEPT (size) but NOT large (< $200k)
    accs: dict = {}
    aggregate([trade("2026-10-29T13:30:10Z", 1.0, 10_000, x="D", i=8)], {8: "buy"}, accs, **MIN)
    (bar,) = [acc.to_wire() for acc in accs.values()]
    assert (bar["large_count"], bar["large_volume"]) == (0, 0)
    assert bar["buy_volume"] == 10_000


def test_aggregate_vwap_tie_counts_toward_neither_side():
    """Three un-quoted small D prints at ONE price: the first has no reference VWAP, the next
    two tie it exactly -> buy 0, sell 0 (a '>=' would call the ties buys)."""
    accs: dict = {}
    aggregate(
        [
            trade("2026-10-29T13:30:10Z", 500.0, 100, x="D", i=21),
            trade("2026-10-29T13:30:20Z", 500.0, 200, x="D", i=22),
            trade("2026-10-29T13:30:30Z", 500.0, 300, x="D", i=23),
        ],
        {},
        accs,
        **MIN,
    )
    (bar,) = [acc.to_wire() for acc in accs.values()]
    assert (bar["dp_volume"], bar["dp_trades"]) == (600, 3)
    assert (bar["buy_volume"], bar["sell_volume"]) == (0, 0)
    assert bar["dp_vwap"] == 500.0
    # and a print strictly above / below the running VWAP still classifies
    aggregate(
        [
            trade("2026-10-29T13:30:40Z", 500.01, 10, x="D", i=24),
            trade("2026-10-29T13:30:50Z", 499.0, 20, x="D", i=25),
        ],
        {},
        accs,
        **MIN,
    )
    (bar,) = [acc.to_wire() for acc in accs.values()]
    assert (bar["buy_volume"], bar["sell_volume"]) == (10, 20)


def test_aggregate_resends_the_full_bar_across_cycles():
    """Later trades in the same 5-minute bucket land on the SAME accumulator, so the re-sent bar
    carries the cumulative totals (the BFF upserts on (symbol, bucket))."""
    accs: dict = {}
    key = ("QQQ", datetime(2026, 10, 29, 13, 30, tzinfo=UTC))
    # cycle 1: one lit trade + one unkept D print
    aggregate(
        [
            trade("2026-10-29T13:30:05Z", 500.0, 100, x="V", i=11),
            trade("2026-10-29T13:30:10Z", 500.0, 200, x="D", i=12),
        ],
        {},
        accs,
        **MIN,
    )
    assert accs[key].to_wire()["total_volume"] == 300
    assert accs[key].to_wire()["dp_volume"] == 200
    # cycle 2: another D print in the same bucket; prior VWAP 500.00 -> 500.10 is a buy
    touched = aggregate([trade("2026-10-29T13:31:00Z", 500.10, 300, x="D", i=13)], {}, accs, **MIN)
    assert touched == {key}
    bar = accs[key].to_wire()
    assert (bar["total_volume"], bar["total_trades"]) == (600, 3)
    assert (bar["dp_volume"], bar["dp_trades"]) == (500, 2)
    assert bar["buy_volume"] == 300
    # (500*200 + 500.10*300) / 500 = (100000 + 150030) / 500 = 500.06
    assert bar["dp_vwap"] == 500.06


def test_prune_keeps_only_the_two_newest_buckets_per_symbol():
    b = lambda m: datetime(2026, 10, 29, 13, m, tzinfo=UTC)  # noqa: E731
    accs = {
        ("QQQ", b(25)): BarAccumulator("QQQ", b(25)),
        ("QQQ", b(30)): BarAccumulator("QQQ", b(30)),
        ("QQQ", b(35)): BarAccumulator("QQQ", b(35)),
        ("SPY", b(35)): BarAccumulator("SPY", b(35)),
    }
    prune_accumulators(accs)
    assert set(accs) == {("QQQ", b(30)), ("QQQ", b(35)), ("SPY", b(35))}


# ---------------------------------------------------------------------------
# Cursor
# ---------------------------------------------------------------------------


def test_cursor_resume_excludes_already_seen_t_and_i():
    t0 = "2026-10-29T13:30:10Z"
    first = [
        trade("2026-10-29T13:30:09Z", 500.0, 100, i=1),
        trade(t0, 500.0, 100, i=2),
        trade(t0, 500.0, 100, i=3),
    ]
    cur = Cursor.start(datetime(2026, 10, 29, 13, 0, tzinfo=UTC)).advance(first)
    assert cur.last_t == parse_ts(t0)
    assert cur.last_ids == frozenset({2, 3})
    # The next poll starts AT last_t (Alpaca's start is inclusive), so the same-timestamp
    # trades come back: they must be dropped, a new id at the same t and later ones kept.
    page = [
        trade(t0, 500.0, 100, i=2),
        trade(t0, 500.0, 100, i=3),
        trade(t0, 500.0, 100, i=4),
        trade("2026-10-29T13:30:11Z", 500.0, 100, i=5),
        trade("2026-10-29T13:30:08Z", 500.0, 100, i=0),  # earlier than the cursor: seen
    ]
    fresh = cur.filter_new(page)
    assert [t.trade_id for t in fresh] == [4, 5]
    cur2 = cur.advance(fresh)
    assert cur2.last_t == parse_ts("2026-10-29T13:30:11Z")
    assert cur2.last_ids == frozenset({5})
    # advancing on an empty page is a no-op
    assert cur2.advance([]) == cur2


def test_cursor_advance_unions_ids_at_the_same_instant():
    """Two cycles can each end on trades at the SAME timestamp (a burst straddling the fetch
    end). The second advance must keep the first cycle's ids, or those trades come back as new
    on the third fetch and are double-counted."""
    t0 = "2026-10-29T13:30:10Z"
    cur = Cursor.start(datetime(2026, 10, 29, 13, 0, tzinfo=UTC)).advance(
        [trade(t0, 500.0, 100, i=2), trade(t0, 500.0, 100, i=3)]
    )
    cur2 = cur.advance([trade(t0, 500.0, 100, i=4)])
    assert cur2.last_t == parse_ts(t0)
    assert cur2.last_ids == frozenset({2, 3, 4})
    assert cur2.filter_new([trade(t0, 500.0, 100, i=2), trade(t0, 500.0, 100, i=3)]) == []


def test_cursor_start_is_the_lookback_instant_with_nothing_seen():
    cur = Cursor.start(datetime(2026, 10, 29, 13, 0, tzinfo=UTC))
    assert cur.last_ids == frozenset()
    assert [t.trade_id for t in cur.filter_new([trade("2026-10-29T13:00:00Z", 1.0, 1, i=9)])] == [9]


# ---------------------------------------------------------------------------
# AlpacaDataClient — pagination, budget, 429 backoff, NBBO query
# ---------------------------------------------------------------------------


class FakeAlpaca:
    """``/v2/stocks/trades`` with page tokens and ``/v2/stocks/quotes`` with sort=desc."""

    def __init__(self) -> None:
        self.pages: list[dict] = []  # trades pages in order
        self.quotes: list[dict] = []  # quote rows, any order
        self.requests: list[httpx.Request] = []
        self.fail_next: list = []
        self.headers: dict[str, str] = {}  # added to every 200

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        assert request.headers["APCA-API-KEY-ID"] == "kid"
        assert request.headers["APCA-API-SECRET-KEY"] == "sec"
        if self.fail_next:
            nxt = self.fail_next.pop(0)
            if isinstance(nxt, Exception):
                raise nxt
            status, headers = (nxt, {}) if isinstance(nxt, int) else nxt
            return httpx.Response(status, json={"message": "injected"}, headers=headers)
        params = request.url.params
        assert params["feed"] == "sip"
        if request.url.path == "/v2/stocks/trades":
            assert params["limit"] == "10000"
            idx = int(params.get("page_token", "p0")[1:])
            page = self.pages[idx]
            nxt = f"p{idx + 1}" if idx + 1 < len(self.pages) else None
            return httpx.Response(
                200,
                json={"trades": {params["symbols"]: page}, "next_page_token": nxt},
                headers=self.headers,
            )
        if request.url.path == "/v2/stocks/quotes":
            assert params["limit"] == "1" and params["sort"] == "desc"
            end = parse_ts(params["end"])
            start = parse_ts(params["start"])
            rows = sorted(
                (r for r in self.quotes if start <= parse_ts(r["t"]) <= end),
                key=lambda r: r["t"],
                reverse=True,
            )[:1]
            return httpx.Response(
                200, json={"quotes": {params["symbols"]: rows}, "next_page_token": None}
            )
        return httpx.Response(404, json={"message": "not found"})

    def client(self, **kw) -> AlpacaDataClient:
        sleeps: list[float] = []

        async def fake_sleep(secs: float) -> None:
            sleeps.append(secs)

        c = AlpacaDataClient(
            key_id="kid",
            secret="sec",
            log=LOG,
            client=httpx.AsyncClient(
                transport=httpx.MockTransport(self.handler), base_url="https://data.test"
            ),
            sleep=fake_sleep,
            **kw,
        )
        c.test_sleeps = sleeps  # type: ignore[attr-defined]
        return c


def raw(ts: str, i: int, x: str = "V") -> dict:
    return {"t": ts, "p": 500.0, "s": 100, "x": x, "i": i, "c": ["@"], "z": "C"}


async def test_trades_pagination_joins_pages_in_order():
    fake = FakeAlpaca()
    fake.pages = [
        [raw("2026-10-29T13:30:00Z", 1), raw("2026-10-29T13:30:01Z", 2)],
        [raw("2026-10-29T13:30:02Z", 3)],
        [raw("2026-10-29T13:30:03Z", 4)],
    ]
    client = fake.client()
    start = datetime(2026, 10, 29, 13, 30, tzinfo=UTC)
    end = datetime(2026, 10, 29, 13, 31, tzinfo=UTC)
    trades = await client.trades("QQQ", start, end)
    assert [t.trade_id for t in trades] == [1, 2, 3, 4]
    assert all(t.symbol == "QQQ" for t in trades)
    assert client.requests_this_cycle == 3
    tokens = [r.url.params.get("page_token") for r in fake.requests]
    assert tokens == [None, "p1", "p2"]
    first = fake.requests[0].url.params
    assert (first["symbols"], first["start"], first["end"]) == (
        "QQQ",
        "2026-10-29T13:30:00Z",
        "2026-10-29T13:31:00Z",
    )


async def test_quote_before_returns_the_latest_nbbo_within_5s():
    fake = FakeAlpaca()
    fake.quotes = [
        {"t": "2026-10-29T13:30:03Z", "bp": 499.90, "ap": 499.92},  # 7 s before: outside
        {"t": "2026-10-29T13:30:08Z", "bp": 499.95, "ap": 499.97},
        {"t": "2026-10-29T13:30:09.5Z", "bp": 499.98, "ap": 500.00},  # the latest <= print
        {"t": "2026-10-29T13:30:10.5Z", "bp": 500.10, "ap": 500.12},  # after: excluded
    ]
    client = fake.client()
    quote = await client.quote_before("QQQ", parse_ts("2026-10-29T13:30:10Z"))
    assert quote == Quote(bid=499.98, ask=500.00, ts=parse_ts("2026-10-29T13:30:09.5Z"))
    params = fake.requests[0].url.params
    assert (params["start"], params["end"]) == ("2026-10-29T13:30:05Z", "2026-10-29T13:30:10Z")
    # no quote in the window -> None (the caller stamps the print 'unknown')
    fake.quotes = []
    assert await client.quote_before("QQQ", parse_ts("2026-10-29T13:30:10Z")) is None


async def test_request_budget_is_a_hard_cap_per_cycle():
    fake = FakeAlpaca()
    fake.pages = [[raw("2026-10-29T13:30:00Z", 1)]]
    client = fake.client(max_requests=2)
    start = datetime(2026, 10, 29, 13, 30, tzinfo=UTC)
    end = datetime(2026, 10, 29, 13, 31, tzinfo=UTC)
    await client.trades("QQQ", start, end)
    await client.trades("QQQ", start, end)
    with pytest.raises(BudgetExhausted):
        await client.trades("QQQ", start, end)
    assert len(fake.requests) == 2  # the third never left the process
    client.reset_budget()
    await client.trades("QQQ", start, end)
    assert len(fake.requests) == 3


async def test_429_sleeps_at_most_5s_and_ends_the_cycle():
    """No retry inside the cycle: a 429 means the shared 10k/min account budget is gone, so the
    cycle stops (BudgetExhausted semantics) and the symbol resumes next cycle. Retry-After is
    honoured but capped at 5 s so a hostile header cannot stall the heartbeat."""
    fake = FakeAlpaca()
    fake.pages = [[raw("2026-10-29T13:30:00Z", 1)]]
    fake.fail_next = [(429, {"Retry-After": "30"})]
    client = fake.client()
    start = datetime(2026, 10, 29, 13, 30, tzinfo=UTC)
    end = datetime(2026, 10, 29, 13, 31, tzinfo=UTC)
    with pytest.raises(RateLimited) as exc:
        await client.trades("QQQ", start, end)
    assert isinstance(exc.value, BudgetExhausted)
    assert client.test_sleeps == [5.0]
    assert len(fake.requests) == 1  # no retry
    assert client.requests_this_cycle == 1
    # Retry-After within the cap is used as-is; absent -> 1 s
    fake.fail_next = [(429, {"Retry-After": "2"}), 429]
    with pytest.raises(RateLimited):
        await client.trades("QQQ", start, end)
    with pytest.raises(RateLimited):
        await client.trades("QQQ", start, end)
    assert client.test_sleeps == [5.0, 2.0, 1.0]


async def test_low_ratelimit_remaining_stops_the_cycle_after_the_current_response():
    fake = FakeAlpaca()
    fake.pages = [[raw("2026-10-29T13:30:00Z", 1)]]
    client = fake.client()
    start = datetime(2026, 10, 29, 13, 30, tzinfo=UTC)
    end = datetime(2026, 10, 29, 13, 31, tzinfo=UTC)
    fake.headers = {"X-Ratelimit-Remaining": "2000"}
    await client.trades("QQQ", start, end)  # at the floor: fine
    fake.headers = {"X-Ratelimit-Remaining": "1999"}
    trades = await client.trades("QQQ", start, end)  # the response that crossed it is kept...
    assert [t.trade_id for t in trades] == [1]
    with pytest.raises(BudgetExhausted) as exc:  # ...and the NEXT request is refused
        await client.trades("QQQ", start, end)
    assert "X-Ratelimit-Remaining=1999" in str(exc.value)
    assert len(fake.requests) == 2
    # a new cycle starts clean (the minute window has rolled)
    client.reset_budget()
    fake.headers = {}
    await client.trades("QQQ", start, end)
    assert len(fake.requests) == 3


# ---------------------------------------------------------------------------
# read_universe
# ---------------------------------------------------------------------------


def test_read_universe_one_per_line_comments_dedup_upper(tmp_path):
    f = tmp_path / "universe.txt"
    f.write_text("# header\nspy\nQQQ\n\n  aapl  \nSPY\n# trailing\n")
    assert read_universe(f) == ["SPY", "QQQ", "AAPL"]


def test_read_universe_empty_file_fails_fast(tmp_path):
    f = tmp_path / "universe.txt"
    f.write_text("# nothing\n")
    with pytest.raises(SystemExit):
        read_universe(f)
