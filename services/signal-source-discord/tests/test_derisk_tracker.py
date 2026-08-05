"""Tests for the per-author recent-BTO tracker used by de-risk attribution.

Attribution rule (PLAN-2026-08-04-copytrade-derisk-followup-cue):
- same AUTHOR only (other authors' BTOs are invisible),
- within a bounded look-back window,
- ticker-aware: if the cue names ticker(s) matching a held BTO → that ticker's
  most-recent BTO; a named-but-unheld ticker → None (never mis-attribute),
- generic (no ticker named) → the author's single most-recent in-window BTO.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone

from ohmytradeagent_sidecar.derisk_tracker import RecentBto, RecentBtoTracker


def _bto(
    ticker: str,
    posted_at: datetime,
    *,
    signal_id: str = "sig",
    strike: float = 95.0,
    price: float = 1.34,
) -> RecentBto:
    return RecentBto(
        ticker=ticker,
        expiry=date(2026, 8, 3),
        strike=strike,
        right="C",
        price=price,
        signal_id=signal_id,
        posted_at=posted_at,
    )


T0 = datetime(2026, 7, 31, 17, 46, tzinfo=timezone.utc)


def test_generic_returns_most_recent_same_author() -> None:
    tr = RecentBtoTracker(window_secs=3600, per_author_cap=20)
    tr.record("TTT", _bto("SPY", T0, signal_id="spy-1"))
    tr.record("TTT", _bto("INTC", T0 + timedelta(minutes=5), signal_id="intc-1"))

    got = tr.resolve("TTT", tickers=(), now=T0 + timedelta(minutes=10))
    assert got is not None
    assert got.ticker == "INTC"
    assert got.signal_id == "intc-1"


def test_ticker_aware_picks_named_even_if_older() -> None:
    tr = RecentBtoTracker(window_secs=3600, per_author_cap=20)
    tr.record("TTT", _bto("INTC", T0, signal_id="intc-1"))
    tr.record("TTT", _bto("AAPL", T0 + timedelta(minutes=5), signal_id="aapl-1"))

    got = tr.resolve("TTT", tickers=("INTC",), now=T0 + timedelta(minutes=10))
    assert got is not None
    assert got.ticker == "INTC"


def test_named_but_unheld_ticker_returns_none() -> None:
    tr = RecentBtoTracker(window_secs=3600, per_author_cap=20)
    tr.record("TTT", _bto("INTC", T0, signal_id="intc-1"))

    got = tr.resolve("TTT", tickers=("TSLA",), now=T0 + timedelta(minutes=10))
    assert got is None


def test_other_authors_are_invisible() -> None:
    tr = RecentBtoTracker(window_secs=3600, per_author_cap=20)
    tr.record("SomeoneElse", _bto("INTC", T0, signal_id="intc-1"))

    assert tr.resolve("TTT", tickers=(), now=T0 + timedelta(minutes=1)) is None


def test_out_of_window_bto_ignored() -> None:
    tr = RecentBtoTracker(window_secs=3600, per_author_cap=20)
    tr.record("TTT", _bto("INTC", T0, signal_id="intc-1"))

    # Cue arrives 61 minutes later — past the 60-minute window.
    got = tr.resolve("TTT", tickers=(), now=T0 + timedelta(minutes=61))
    assert got is None


def test_most_recent_among_same_ticker() -> None:
    tr = RecentBtoTracker(window_secs=3600, per_author_cap=20)
    tr.record("TTT", _bto("INTC", T0, signal_id="intc-old", price=1.10))
    tr.record("TTT", _bto("INTC", T0 + timedelta(minutes=5), signal_id="intc-new", price=1.34))

    got = tr.resolve("TTT", tickers=("INTC",), now=T0 + timedelta(minutes=10))
    assert got is not None
    assert got.signal_id == "intc-new"


def test_no_history_returns_none() -> None:
    tr = RecentBtoTracker(window_secs=3600, per_author_cap=20)
    assert tr.resolve("TTT", tickers=(), now=T0) is None
