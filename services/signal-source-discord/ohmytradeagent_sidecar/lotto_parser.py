"""Classify TradingTheTrend's #lotto-alerts posts into BTO/STC signals.

The channel has no BTO/STC grammar (``parser.py`` is not reused for that
reason): an entry is ``[BTO ]TICKER M/D[/YY]|0dte STRIKEc|p @ PRICE``, and every
later exit is free text ("1.40 100% sold half", "stop hit on the rest",
"dumping these") that names no contract. :class:`LottoBook` attributes each
exit to a contract — the quoted parent's contract for a reply, otherwise the
most recently opened contract still in the book — and emits one ``LottoSignal``
per leg with ids ``"<message_id>:<k>"``.

An exit with no posted price carries ``ref_price`` = the author's last posted
price for that contract (plan fork F2); ``price`` is only what the post said.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from datetime import date, datetime
from zoneinfo import ZoneInfo

from ohmytradeagent_sidecar.parser import _resolve_expiry

log = logging.getLogger(__name__)

AUTHOR = "TradingTheTrend"
_ET = ZoneInfo("America/New_York")

_NUM = r"\d*[.,]?\d+"
_ENTRY_RE = re.compile(
    rf"""
    ^\s*(?:(?i:BTO)\s+)?
    (?P<ticker>[A-Z]{{1,6}})\s+
    (?P<expiry>\d{{1,2}}/\d{{1,2}}(?:/\d{{2,4}})?|(?i:0dte))\s+
    (?P<strike>{_NUM})(?P<right>[cCpP])
    \s*@\s*(?P<price>{_NUM})\b
    """,
    re.VERBOSE,
)
_ROLL_RE = re.compile(rf"\brolled\b.*?\bto\s+(?P<strike>{_NUM})\b.*?@\s*(?P<price>{_NUM})\b", re.I)

# A price in an exit post: a leading decimal ("1.40 ...", ".85 ...", "2..15" typo) — the decimal
# is required so "4 hour" / "30m wedge" are not prices — or "@|at PRICE".
_PRICE = r"\d*\.\.?\d+"
_LEAD_PRICE_RE = re.compile(rf"^\s*(?P<price>{_PRICE})\b")
_AT_PRICE_RE = re.compile(rf"(?:@|\bat)\s*(?P<price>{_PRICE})\b", re.I)
_AT_ENTRY_RE = re.compile(r"(?:@|\bat)\s*entry\b", re.I)
# Stop placement ("stop at entry", "putting my stop @ .20", "SL @ 3.00") is not a fill: strip it
# before looking for a price. "stop hit @ ..." does not match because "hit" intervenes.
_STOP_PLACEMENT_RE = re.compile(r"\b(?:stop|sl)\s*(?:@|at)\s*(?:entry\b|\d*\.?\d+)?", re.I)
_EXIT_VERB_RE = re.compile(
    r"\b(?:taking|took|sold|selling|cut|cutting|dumped|dumping|closing|filled\s+some"
    r"|(?:half|all|mostly|i'?m)\s+out|stop(?:ped)?\s+hit|stopped\s+out|sl\s+hit)\b",
    re.I,
)
# Intent, not a fill: "May cut if 587 holds", "Plan to add more", "Will add more before close".
_INTENT_RE = re.compile(r"\b(?:may cut if|plan to|will add|hoping)\b", re.I)
_ADD_RE = re.compile(r"\badd(?:ing|ed)?\b", re.I)


def _num(s: str) -> float:
    return float(s.replace(",", ".").replace("..", "."))


def occ_key(ticker: str, expiry: date, strike: float, right: str) -> str:
    return f"{ticker}{expiry:%y%m%d}{right}{int(round(strike * 1000)):08d}"


@dataclass(frozen=True)
class Entry:
    ticker: str
    expiry: date
    strike: float
    right: str
    price: float


@dataclass(frozen=True)
class Roll:
    strike: float
    price: float


@dataclass(frozen=True)
class ExitCue:
    price: float | None  # as posted; None when the post names no price
    at_entry: bool = False  # "@ entry" / "at entry": the price is the entry price


@dataclass(frozen=True)
class LottoSignal:
    signal_id: str
    action: str  # BTO | STC
    ticker: str
    expiry: date
    strike: float
    right: str
    price: float | None  # posted price (BTO: always set)
    ref_price: float  # price to trade at: posted, else the author's last posted price
    text: str

    @property
    def contract(self) -> str:
        return occ_key(self.ticker, self.expiry, self.strike, self.right)


def parse_entry(text: str, posted_date: date) -> Entry | None:
    m = _ENTRY_RE.match(text)
    if not m:
        return None
    expiry = m.group("expiry")
    return Entry(
        ticker=m.group("ticker"),
        expiry=posted_date if expiry.lower() == "0dte" else _resolve_expiry(expiry, posted_date),
        strike=_num(m.group("strike")),
        right=m.group("right").upper(),
        price=_num(m.group("price")),
    )


def parse_roll(text: str) -> Roll | None:
    m = _ROLL_RE.search(text)
    return Roll(strike=_num(m.group("strike")), price=_num(m.group("price"))) if m else None


def classify_exit(text: str) -> ExitCue | None:
    text = _STOP_PLACEMENT_RE.sub(" ", text)
    m = _LEAD_PRICE_RE.match(text) or _AT_PRICE_RE.search(text)
    if m:
        return ExitCue(price=_num(m.group("price")))
    if _AT_ENTRY_RE.search(text):
        return ExitCue(price=None, at_entry=True)
    if _EXIT_VERB_RE.search(text) and not _INTENT_RE.search(text):
        return ExitCue(price=None)
    return None


@dataclass
class _Contract:
    ticker: str
    expiry: date
    strike: float
    right: str
    entry: float
    last_price: float
    closed: bool = False  # an exit was posted; the strategy has flattened it

    @property
    def key(self) -> str:
        return occ_key(self.ticker, self.expiry, self.strike, self.right)


@dataclass
class LottoBook:
    """Stateful attribution of one author's posts to option contracts."""

    author: str = AUTHOR
    _book: dict[str, _Contract] = field(default_factory=dict)  # insertion = open order
    _attributed: dict[str, str] = field(default_factory=dict)  # message_id -> contract key

    def apply(
        self,
        msg_id: str,
        author: str,
        posted_at: datetime,
        text: str,
        reply_to_id: str | None,
    ) -> list[LottoSignal]:
        if author != self.author:
            return []
        posted_date = posted_at.astimezone(_ET).date()
        self._prune(posted_date)

        entry = parse_entry(text, posted_date)
        if entry:
            return self._open(msg_id, entry, text, k=0)

        roll = parse_roll(text)
        if roll:
            old = self._resolve(reply_to_id)
            if old is None:
                log.info("lotto roll with nothing open: %s %r", msg_id, text)
                return []
            new = Entry(old.ticker, old.expiry, roll.strike, old.right, roll.price)
            return [
                self._close(msg_id, old, ExitCue(None), text, k=0),
                *self._open(msg_id, new, text, k=1),
            ]

        cue = classify_exit(text)
        if cue is None:
            return []
        contract = self._resolve(reply_to_id)
        if contract is None:
            log.info("lotto exit with nothing open: %s %r", msg_id, text)
            return []
        return [self._close(msg_id, contract, cue, text, k=0)]

    def _prune(self, posted_date: date) -> None:
        for key in [k for k, c in self._book.items() if c.expiry < posted_date]:
            del self._book[key]

    def _resolve(self, reply_to_id: str | None) -> _Contract | None:
        parent_key = self._attributed.get(reply_to_id) if reply_to_id else None
        if parent_key in self._book:
            return self._book[parent_key]
        return next(reversed(self._book.values()), None)

    def _open(self, msg_id: str, entry: Entry, text: str, k: int) -> list[LottoSignal]:
        key = occ_key(entry.ticker, entry.expiry, entry.strike, entry.right)
        existing = self._book.get(key)
        if existing and not existing.closed and not _ADD_RE.search(text):
            log.info("lotto entry on an open contract without 'add', ignored: %s %r", msg_id, text)
            return []
        # Re-insert so this contract becomes the most recently opened.
        self._book.pop(key, None)
        self._book[key] = _Contract(
            entry.ticker, entry.expiry, entry.strike, entry.right, entry.price, entry.price
        )
        self._attributed[msg_id] = key
        return [self._signal(msg_id, k, "BTO", self._book[key], entry.price, text)]

    def _close(self, msg_id: str, c: _Contract, cue: ExitCue, text: str, k: int) -> LottoSignal:
        price = c.entry if cue.at_entry else cue.price
        if price is not None:
            c.last_price = price
        c.closed = True
        self._attributed[msg_id] = c.key
        return self._signal(msg_id, k, "STC", c, price, text)

    @staticmethod
    def _signal(
        msg_id: str, k: int, action: str, c: _Contract, price: float | None, text: str
    ) -> LottoSignal:
        return LottoSignal(
            signal_id=f"{msg_id}:{k}",
            action=action,
            ticker=c.ticker,
            expiry=c.expiry,
            strike=c.strike,
            right=c.right,
            price=price,
            ref_price=c.last_price,
            text=text,
        )
