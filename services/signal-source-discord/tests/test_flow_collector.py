"""Classifier, aggregator, QuoteSet and TapeArchiver tests for the flow collector
(PLAN-2026-10-29-flow-page, Step 1).

All tests are pure: no network, no filesystem side-effects beyond a tmp_path.
Msgpack frames used in ``parse_frame`` tests are assembled inline; a small fixture
file ``tests/fixtures/flow_sample.msgpack`` is also used for end-to-end frame decoding.
"""

from __future__ import annotations

import gzip
import pathlib
import subprocess
from datetime import datetime, timedelta, timezone

import msgpack
import pytest

from ohmytradeagent_sidecar.flow_collector import (
    Control,
    Order,
    OrderAggregator,
    Quote,
    QuoteSet,
    TapeArchiver,
    Trade,
    classify,
    dte,
    parse_frame,
    parse_occ,
    trade_to_archive_row,
)

UTC = timezone.utc
ET_OFFSET = timedelta(hours=-4)  # EDT in summer; EDST crosses don't affect test logic


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def ts(s: str) -> datetime:
    """Parse an RFC-3339 timestamp."""
    return datetime.fromisoformat(s.replace("Z", "+00:00")).astimezone(UTC)


def _trade(
    contract: str = "AAPL241220C00150000",
    ts_str: str = "2026-10-29T14:00:00Z",
    price: float = 2.05,
    size: int = 10,
    exchange: str = "C",
    conditions: tuple[str, ...] = (),
) -> Trade:
    parsed = parse_occ(contract)
    assert parsed is not None
    ticker, expiry_str, strike, right = parsed
    t = ts(ts_str)
    return Trade(
        contract=contract,
        ticker=ticker,
        expiry_str=expiry_str,
        strike=strike,
        right=right,
        ts=t,
        ts_raw=ts_str,
        price=price,
        size=size,
        exchange=exchange,
        conditions=conditions,
    )


def _quote(
    contract: str = "AAPL241220C00150000",
    bid: float = 2.0,
    ask: float = 2.1,
    ts_str: str = "2026-10-29T14:00:00Z",
) -> Quote:
    t = ts(ts_str)
    return Quote(
        contract=contract,
        bid=bid,
        ask=ask,
        bid_size=10,
        ask_size=10,
        ts=t,
        ts_raw=ts_str,
    )


def _make_trade_frame(
    contract: str = "AAPL241220C00150000",
    price: float = 2.05,
    size: int = 10,
    exchange: str = "C",
    conditions: list[str] | None = None,
    ts_str: str = "2026-10-29T14:00:00Z",
) -> bytes:
    msg = {
        "T": "t",
        "S": contract,
        "p": price,
        "s": size,
        "x": exchange,
        "c": conditions or [],
        "t": ts_str,
    }
    return msgpack.packb([msg])


def _make_quote_frame(
    contract: str = "AAPL241220C00150000",
    bid: float = 2.0,
    ask: float = 2.1,
    ts_str: str = "2026-10-29T14:00:00Z",
) -> bytes:
    msg = {
        "T": "q",
        "S": contract,
        "bp": bid,
        "bs": 10,
        "ap": ask,
        "as": 5,
        "t": ts_str,
    }
    return msgpack.packb([msg])


# ---------------------------------------------------------------------------
# parse_occ
# ---------------------------------------------------------------------------


def test_parse_occ_standard_symbol():
    assert parse_occ("AAPL241220C00150000") == ("AAPL", "241220", 150.0, "C")


def test_parse_occ_put():
    assert parse_occ("TSLA250117P00250000") == ("TSLA", "250117", 250.0, "P")


def test_parse_occ_fractional_strike():
    # 00012500 = 12.5
    assert parse_occ("SPY241220C00012500") == ("SPY", "241220", 12.5, "C")


def test_parse_occ_invalid_returns_none():
    for bad in ("", "AAPL", "aapl241220c00150000", "AAPL241220X00150000"):
        assert parse_occ(bad) is None, f"expected None for {bad!r}"


