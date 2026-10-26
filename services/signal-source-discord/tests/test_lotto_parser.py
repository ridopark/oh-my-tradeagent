"""Lotto-alerts parser tests (PLAN-2026-10-26-copytrade-lotto-v1, phase 1).

The golden fixture is TradingTheTrend's #lotto-alerts history (r18, 113 messages,
25 trades). ``ttt_messages.json`` is replayed in snowflake order through a
``LottoBook`` and the per-message classification must equal the hand-labelled
``ttt_expected.json`` with zero diffs; the resulting BTO/STC stream must agree
with ``trades.csv``.
"""

from __future__ import annotations

import csv
import json
import pathlib
from datetime import date, datetime, timezone

import pytest

from ohmytradeagent_sidecar.lotto_parser import (
    AUTHOR,
    LottoBook,
    LottoSignal,
    classify_exit,
    occ_key,
    parse_entry,
)

FIXTURES = pathlib.Path(__file__).parent / "fixtures" / "lotto"
DISCORD_EPOCH_MS = 1420070400000


def _posted_at(message_id: str) -> datetime:
    """Snowflake → UTC datetime. The fixture's ``t`` is wrong on 4 reply rows."""
    ms = (int(message_id) >> 22) + DISCORD_EPOCH_MS
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc)


def _flat(text: str) -> str:
    # The fixture joins message lines with " | "; a Discord quote collapses them to a space.
    return " ".join(text.replace(" | ", " ").split())


def load_messages() -> list[dict]:
    """Fixture rows sorted by snowflake, with ``q`` resolved to ``reply_to_id``."""
    rows = sorted(
        json.loads((FIXTURES / "ttt_messages.json").read_text()), key=lambda r: int(r["i"])
    )
    out: list[dict] = []
    for row in rows:
        reply_to_id = None
        if row.get("q"):
            parents = [p for p in out if _flat(p["text"]).startswith(_flat(row["q"]))]
            assert parents, f"no parent for quote {row['q']!r}"
            reply_to_id = parents[-1]["id"]
        out.append(
            {
                "id": row["i"],
                "text": row["x"],
                "posted_at": _posted_at(row["i"]),
                "reply_to_id": reply_to_id,
            }
        )
    return out


def load_trades() -> list[dict]:
    with (FIXTURES / "trades.csv").open(newline="") as f:
        return list(csv.DictReader(f))


def replay(messages: list[dict], author: str = AUTHOR) -> list[tuple[dict, list[LottoSignal]]]:
    book = LottoBook(author=AUTHOR)
    return [
        (m, book.apply(m["id"], author, m["posted_at"], m["text"], m["reply_to_id"]))
        for m in messages
    ]


def _label(signals: list[LottoSignal]) -> dict:
    actions = [s.action for s in signals]
    if not signals:
        return {"label": "NONE", "contract": None, "price": None}
    if actions == ["STC", "BTO"]:
        label, sig = "ROLL", signals[1]
    elif actions == ["BTO"]:
        label, sig = "ENTRY", signals[0]
    elif actions == ["STC"]:
        label, sig = "EXIT", signals[0]
    else:
        raise AssertionError(f"unexpected signal shape {actions}")
    return {"label": label, "contract": sig.contract, "price": sig.price}


def _trades_from(replayed) -> list[dict]:
    """Group the signal stream into trades: a BTO opens one, STCs attach to the
    latest trade on the same contract."""
    trades: list[dict] = []
    for m, signals in replayed:
        for s in signals:
            if s.action == "BTO":
                trades.append({"entry": s, "exits": []})
            else:
                trade = next(t for t in reversed(trades) if t["entry"].contract == s.contract)
                trade["exits"].append((m["id"], s))
    return trades


# ---------------------------------------------------------------------------
# Golden fixture
# ---------------------------------------------------------------------------


