"""Parser tests.

All 20 sample lines from the TradingTheTrend/TB22/Edtrader channel pasted
during design review must parse (or be correctly ignored) against
today = 2026-04-23 (the date we captured them).

Adversarial cases cover formatting variants the grammar must tolerate.
"""

from __future__ import annotations

from datetime import date

import pytest

from ohmytradeagent_sidecar.parser import (
    DeriskCue,
    ParsedSignal,
    classify_derisk,
    parse_message,
)


REF_DATE = date(2026, 4, 23)


def _one(text: str) -> ParsedSignal:
    sigs = parse_message(text, today=REF_DATE)
    assert len(sigs) == 1, f"expected 1 signal, got {len(sigs)}: {sigs!r}"
    return sigs[0]


def _none(text: str) -> None:
    assert parse_message(text, today=REF_DATE) == []


# ---------------------------------------------------------------------------
# Sample messages from the channel (2026-04-17 .. 2026-04-23)
# ---------------------------------------------------------------------------

def test_sample_stc_googl_partial():
    s = _one("STC GOOGL 4/27 345c @ 2.52 partial")
    assert s.action == "STC"
    assert s.ticker == "GOOGL"
    assert s.expiry == date(2026, 4, 27)
    assert s.strike == 345.0
    assert s.right == "C"
    assert s.price == 2.52
    assert s.tail == "partial"


def test_sample_stc_nvda_half_out():
    s = _one("STC NVDA 4/27 205c @ 2.03 partial. Half out")
    assert s.action == "STC"
    assert s.price == 2.03
    assert s.tail == "partial. Half out"


def test_sample_stc_spy_leading_dot_price():
    # "@ .55" — leading-dot price, and "stop hit" in tail
    s = _one("STC SPY 4/24 715c @ .55 stop hit, too much chop and not enough time left on these")
    assert s.ticker == "SPY"
    assert s.price == 0.55
    assert "stop hit" in s.tail


def test_sample_stc_msft_holding_freebies():
    s = _one("STC MSFT 4/24 430c @ 4.30 partial, holding a few freebies")
    assert s.action == "STC"
    assert s.price == 4.30


def test_sample_avg_message():
    # AVG retrospective: "AVG NVDA 4/27 205c @ 1.40, added @ .98"
    s = _one("AVG NVDA 4/27 205c @ 1.40, added @ .98")
    assert s.action == "AVG"
    assert s.price == 1.40
    assert "added @ .98" in s.tail


def test_sample_stc_all_out():
    s = _one("STC MSFT 4/24 430c @ 5.25 all out")
    assert s.tail == "all out"


def test_sample_stc_taking_a_few():
    s = _one("STC NVDA 4/27 205c @ 1.58 nice to see some green. Taking a few.")
    assert s.price == 1.58
    assert "Taking a few" in s.tail


def test_sample_stc_taking_more():
    s = _one("STC NVDA 4/27 205c @ 1.73 partial")
    assert s.tail == "partial"


def test_sample_multiline_two_stcs():
    # Two STCs in a single Discord message separated by newline
    msg = (
        "STC MSFT 4/24 430c @ 2.50 partial taking a few here\n"
        "STC MSFT 4/24 430c @ 2.75 partial taking it as it comes. Half out"
    )
    sigs = parse_message(msg, today=REF_DATE)
    assert len(sigs) == 2
    assert sigs[0].price == 2.50
    assert sigs[1].price == 2.75
    assert "Half out" in sigs[1].tail


def test_sample_stc_with_continuation_commentary():
    # Line 1 parses; line 2 ("mostly out now...") is commentary — no BTO/STC prefix
    msg = (
        "STC MSFT 4/24 430c @ 3.20 partial\n"
        "mostly out now, holding a few. Also hit 425c from \u26d4 options-watchlist"
    )
    sigs = parse_message(msg, today=REF_DATE)
    assert len(sigs) == 1
    assert sigs[0].tail == "partial"


def test_sample_bto_simple():
    s = _one("BTO NVDA 4/27 205c @ 2.11")
    assert s.action == "BTO"
    assert s.tail == ""


def test_sample_bto_msft():
    s = _one("BTO MSFT 4/24 430c @ 2.18")
    assert s.action == "BTO"
    assert s.expiry == date(2026, 4, 24)


def test_sample_bto_kweb_no_space_and_comma_tail():
    # Edtrader: "BTO KWEB 06/18 32c @1.2, loading up more shorter term on these."
    s = _one("BTO KWEB 06/18 32c @1.2, loading up more shorter term on these.")
    assert s.ticker == "KWEB"
    assert s.expiry == date(2026, 6, 18)
    assert s.strike == 32.0
    assert s.price == 1.2
    assert s.tail.startswith(",")


def test_sample_tb22_aapl_monthly():
    s = _one("STC AAPL 5/15 275c @ 8.3 partial  a few left")
    assert s.expiry == date(2026, 5, 15)
    assert s.price == 8.3


def test_sample_stc_uppercase_right():
    s = _one("STC NVDA 4/24 205C @ 1.60 stopped on the rest")
    assert s.right == "C"
    assert "stopped" in s.tail


def test_sample_edtrader_baba_no_space_at():
    # "@5.35" — no space after @
    s = _one(
        "STC BABA 07/17 165c @5.35 partial. Like NIO, just trimming one so you know we are up. "
        "Holding bulk, expecting nice ITM on these."
    )
    assert s.ticker == "BABA"
    assert s.expiry == date(2026, 7, 17)
    assert s.strike == 165.0
    assert s.price == 5.35