def test_parse_occ_numeric_ticker():
    # Some Alpaca symbols include digits in the ticker component
    assert parse_occ("BRK1241220C00150000") is not None


# ---------------------------------------------------------------------------
# DTE
# ---------------------------------------------------------------------------


def test_dte_same_day():
    # trade at noon ET on expiry day: 0 DTE
    trade_ts = datetime(2024, 12, 20, 17, 0, 0, tzinfo=UTC)  # 12:00 ET
    assert dte("241220", trade_ts) == 0


def test_dte_cross_midnight_utc():
    # Trade at 23:00 UTC on 2024-12-19 = 18:00 ET on 2024-12-19 → 1 DTE to 2024-12-20
    trade_ts = datetime(2024, 12, 19, 23, 0, 0, tzinfo=UTC)
    assert dte("241220", trade_ts) == 1


def test_dte_uses_et_not_utc():
    # 02:00 UTC on 2024-12-20 = 21:00 ET on 2024-12-19 (EST=UTC-5)
    # → trade_date = 2024-12-19 ET → DTE = 1
    trade_ts = datetime(2024, 12, 20, 2, 0, 0, tzinfo=UTC)
    assert dte("241220", trade_ts) == 1


# ---------------------------------------------------------------------------
# classify — side rules
# ---------------------------------------------------------------------------


def test_classify_buy_at_or_above_ask():
    t = _trade(price=2.1, size=10)
    q = _quote(bid=2.0, ask=2.1)
    result = classify(t, q, t.ts)
    assert result["side"] == "buy"
    assert result["side_source"] == "quote"


def test_classify_sell_at_or_below_bid():
    t = _trade(price=2.0, size=10)
    q = _quote(bid=2.0, ask=2.1)
    result = classify(t, q, t.ts)
    assert result["side"] == "sell"
    assert result["side_source"] == "quote"


def test_classify_mid_between_bid_and_ask():
    t = _trade(price=2.05, size=10)
    q = _quote(bid=2.0, ask=2.1)
    result = classify(t, q, t.ts)
    assert result["side"] == "mid"
    assert result["side_source"] == "quote"


def test_classify_no_quote_is_unknown_none():
    t = _trade(price=2.05, size=10)
    result = classify(t, None, t.ts)
    assert result["side"] == "unknown"
    assert result["side_source"] == "none"


def test_classify_quote_after_trade_is_unused():
    """A quote stamped AFTER the trade is never used for its side (Q1 decision)."""
    t = _trade(ts_str="2026-10-29T14:00:00Z", price=2.1)
    q = _quote(ts_str="2026-10-29T14:00:01Z")  # 1 s after the trade
    result = classify(t, q, t.ts)
    assert result["side"] == "unknown"
    assert result["side_source"] == "none"


def test_classify_quote_older_than_3s_is_none():
    t = _trade(ts_str="2026-10-29T14:00:03.001Z", price=2.1)
    # quote is 3.001 s before trade: exceeds FLOW_QUOTE_MAX_AGE (3 s)
    q = _quote(ts_str="2026-10-29T14:00:00Z")  # exactly 3.001 s before the trade
    result = classify(t, q, t.ts)
    assert result["side"] == "unknown"
    assert result["side_source"] == "none"


def test_classify_quote_exactly_3s_old_is_usable():
    t = _trade(ts_str="2026-10-29T14:00:03Z", price=2.1)
    q = _quote(ts_str="2026-10-29T14:00:00Z", ask=2.1)  # exactly 3 s before
    result = classify(t, q, t.ts)
    assert result["side"] == "buy"
    assert result["side_source"] == "quote"


def test_classify_one_sided_book_is_unknown():
    t = _trade(price=2.05)
    q = _quote(bid=0.0, ask=2.1)
    result = classify(t, q, t.ts)
    assert result["side"] == "unknown"
    assert result["side_source"] == "none"