def test_fixture_matches_golden_labels():
    expected = json.loads((FIXTURES / "ttt_expected.json").read_text())
    replayed = replay(load_messages())
    assert len(replayed) == 113 == len(expected)
    diffs = []
    for (m, signals), exp in zip(replayed, expected, strict=True):
        assert m["id"] == exp["i"]
        got = _label(signals)
        want = {k: exp[k] for k in ("label", "contract", "price")}
        if got != want:
            diffs.append(f"{m['id']} {m['text']!r}\n   want {want}\n   got  {got}")
    assert not diffs, "\n".join(diffs)


ROLLED_AWAY = ("MU", date(2026, 9, 4), 1015.0, "C", 0.51)


def test_entries_match_trades_csv():
    btos = [s for _, signals in replay(load_messages()) for s in signals if s.action == "BTO"]
    assert len(btos) == 26
    kept = [s for s in btos if (s.ticker, s.expiry, s.strike, s.right, s.price) != ROLLED_AWAY]
    assert len(kept) == 25
    got = [(s.ticker, s.expiry, s.strike, s.right, s.price) for s in kept]
    want = [
        (t["sym"], date.fromisoformat(t["expiry"]), float(t["strike"]), t["cp"], float(t["entry"]))
        for t in load_trades()
    ]
    assert got == want


# trades.csv n → (message id of the first leg, message id of the "rest" leg or None).
LEG_MESSAGES = {
    1: ("1480976800648528055", "1481007366316228660"),
    2: ("1483819835870675126", None),
    3: ("1485677010876633299", "1485693147299319829"),
    4: ("1486033650607853801", "1486068018021793912"),
    5: ("1489281861464358963", "1489281861464358963"),
    6: ("1491067603156733952", "1491070621411573822"),
    7: ("1493979335562887278", "1494047994952028282"),
    8: ("1495802886725046354", "1495802886725046354"),
    9: ("1502365446161305611", "1502384122226540624"),
    10: ("1504527137074315284", "1504566624139608105"),
    11: ("1507012657147023512", "1507018935936094239"),
    12: ("1513581703946572076", "1513597079212134791"),
    13: ("1516797177102598224", "1516797911680421889"),
    14: ("1524132351716888618", "1524486725710254180"),
    15: ("1529576286421516329", "1530302224343760990"),
    16: ("1538944515480485958", "1538948608051773462"),
    17: (None, None),
    18: ("1542611655974719511", None),
    19: ("1544703731604529192", "1544720421092589650"),
    20: ("1545517283001106573", "1545522767963758664"),
    21: ("1546901614621626438", "1546923344379514940"),
    22: ("1546972156817182820", "1546972156817182820"),
    23: ("1554168118971080707", "1554514044399124501"),
    24: ("1557096363517288568", "1557096363517288568"),
    25: ("1562174880596893757", None),
}


def _legs(trade: dict) -> list[tuple[str, str]]:
    return [tuple(leg.split(":")) for leg in trade["legs"].split(";") if leg]


def _csv_trades_with_signals():
    trades = _trades_from(replay(load_messages()))
    kept = [
        t
        for t in trades
        if (
            t["entry"].ticker,
            t["entry"].expiry,
            t["entry"].strike,
            t["entry"].right,
            t["entry"].price,
        )
        != ROLLED_AWAY
    ]
    return list(zip(load_trades(), kept, strict=True))


def test_first_exit_per_trade_matches_first_leg():
    for row, trade in _csv_trades_with_signals():
        n = int(row["n"])
        first_id, _ = LEG_MESSAGES[n]
        legs = _legs(row)
        if first_id is None:
            assert not legs and not trade["exits"], f"trade {n}: unexpected exit"
            continue
        assert trade["exits"], f"trade {n}: no exit found"
        msg_id, sig = trade["exits"][0]
        assert msg_id == first_id, f"trade {n}: first EXIT came from {msg_id}, want {first_id}"
        assert sig.contract == trade["entry"].contract
        leg_price = legs[0][0]
        if leg_price != "?":
            assert sig.price == float(leg_price), f"trade {n}"


