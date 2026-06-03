"""Tests for the watchlist-shape detector.

The detector decides whether a Discord message body is the daily watchlist
(option-level grid) rather than prose or the BTO/STC copytrade grammar. It is
pure: ``is_watchlist(text) -> bool``.
"""

from __future__ import annotations

from ohmytradeagent_sidecar.watchlist_detector import is_watchlist


def test_accepts_two_ticker_lines() -> None:
    text = "SPY 762c > 761.00\nQQQ 480p < 481.00"
    assert is_watchlist(text) is True


def test_accepts_ticker_plus_continuation() -> None:
    text = "SPY 762c > 761.00\n753p < 754.00"
    assert is_watchlist(text) is True


def test_rejects_single_line() -> None:
    assert is_watchlist("SPY 762c > 761.00") is False


def test_rejects_prose() -> None:
    text = (
        "Hey everyone!\n"
        "100% profit now :vibe:\n"
        "Playing this flag BTW. Tight stop under 417"
    )
    assert is_watchlist(text) is False


def test_rejects_bto_stc_grammar() -> None:
    text = "BTO NVDA 4/27 205c @ 2.11\nSTC NVDA 4/27 205c @ 2.50 partial"
    assert is_watchlist(text) is False


def test_case_insensitive_right() -> None:
    text = "SPY 762C > 761.00\nQQQ 480P < 481.00"
    assert is_watchlist(text) is True


def test_blank_lines_between_levels_ok() -> None:
    text = "SPY 762c > 761.00\n\n\nQQQ 480p < 481.00"
    assert is_watchlist(text) is True