def test_classify_crossed_book_is_unknown():
    t = _trade(price=2.05)
    q = _quote(bid=2.2, ask=2.1)  # crossed: ask < bid
    result = classify(t, q, t.ts)
    assert result["side"] == "unknown"
    assert result["side_source"] == "none"


# ---------------------------------------------------------------------------
# classify — condition-code labels
# ---------------------------------------------------------------------------


def test_classify_S_is_sweep():
    t = _trade(conditions=("S",))
    result = classify(t, None, t.ts)
    assert result["sweep"] is True
    assert result["multi_leg"] is False


def test_classify_b_is_sweep_and_auction():
    t = _trade(conditions=("b",))
    result = classify(t, None, t.ts)
    assert result["sweep"] is True
    assert result["auction"] is True
    assert result["cross"] is False


def test_classify_d_is_sweep_and_cross():
    t = _trade(conditions=("d",))
    result = classify(t, None, t.ts)
    assert result["sweep"] is True
    assert result["cross"] is True
    assert result["auction"] is False


def test_classify_f_through_l_are_multi_leg():
    for c in ("f", "g", "h", "i", "j", "l"):
        t = _trade(conditions=(c,))
        result = classify(t, None, t.ts)
        assert result["multi_leg"] is True, f"condition {c!r} must be multi_leg"
        assert result["sweep"] is False


def test_classify_no_conditions_no_labels():
    t = _trade(conditions=())
    result = classify(t, None, t.ts)
    assert result["sweep"] is False
    assert result["auction"] is False
    assert result["cross"] is False
    assert result["multi_leg"] is False


# ---------------------------------------------------------------------------
# classify — premium
# ---------------------------------------------------------------------------


def test_classify_premium_equals_price_times_size_times_100():
    t = _trade(price=2.05, size=10)
    result = classify(t, None, t.ts)
    assert result["premium"] == pytest.approx(2.05 * 10 * 100)


# ---------------------------------------------------------------------------
# OrderAggregator — grouping
# ---------------------------------------------------------------------------


def _agg_with_quote(
    contract: str = "AAPL241220C00150000",
    price: float = 2.1,
    size: int = 10,
    exchange: str = "C",
    ts_str: str = "2026-10-29T14:00:00Z",
    conditions: tuple[str, ...] = (),
) -> tuple[OrderAggregator, Trade, dict]:
    agg = OrderAggregator()
    t = _trade(contract=contract, price=price, size=size, exchange=exchange,
               ts_str=ts_str, conditions=conditions)
    q = _quote(contract=contract, bid=2.0, ask=2.1, ts_str=ts_str)
    labels = classify(t, q, t.ts)
    labels["_quote"] = q
    return agg, t, labels


def test_aggregator_first_trade_opens_window():
    agg = OrderAggregator()
    t = _trade()
    q = _quote()
    labels = classify(t, q, t.ts)
    labels["_quote"] = q
    closed = agg.feed(t, labels)
    assert closed == []
    assert len(agg._open) == 1


def test_aggregator_same_side_within_window_merges():
    agg, t1, labels1 = _agg_with_quote(ts_str="2026-10-29T14:00:00Z", price=2.1)
    closed1 = agg.feed(t1, labels1)
    # Second trade at same price (→ same side "buy") 500ms later
    t2 = _trade(ts_str="2026-10-29T14:00:00.500Z", price=2.1)
    q2 = _quote(ts_str="2026-10-29T14:00:00Z", bid=2.0, ask=2.1)
    labels2 = classify(t2, q2, t2.ts)
    labels2["_quote"] = q2
    closed2 = agg.feed(t2, labels2)
    assert closed1 == [] and closed2 == []
    key = (t1.contract, labels1["side"])
    order = agg._open[key]
    assert order.trade_count == 2
    assert order.size == t1.size + t2.size