def test_every_rest_exit_is_exit():
    for row, trade in _csv_trades_with_signals():
        n = int(row["n"])
        _, rest_id = LEG_MESSAGES[n]
        legs = _legs(row)
        if rest_id is None:
            assert not any(kind == "rest" for _, kind in legs)
            continue
        rest = [(mid, s) for mid, s in trade["exits"] if mid == rest_id]
        assert len(rest) == 1, f"trade {n}: rest message {rest_id} did not classify EXIT"
        sig = rest[0][1]
        assert sig.action == "STC" and sig.contract == trade["entry"].contract
        leg_price = legs[-1][0]
        if sig.price is not None and leg_price != "?":
            assert sig.price == float(leg_price), f"trade {n}"


@pytest.mark.parametrize(
    "text",
    [
        "im 0 or hero on this",
        "eyeing this gap fill",
        "yes 0dte",
        "I want a monster dump",
        "4 hour",
        "30m wedge",
        "stop at entry",
        "putting my stop @ .20 moving way too slow",
        "May cut if 587 holds",
        "Eyeing 390p for Friday if we can get the breakdown",
        "",
    ],
)
def test_chatter_is_none(text):
    assert parse_entry(text, date(2026, 3, 24)) is None
    assert classify_exit(text) is None


def test_other_author_yields_nothing():
    replayed = replay(load_messages(), author="Edtrader")
    assert sum(len(signals) for _, signals in replayed) == 0


# ---------------------------------------------------------------------------
# Unit behaviour
# ---------------------------------------------------------------------------

T0 = datetime(2026, 9, 4, 18, 43, tzinfo=timezone.utc)  # 14:43 ET


def _book() -> LottoBook:
    return LottoBook(author=AUTHOR)


def test_roll_emits_close_then_open():
    book = _book()
    opened = book.apply("m1", AUTHOR, T0, "MU 9/04 1015c @ .51 one more lotto for the road", None)
    assert [s.action for s in opened] == ["BTO"]
    rolled = book.apply(
        "m2", AUTHOR, T0, "rolled these to 1010.. cheaper @ .44  | 0 or hero still", None
    )
    assert [(s.signal_id, s.action, s.strike, s.price) for s in rolled] == [
        ("m2:0", "STC", 1015.0, None),
        ("m2:1", "BTO", 1010.0, 0.44),
    ]
    assert rolled[0].ref_price == 0.51  # F2: no posted exit price → last posted price
    assert (
        rolled[1].ticker == "MU" and rolled[1].expiry == date(2026, 9, 4) and rolled[1].right == "C"
    )
    taken = book.apply("m3", AUTHOR, T0, "1.05 over 100%  took some", None)
    assert [(s.action, s.strike, s.price, s.ref_price) for s in taken] == [
        ("STC", 1010.0, 1.05, 1.05)
    ]


def test_comma_strike_and_0dte():
    e = parse_entry("BTO GOOGL 4/20 342,5c @ .25 risky lotto", date(2026, 4, 20))
    assert (e.ticker, e.expiry, e.strike, e.right, e.price) == (
        "GOOGL",
        date(2026, 4, 20),
        342.5,
        "C",
        0.25,
    )
    e = parse_entry("USO 0dte 130p @ .75", date(2026, 4, 2))
    assert (e.ticker, e.expiry, e.strike, e.right, e.price) == (
        "USO",
        date(2026, 4, 2),
        130.0,
        "P",
        0.75,
    )
    e = parse_entry("AMD  0dte 445p @ .76 grabbing a cheap lotto", date(2026, 8, 24))
    assert (e.ticker, e.expiry, e.strike, e.right) == ("AMD", date(2026, 8, 24), 445.0, "P")
    assert occ_key("GOOGL", date(2026, 4, 20), 342.5, "C") == "GOOGL260420C00342500"


