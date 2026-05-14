"""Parse Discord trade-alert messages into structured signals.

Grammar (per-line, case-insensitive):
    (BTO|STC|AVG) TICKER M/D[/YY] STRIKE(C|P) [@] PRICE [tail]

The tail is captured verbatim. Partial-exit fraction mapping happens
downstream in the Go strategy (TOML-driven keyword table).

AVG messages are retrospective in the observed channel (author posts the new
average after the fill). They are parsed and returned so the caller can decide
to skip or act; the Go strategy skips by default via `skip_avg = true`.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date

_LINE_RE = re.compile(
    r"""
    ^\s*
    (?P<action>BTO|STC|AVG)\s+
    (?P<ticker>[A-Z]{1,6})\s+
    (?P<expiry>\d{1,2}/\d{1,2}(?:/\d{2,4})?)\s+
    (?P<strike>\d+(?:\.\d+)?)
    (?P<right>[CP])
    \s*@\s*
    (?P<price>\d*\.?\d+)
    (?P<tail>.*)
    $
    """,
    re.VERBOSE | re.IGNORECASE,
)


@dataclass(frozen=True)
class ParsedSignal:
    action: str   # BTO | STC | AVG (uppercase)
    ticker: str
    expiry: date
    strike: float
    right: str    # C | P (uppercase)
    price: float
    tail: str
    raw_line: str


def _resolve_expiry(md: str, today: date) -> date:
    parts = md.split("/")
    month = int(parts[0])
    day = int(parts[1])
    if len(parts) == 3:
        yr = int(parts[2])
        year = 2000 + yr if yr < 100 else yr
        return date(year, month, day)
    year = today.year
    candidate = date(year, month, day)
    if candidate < today:
        candidate = date(year + 1, month, day)
    return candidate


def parse_message(text: str, today: date | None = None) -> list[ParsedSignal]:
    """Parse a (potentially multi-line) Discord message body.

    Returns an empty list for pure commentary or noise.
    """
    if today is None:
        today = date.today()
    out: list[ParsedSignal] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        m = _LINE_RE.match(line)
        if not m:
            continue
        out.append(
            ParsedSignal(
                action=m.group("action").upper(),
                ticker=m.group("ticker").upper(),
                expiry=_resolve_expiry(m.group("expiry"), today),
                strike=float(m.group("strike")),
                right=m.group("right").upper(),
                price=float(m.group("price")),
                tail=m.group("tail").strip(),
                raw_line=line,
            )
        )
    return out