def test_aggregator_trade_after_window_closes_old_starts_new():
    agg, t1, labels1 = _agg_with_quote(ts_str="2026-10-29T14:00:00Z", price=2.1)
    agg.feed(t1, labels1)
    # New trade arrives 2 seconds later (> 1000ms window), same side "buy"
    t2 = _trade(ts_str="2026-10-29T14:00:02Z", price=2.1)
    q2 = _quote(ts_str="2026-10-29T14:00:02Z", bid=2.0, ask=2.1)
    labels2 = classify(t2, q2, t2.ts)
    labels2["_quote"] = q2
    closed = agg.feed(t2, labels2)
    assert len(closed) == 1
    assert closed[0].trade_count == 1
    assert agg._open[(t2.contract, labels2["side"])].trade_count == 1


def test_aggregator_different_sides_never_merge():
    agg = OrderAggregator()
    # buy trade
    t_buy = _trade(price=2.1, ts_str="2026-10-29T14:00:00Z")
    q = _quote(bid=2.0, ask=2.1, ts_str="2026-10-29T14:00:00Z")
    labels_buy = classify(t_buy, q, t_buy.ts)
    labels_buy["_quote"] = q
    agg.feed(t_buy, labels_buy)
    # sell trade at same instant on same contract
    t_sell = _trade(price=2.0, ts_str="2026-10-29T14:00:00Z")
    labels_sell = classify(t_sell, q, t_sell.ts)
    labels_sell["_quote"] = q
    agg.feed(t_sell, labels_sell)
    assert len(agg._open) == 2  # buy and sell windows are separate


def test_aggregator_sweep_across_exchanges_within_1s_is_one_order():
    """A sweep fills on multiple exchanges within 1s — these are ONE order."""
    agg = OrderAggregator()
    base = "2026-10-29T14:00:00Z"
    q = _quote(bid=2.0, ask=2.1, ts_str=base)
    exchanges = ["C", "X", "A"]
    for i, exch in enumerate(exchanges):
        t = _trade(
            price=2.1,
            exchange=exch,
            ts_str=f"2026-10-29T14:00:00.{i*200:03d}Z",
            conditions=("S",),
        )
        labels = classify(t, q, t.ts)
        labels["_quote"] = q
        agg.feed(t, labels)
    # All three should be in the same order
    assert len(agg._open) == 1
    (key, order) = next(iter(agg._open.items()))
    assert order.trade_count == 3
    assert order.exchange_count == 3


def test_aggregator_flush_emits_idle_windows():
    agg, t, labels = _agg_with_quote(ts_str="2026-10-29T14:00:00Z")
    agg.feed(t, labels)
    # Flush at 1 ms after window: nothing yet
    flushed = agg.flush(ts("2026-10-29T14:00:01Z"))
    assert flushed == []
    # Flush at 1001 ms: the window is expired
    flushed = agg.flush(ts("2026-10-29T14:00:01.001Z"))
    assert len(flushed) == 1


# ---------------------------------------------------------------------------
# OrderAggregator — premium / VWAP / block
# ---------------------------------------------------------------------------


def test_aggregator_premium_equals_vwap_times_size_times_100():
    agg, t, labels = _agg_with_quote(price=2.0, size=10)
    agg.feed(t, labels)
    order = next(iter(agg._open.values()))
    assert order.premium == pytest.approx(order.vwap * order.size * 100)


def test_aggregator_vwap_weighted_correctly():
    agg = OrderAggregator()
    contract = "AAPL241220C00150000"
    # Use bid=1.9, ask=2.2 so prices 2.0 and 2.05 are both "mid" (between bid and ask)
    q = _quote(contract=contract, bid=1.9, ask=2.2, ts_str="2026-10-29T14:00:00Z")

    def feed(price: float, size: int, ts_str: str) -> None:
        t = _trade(contract=contract, price=price, size=size, ts_str=ts_str)
        labels = classify(t, q, t.ts)
        labels["_quote"] = q
        agg.feed(t, labels)

    feed(2.0, 10, "2026-10-29T14:00:00Z")
    feed(2.1, 20, "2026-10-29T14:00:00.100Z")  # same "mid" side
    # VWAP = (2.0*10 + 2.1*20) / 30 = (20 + 42) / 30 = 62/30
    order = next(iter(agg._open.values()))
    assert order.vwap == pytest.approx((2.0 * 10 + 2.1 * 20) / 30)
    assert order.premium == pytest.approx(order.vwap * order.size * 100)