def test_sample_stc_partial_leaving_runners():
    s = _one("STC AAPL 4/24 270c @ 4.70 partial. Leaving free runners")
    assert s.expiry == date(2026, 4, 24)
    assert "Leaving free runners" in s.tail


def test_sample_tb22_stc_half_out():
    s = _one("STC AAPL 5/15 275c @ 7.2 partial half out.")
    assert "half out" in s.tail.lower()


# ---------------------------------------------------------------------------
# Noise / commentary lines — must NOT parse
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "text",
    [
        "100% from the avg down :vibe:",
        "100% profit now :vibe:",
        "finally over 100%...",
        "4.50 now still holding half",
        "Playing this flag BTW. Tight stop under 417",
        "Hate me all you want, I have my own plans and shorter term trades",
        "",
        "   ",
        "Partial",  # bare word, no prefix
        "mostly out now, holding a few",
    ],
)
def test_noise_lines_skipped(text):
    _none(text)


# ---------------------------------------------------------------------------
# Adversarial formatting
# ---------------------------------------------------------------------------

def test_lowercase_action():
    s = _one("bto aapl 4/27 150c @ 1.00")
    assert s.action == "BTO"
    assert s.ticker == "AAPL"


def test_no_space_anywhere():
    # Still requires at least one space between tokens; "@" can be tight
    s = _one("BTO AAPL 4/27 150c@1.00")
    assert s.price == 1.00


def test_two_digit_month_and_decimal_strike():
    s = _one("BTO AAPL 04/27 150.5c @ 1.00")
    assert s.expiry == date(2026, 4, 27)
    assert s.strike == 150.5


def test_leading_dot_price():
    s = _one("STC AAPL 4/27 150c @ .55")
    assert s.price == 0.55


def test_lowercase_right():
    s = _one("BTO AAPL 4/27 150p @ 1.00")
    assert s.right == "P"


def test_expiry_rolls_to_next_year():
    # Today 2026-04-23; "1/2" is in the past this year so rolls to 2027
    s = parse_message("BTO AAPL 1/2 150c @ 1.00", today=REF_DATE)[0]
    assert s.expiry == date(2027, 1, 2)


def test_expiry_same_day_stays_this_year():
    s = parse_message("BTO AAPL 4/23 150c @ 1.00", today=REF_DATE)[0]
    assert s.expiry == date(2026, 4, 23)


def test_expiry_with_explicit_year():
    s = parse_message("BTO AAPL 4/27/27 150c @ 1.00", today=REF_DATE)[0]
    assert s.expiry == date(2027, 4, 27)


def test_two_digit_day():
    s = _one("BTO AAPL 11/21 150c @ 1.00")
    assert s.expiry == date(2026, 11, 21)


def test_returns_list_not_single():
    assert isinstance(parse_message("BTO AAPL 4/27 150c @ 1.00", today=REF_DATE), list)


def test_pure_noise_block():
    msg = "Hey everyone!\n\n100% profit now :vibe:\n\nHolding runners"
    assert parse_message(msg, today=REF_DATE) == []


def test_mixed_signal_and_noise():
    msg = (
        "Hey everyone!\n"
        "BTO NVDA 4/27 205c @ 2.11\n"
        "Feeling bullish today\n"
        "STC NVDA 4/27 205c @ 2.50 partial\n"
    )
    sigs = parse_message(msg, today=REF_DATE)
    assert len(sigs) == 2
    assert sigs[0].action == "BTO"
    assert sigs[1].action == "STC"


# ---- de-risk cue classification (PLAN-2026-08-04-copytrade-derisk-followup-cue) ----


def test_classify_derisk_friday_message() -> None:
    # The exact 2026-07-31 escalation that followed the INTC 95c BTO.
    cue = classify_derisk(
        "I'm cool with going 0 or hero on these. Feel free to use your own stop"
    )
    assert isinstance(cue, DeriskCue)
    # "0 or hero" wins precedence over the "use your own stop" secondary cue.
    assert cue.matched_cue == "0 or hero"
    # No explicit ticker named ("on these") → generic attribution downstream.
    assert cue.tickers == ()


@pytest.mark.parametrize(
    "text",
    [
        "0 or hero",
        "zero or hero",
        "0-or-hero",
        "go 0 or hero on this one",
        "ZERO OR HERO",
        "use your own stop",
        "your own stop",
        "feel free to use your own stop",
    ],
)
def test_classify_derisk_variants_match(text: str) -> None:
    assert classify_derisk(text) is not None


@pytest.mark.parametrize(
    "text",
    [
        "risky",                     # too common — explicitly NOT a cue
        "this one is risky af",
        "half out",
        "taking profit here",
        "adding more here",
        "0 contracts left",          # "0" alone must not trip it
        "hero of the day",           # "hero" alone must not trip it
        "",
        "   ",
    ],
)
def test_classify_derisk_non_cues(text: str) -> None:
    assert classify_derisk(text) is None


def test_classify_derisk_extracts_named_ticker() -> None:
    cue = classify_derisk("0 or hero on INTC")
    assert cue is not None
    assert "INTC" in cue.tickers


def test_classify_derisk_extracts_multiple_tickers() -> None:
    cue = classify_derisk("zero or hero on INTC and SPY, use your own stop")
    assert cue is not None
    assert set(cue.tickers) >= {"INTC", "SPY"}


def test_classify_derisk_allcaps_cue_has_no_bogus_tickers() -> None:
    # An ALL-CAPS cue must read as generic ("on these"), not three ticker mentions.
    cue = classify_derisk("ZERO OR HERO")
    assert cue is not None
    assert cue.tickers == ()