def test_expired_contract_pruned():
    book = _book()
    assert book.apply("m1", AUTHOR, T0, "SPY 9/4 650p @ .70", None)
    next_day = datetime(2026, 9, 5, 14, 0, tzinfo=timezone.utc)
    assert book.apply("m2", AUTHOR, next_day, "dumped the rest at .20", None) == []


def test_exit_cue_prices():
    assert classify_exit("2..15 taking some quickies").price == 2.15
    assert classify_exit("Cut the last @ 2.40").price == 2.40
    assert classify_exit("filled some at 17.00").price == 17.0
    assert classify_exit("Sold more @ 4.25, holding the rest, SL @ entry price").price == 4.25
    cue = classify_exit("stop hit @ entry")
    assert cue.price is None and cue.at_entry
    assert classify_exit("stop hit on the remainder").price is None


def test_entry_on_open_contract_needs_add():
    book = _book()
    assert book.apply("m1", AUTHOR, T0, "MU 9/9 1050c @ 1.90", None)
    assert book.apply("m2", AUTHOR, T0, "MU 9/9 1050c @ 1.70", None) == []
    added = book.apply("m3", AUTHOR, T0, "MU 9/9 1050c @ 1.70 adding here", None)
    assert [s.action for s in added] == ["BTO"]
    assert book.apply("m4", AUTHOR, T0, "1.95 taking some", None)[0].strike == 1050.0


def test_reply_attributes_through_parent():
    book = _book()
    book.apply("m1", AUTHOR, T0, "MU 9/9 1050c @ 5.38 RISKY", None)
    book.apply("m2", AUTHOR, T0, "SPY 9/4 650p @ .70", None)
    half = book.apply("m3", AUTHOR, T0, "7.80 half out now", "m1")
    rest = book.apply("m4", AUTHOR, T0, "stop hit on the remainder", "m3")
    assert half[0].ticker == "MU" and rest[0].ticker == "MU"
    assert rest[0].ref_price == 7.80
    # Not a reply → most recently opened contract.
    assert book.apply("m5", AUTHOR, T0, ".40 cut the last", None)[0].ticker == "SPY"


def test_exit_with_no_open_contract_is_none():
    assert _book().apply("m1", AUTHOR, T0, "2.35 I'm out", None) == []


# ---------------------------------------------------------------------------
# Forward-behaviour guards (adversarial review of phase 1)
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "text",
    [
        "added more @ 1.90",
        "adding here at 1.85",
        "will add more at 1.50",
        "1.90 added more",
        "target at 4.00",
        "MU at 1000.50 now, support",
        "2.00 stop",
        "1.40 is the target",
    ],
)
def test_add_intent_and_levels_veto_price_exit(text):
    assert classify_exit(text) is None


@pytest.mark.parametrize(
    "text,price", [("1.44 ", 1.44), ("3.00 now", 3.0), ("8.12 lol keeping one", 8.12)]
)
def test_bare_lead_price_is_exit(text, price):
    assert classify_exit(text).price == price


@pytest.mark.parametrize(
    "text",
    [
        "stop loss @ 2.00",
        "stop loss at 2.00",
        "setting stop loss at 2.00",
        "stops at 1.50",
        "SL to 1.50",
        "stop loss to entry",
    ],
)
def test_stop_placement_variants_are_none(text):
    assert classify_exit(text) is None


def test_stop_hit_variants_are_exit():
    assert classify_exit("Stop hit @ 2.00").price == 2.0
    assert classify_exit("stop loss hit").price is None
    assert classify_exit("SL hit on the rest @ 1.90").price == 1.9


def test_implausible_exit_price_is_none():
    book = _book()
    book.apply("m1", AUTHOR, T0, "SPY 9/4 681p @ .70", None)
    assert book.apply("m2", AUTHOR, T0, "taking some at 681.50", None) == []
    assert book.apply("m3", AUTHOR, T0, "taking some at .01", None) == []
    assert book.apply("m4", AUTHOR, T0, ".85 taking some", None)[0].price == 0.85