def test_block_at_premium_threshold_inclusive():
    agg = OrderAggregator(block_premium=250_000, block_size=1_000)
    # premium = 2.5 * 1000 * 100 = 250,000 exactly
    t = _trade(price=2.5, size=1000)
    q = _quote(bid=2.4, ask=2.5)
    labels = classify(t, q, t.ts)
    labels["_quote"] = q
    agg.feed(t, labels)
    order = next(iter(agg._open.values()))
    assert order.premium == pytest.approx(250_000.0)
    assert order.block is True  # at the threshold → block


def test_block_at_size_threshold_inclusive():
    agg = OrderAggregator(block_premium=250_000, block_size=1_000)
    # premium = 1.0 * 1000 * 100 = 100,000 < threshold, but size = 1000 = threshold
    t = _trade(price=1.0, size=1_000)
    q = _quote(bid=0.9, ask=1.0)
    labels = classify(t, q, t.ts)
    labels["_quote"] = q
    agg.feed(t, labels)
    order = next(iter(agg._open.values()))
    assert order.block is True


def test_block_thresholds_recorded_in_order():
    agg = OrderAggregator(block_premium=300_000, block_size=2_000)
    t = _trade(price=2.0, size=5)
    q = _quote(bid=1.9, ask=2.0)
    labels = classify(t, q, t.ts)
    labels["_quote"] = q
    agg.feed(t, labels)
    order = next(iter(agg._open.values()))
    assert order.block_premium_threshold == 300_000
    assert order.block_size_threshold == 2_000


# ---------------------------------------------------------------------------
# OrderAggregator — deterministic order_id
# ---------------------------------------------------------------------------


def test_order_id_is_deterministic_on_replay():
    """Same trades replayed produce identical order_ids (for BFF deduplication)."""
    def make_order() -> Order:
        agg = OrderAggregator()
        t = _trade(ts_str="2026-10-29T14:00:00Z", price=2.1, size=10)
        q = _quote(ts_str="2026-10-29T14:00:00Z")
        labels = classify(t, q, t.ts)
        labels["_quote"] = q
        agg.feed(t, labels)
        return next(iter(agg._open.values()))

    assert make_order().order_id == make_order().order_id


def test_order_id_includes_contract_ts_ns_side():
    t = _trade(ts_str="2026-10-29T14:00:00Z", price=2.1)
    q = _quote(ts_str="2026-10-29T14:00:00Z")
    labels = classify(t, q, t.ts)
    labels["_quote"] = q
    agg = OrderAggregator()
    agg.feed(t, labels)
    order = next(iter(agg._open.values()))
    oid = order.order_id
    assert "AAPL241220C00150000" in oid
    assert "buy" in oid


# ---------------------------------------------------------------------------
# QuoteSet
# ---------------------------------------------------------------------------


def _universe(
    tickers: list[str],
    n_per_ticker: int = 3,
    moneyness_step: float = 0.01,
) -> list[tuple[str, str, float, int]]:
    """Build a synthetic universe of contracts for testing."""
    result = []
    for ticker in tickers:
        for i in range(n_per_ticker):
            contract = f"{ticker}241220C0{i:07d}"
            moneyness = round((i + 1) * moneyness_step, 4)
            dte_val = 3 + i
            result.append((contract, ticker, moneyness, dte_val))
    return result


def test_quoteset_never_exceeds_cap():
    qs = QuoteSet()
    universe = _universe(["AAPL", "QQQ", "TSLA", "NVDA"], n_per_ticker=100)
    cap = 10
    chosen, _, _ = qs.choose(universe, {}, cap=cap, universe_share=0.7)
    assert len(chosen) <= cap


def test_quoteset_fills_universe_share_first():
    qs = QuoteSet()
    universe = _universe(["AAPL", "QQQ"], n_per_ticker=50)
    cap = 10
    rolling = {"EXTRA_CONTRACT": 999_999.0}  # a premium contract not in universe
    chosen, _, _ = qs.choose(universe, rolling, cap=cap, universe_share=0.7)
    # Universe share = 7; there are 100 universe contracts so 7 should be from universe
    universe_contracts = {e[0] for e in universe}
    in_universe = chosen & universe_contracts
    assert len(in_universe) == 7


def test_quoteset_interleaves_across_names():
    """Universe contracts are taken one ticker at a time, round-robin."""
    qs = QuoteSet()
    # 2 tickers, 3 contracts each, cap=6, universe_share=1.0
    universe = [
        ("A241220C00010000", "A", 0.01, 1),
        ("A241220C00020000", "A", 0.02, 2),
        ("A241220C00030000", "A", 0.03, 3),
        ("B241220C00010000", "B", 0.01, 1),
        ("B241220C00020000", "B", 0.02, 2),
        ("B241220C00030000", "B", 0.03, 3),
    ]
    chosen, _, _ = qs.choose(universe, {}, cap=6, universe_share=1.0)
    # All 6 should be chosen
    assert len(chosen) == 6
    assert chosen == frozenset(c[0] for c in universe)


def test_quoteset_returns_exact_subscribe_unsubscribe_diff():
    qs = QuoteSet()
    universe1 = [
        ("A241220C00010000", "A", 0.01, 1),
        ("A241220C00020000", "A", 0.02, 2),
    ]
    chosen1, sub1, unsub1 = qs.choose(universe1, {}, cap=2, universe_share=1.0)
    assert set(sub1) == {"A241220C00010000", "A241220C00020000"}
    assert unsub1 == []

    # Second call: remove one, add another
    universe2 = [
        ("A241220C00010000", "A", 0.01, 1),
        ("B241220C00010000", "B", 0.01, 1),
    ]
    chosen2, sub2, unsub2 = qs.choose(universe2, {}, cap=2, universe_share=1.0)
    assert set(sub2) == {"B241220C00010000"}
    assert set(unsub2) == {"A241220C00020000"}


def test_quoteset_rolling_premium_fills_remaining_slots():
    qs = QuoteSet()
    universe = [("A241220C00010000", "A", 0.01, 1)]  # only 1 universe contract
    rolling = {
        "B241220C00010000": 50_000.0,
        "C241220C00010000": 100_000.0,
    }
    chosen, _, _ = qs.choose(universe, rolling, cap=3, universe_share=0.5)
    # universe_share=0.5, cap=3 → 1 universe slot (floor(1.5)=1)
    # 2 premium slots → both rolling contracts should be included
    assert "A241220C00010000" in chosen
    assert "C241220C00010000" in chosen  # higher premium
    assert "B241220C00010000" in chosen  # second premium


def test_quoteset_universe_share_priority_before_rolling():
    """Universe contracts always take their share before rolling-premium contracts."""
    qs = QuoteSet()
    universe = _universe(["AAPL"], n_per_ticker=10)
    # Rolling premium contracts that are NOT in the universe
    rolling = {f"FAKE{i:06d}": float(i * 10_000) for i in range(100)}
    cap = 10
    chosen, _, _ = qs.choose(universe, rolling, cap=cap, universe_share=0.7)
    universe_set = {e[0] for e in universe}
    in_universe = len(chosen & universe_set)
    assert in_universe == 7  # exactly universe_share * cap


# ---------------------------------------------------------------------------
# TapeArchiver
# ---------------------------------------------------------------------------


def _make_rows(n: int, ts_base: str = "2026-10-29T14:00:00Z") -> list[dict]:
    return [
        {
            "t": ts_base,
            "S": f"AAPL241220C0{i:07d}",
            "p": 2.0 + i * 0.01,
            "s": 10,
            "x": "C",
            "c": [],
            "bp": 1.9,
            "ap": 2.1,
            "bs": 5,
            "as": 5,
            "qt": ts_base,
        }
        for i in range(n)
    ]