def test_reply_to_pruned_parent_is_none():
    book = _book()
    book.apply("m1", AUTHOR, T0, "SPY 9/4 650p @ .70", None)
    book.apply("m2", AUTHOR, T0, ".90 taking some", None)
    next_day = datetime(2026, 9, 5, 14, 0, tzinfo=timezone.utc)
    assert book.apply("m3", AUTHOR, next_day, "QQQ 9/10 500p @ 1.00", None)
    # Parent m2 was attributed to the SPY contract, now pruned → never fall back to QQQ.
    assert book.apply("m4", AUTHOR, next_day, "stop hit on the rest", "m2") == []


def test_ticker_named_exit_attributes_by_ticker(caplog):
    book = _book()
    book.apply("m1", AUTHOR, T0, "MU 9/9 1050c @ 5.38", None)
    book.apply("m2", AUTHOR, T0, "SPY 9/4 650p @ .70", None)
    assert book.apply("m3", AUTHOR, T0, "MU stopped out", None)[0].ticker == "MU"
    assert book.apply("m4", AUTHOR, T0, "SPY and MU both stopped out", None) == []
    with caplog.at_level("WARNING", logger="ohmytradeagent_sidecar.lotto_parser"):
        assert book.apply("m5", AUTHOR, T0, "dumped the rest", None)[0].ticker == "SPY"
    assert any("fallback" in r.message for r in caplog.records)


def test_exit_followed_by_plan_is_exit():
    assert classify_exit("took some off, will add back lower").price is None
    assert classify_exit("sold most, hoping the rest runs") is not None
    assert classify_exit("1.50 taking some, will add back lower").price == 1.5


@pytest.mark.parametrize("text", ["trimmed some here", "scaled out of half", "scaling out here"])
def test_more_exit_verbs(text):
    assert classify_exit(text) is not None


def test_roll_variants_and_date_like_strike():
    book = _book()
    book.apply("m1", AUTHOR, T0, "MU 9/04 1015c @ .51", None)
    assert [s.action for s in book.apply("m2", AUTHOR, T0, "rolling to 1010 @ .44", None)] == [
        "STC",
        "BTO",
    ]
    assert [s.action for s in book.apply("m3", AUTHOR, T0, "roll to 1005 @ .40", None)] == [
        "STC",
        "BTO",
    ]
    # "10/23" is a date, not a strike: unparseable roll → NONE, nothing opened.
    assert book.apply("m4", AUTHOR, T0, "rolled these to 10/23 @ 1.20", None) == []
    assert book.apply("m5", AUTHOR, T0, ".60 taking some", None)[0].strike == 1005.0


def test_entry_recap_on_closed_contract_is_none():
    book = _book()
    book.apply("m1", AUTHOR, T0, "SPY 9/4 681p @ .70", None)
    book.apply("m2", AUTHOR, T0, "1.40 sold half", None)
    assert book.apply("m3", AUTHOR, T0, "SPY 9/4 681p @ 1.40 now", None) == []
    assert book.apply("m4", AUTHOR, T0, "SPY 9/4 681p @ 1.20 currently", None) == []
    assert [
        s.action for s in book.apply("m5", AUTHOR, T0, "SPY 9/4 681p @ .60 trying this again", None)
    ] == ["BTO"]


def test_et_date_boundary():
    # 00:30Z on 9/5 is 20:30 ET on 9/4: the 9/4 contract is still in the book and 0dte = 9/4.
    book = _book()
    book.apply("m1", AUTHOR, T0, "SPY 9/4 650p @ .70", None)
    late = datetime(2026, 9, 5, 0, 30, tzinfo=timezone.utc)
    assert book.apply("m2", AUTHOR, late, "dumped the rest at .20", None)[0].ticker == "SPY"
    zero = book.apply("m3", AUTHOR, late, "QQQ 0dte 500p @ .50", None)
    assert zero[0].expiry == date(2026, 9, 4)