def test_archiver_three_flushes_produce_valid_gzip(tmp_path):
    arch = TapeArchiver(tmp_path / "archive")
    flush_ts = ts("2026-10-29T14:00:00Z")
    for batch in [_make_rows(5), _make_rows(3), _make_rows(7)]:
        arch.write_flush(batch, flush_ts)

    gz_file = tmp_path / "archive" / "2026-10-29.trades.ndjson.gz"
    assert gz_file.exists()

    # gzip -t must pass
    result = subprocess.run(["gzip", "-t", str(gz_file)], capture_output=True)
    assert result.returncode == 0, result.stderr.decode()

    # decompressed line count = 5 + 3 + 7 = 15
    with gzip.open(gz_file, "rt") as f:
        lines = f.readlines()
    assert len(lines) == 15


def test_archiver_manifest_sha256_matches_file(tmp_path):
    arch = TapeArchiver(tmp_path / "archive")
    flush_ts = ts("2026-10-29T14:00:00Z")
    rows = _make_rows(10)
    rows[0]["t"] = "2026-10-29T14:00:00Z"
    rows[-1]["t"] = "2026-10-29T15:00:00Z"
    arch.write_flush(rows, flush_ts)

    manifest = arch.close_session("2026-10-29", "09:25-16:20", "test-build")
    assert manifest is not None

    gz_file = tmp_path / "archive" / "2026-10-29.trades.ndjson.gz"
    import hashlib
    h = hashlib.sha256()
    with open(gz_file, "rb") as f:
        h.update(f.read())
    assert manifest["sha256"] == h.hexdigest()
    assert manifest["trades"] == 10
    assert manifest["date"] == "2026-10-29"
    assert manifest["window"] == "09:25-16:20"
    assert manifest["build"] == "test-build"


def test_archiver_disk_cap_stops_writes_and_records_gap(tmp_path):
    arch = TapeArchiver(
        tmp_path / "archive",
        cap_bytes=0,  # 0 byte cap → dir_size (0) is NOT < 0 → always refused
        free_floor_bytes=0,
    )
    flush_ts = ts("2026-10-29T14:00:00Z")
    arch.write_flush(_make_rows(5), flush_ts)

    # File should NOT have been written
    gz_file = tmp_path / "archive" / "2026-10-29.trades.ndjson.gz"
    assert not gz_file.exists()

    # No gap recorded yet (gap is recorded on recovery, not on first stop)
    assert len(arch.gaps) == 0

    # When disk frees up (raise cap), next write records the gap and succeeds
    arch._cap_bytes = 10_000_000_000
    arch.write_flush(_make_rows(3), flush_ts)
    assert gz_file.exists()
    assert len(arch.gaps) == 1
    assert arch.gaps[0]["reason"] == "disk_cap"


def test_archiver_continues_classifying_during_disk_stop(tmp_path):
    """Disk guard stops writing but the loop keeps running (classifying)."""
    arch = TapeArchiver(tmp_path / "archive", cap_bytes=0, free_floor_bytes=0)
    flush_ts = ts("2026-10-29T14:00:00Z")
    # These writes are all refused
    for _ in range(5):
        arch.write_flush(_make_rows(2), flush_ts)
    assert arch.trades_today == 0
    # The archiver object is still alive — no exception raised


def test_archiver_free_floor_stops_writes(tmp_path):
    arch = TapeArchiver(
        tmp_path / "archive",
        cap_bytes=10_000_000_000,
        free_floor_bytes=10_000_000_000_000,  # absurdly large: always below floor
    )
    flush_ts = ts("2026-10-29T14:00:00Z")
    arch.write_flush(_make_rows(5), flush_ts)
    gz_file = tmp_path / "archive" / "2026-10-29.trades.ndjson.gz"
    assert not gz_file.exists()


# ---------------------------------------------------------------------------
# parse_frame
# ---------------------------------------------------------------------------


def test_parse_frame_trade():
    data = _make_trade_frame(price=2.05, size=10, conditions=["S"])
    result = parse_frame(data)
    assert len(result) == 1
    t = result[0]
    assert isinstance(t, Trade)
    assert t.contract == "AAPL241220C00150000"
    assert t.price == pytest.approx(2.05)
    assert t.size == 10
    assert t.conditions == ("S",)


def test_parse_frame_quote():
    data = _make_quote_frame(bid=2.0, ask=2.1)
    result = parse_frame(data)
    assert len(result) == 1
    q = result[0]
    assert isinstance(q, Quote)
    assert q.bid == pytest.approx(2.0)
    assert q.ask == pytest.approx(2.1)


def test_parse_frame_control_success():
    data = msgpack.packb([{"T": "success", "msg": "authenticated"}])
    result = parse_frame(data)
    assert len(result) == 1
    c = result[0]
    assert isinstance(c, Control)
    assert c.action == "success"
    assert c.msg == "authenticated"
    assert c.code is None


def test_parse_frame_control_error_406():
    data = msgpack.packb([{"T": "error", "code": 406, "msg": "connection limit exceeded"}])
    result = parse_frame(data)
    c = result[0]
    assert isinstance(c, Control)
    assert c.code == 406
    assert c.action == "error"


def test_parse_frame_bad_symbol_returns_control():
    data = msgpack.packb([{"T": "t", "S": "not_an_occ", "p": 2.0, "s": 10, "x": "C", "c": [], "t": "2026-10-29T14:00:00Z"}])
    result = parse_frame(data)
    assert len(result) == 1
    assert isinstance(result[0], Control)
    assert result[0].action == "bad_symbol"


def test_parse_frame_non_list_is_wrapped():
    # A single dict (not a list) should be treated as a one-element list
    data = msgpack.packb({"T": "success", "msg": "connected"})
    result = parse_frame(data)
    assert len(result) == 1
    assert isinstance(result[0], Control)


def test_parse_frame_invalid_bytes_returns_control():
    result = parse_frame(b"\xff\xfe\x00\x01")
    assert len(result) == 1
    assert result[0].action == "decode_error"


# ---------------------------------------------------------------------------
# Fixture file: tests/fixtures/flow_sample.msgpack
# ---------------------------------------------------------------------------

FIXTURES_DIR = pathlib.Path(__file__).parent / "fixtures"


def test_fixture_file_exists_and_decodes():
    """The msgpack fixture file must exist and contain at least one trade."""
    fixture = FIXTURES_DIR / "flow_sample.msgpack"
    assert fixture.exists(), (
        "tests/fixtures/flow_sample.msgpack not found — it must be created as part of Step 1"
    )
    with open(fixture, "rb") as fh:
        data = fh.read()
    result = parse_frame(data)
    trades = [r for r in result if isinstance(r, Trade)]
    assert len(trades) >= 1, "fixture must contain at least one trade"


# ---------------------------------------------------------------------------
# trade_to_archive_row
# ---------------------------------------------------------------------------


def test_trade_to_archive_row_with_quote():
    t = _trade(price=2.05, size=10, exchange="C", conditions=("S",))
    q = _quote(bid=2.0, ask=2.1, ts_str="2026-10-29T14:00:00Z")
    row = trade_to_archive_row(t, q)
    assert row["t"] == t.ts_raw
    assert row["S"] == t.contract
    assert row["p"] == pytest.approx(2.05)
    assert row["s"] == 10
    assert row["x"] == "C"
    assert row["c"] == ["S"]
    assert row["bp"] == pytest.approx(2.0)
    assert row["ap"] == pytest.approx(2.1)
    assert row["qt"] == q.ts_raw


def test_trade_to_archive_row_without_quote():
    t = _trade()
    row = trade_to_archive_row(t, None)
    assert row["bp"] is None
    assert row["ap"] is None
    assert row["bs"] is None
    assert row["as"] is None
    assert row["qt"] is None
