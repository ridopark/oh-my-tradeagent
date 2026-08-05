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


@dataclass(frozen=True)
class DeriskCue:
    """A recognized de-risk escalation in a free-form (non-grammar) message.

    ``matched_cue`` is the normalized phrase that fired (precedence order below).
    ``tickers`` are the uppercase tokens found in the message, candidates for
    ticker-aware attribution — the caller intersects them with the same author's
    recently-opened BTOs, so a non-ticker token (e.g. "OR") that matches no held
    position is harmlessly ignored.
    """

    matched_cue: str
    tickers: tuple[str, ...]


# The "0-or-hero" family + the "use your own stop" invitation. Precedence order:
# the first phrase present is the one reported. Deliberately NOT "risky" — most
# BTOs carry it, so it is far too common to be a de-risk trigger.
_DERISK_CUE_PATTERNS: list[tuple[str, "re.Pattern[str]"]] = [
    ("0 or hero", re.compile(r"\b0 or hero\b")),
    ("use your own stop", re.compile(r"\buse your own stop\b")),
    ("your own stop", re.compile(r"\byour own stop\b")),
]

# Uppercase tokens are candidate tickers. 2–6 chars avoids single-letter noise
# ("I", "A"); the caller's intersection with held BTO tickers is the real guard.
_TICKER_RE = re.compile(r"\b[A-Z]{2,6}\b")

# The cue's own words, so an ALL-CAPS cue ("ZERO OR HERO") is read as generic
# ("on these") rather than as three bogus ticker mentions. Deliberately tiny and
# cue-specific — a broad English stopword list would strip real tickers (ON, ET,
# PM, IT are all listed symbols).
_CUE_STOPWORDS = frozenset({"ZERO", "OR", "HERO", "USE", "YOUR", "OWN", "STOP"})


def _normalize_for_cue(text: str) -> str:
    """Lower-case, map the word 'zero'→'0', and collapse every non-alphanumeric
    run to a single space so 'Zero-or-Hero!' and '0 or hero' compare equal."""
    lowered = re.sub(r"\bzero\b", "0", text.lower())
    return re.sub(r"[^a-z0-9]+", " ", lowered).strip()


def classify_derisk(text: str) -> DeriskCue | None:
    """Classify a free-form message as a de-risk cue, or return None.

    Only meaningful for messages that carry NO BTO/STC/AVG grammar (the caller
    invokes this exactly when :func:`parse_message` returns empty).
    """
    if not text or not text.strip():
        return None
    norm = _normalize_for_cue(text)
    matched: str | None = None
    for phrase, pattern in _DERISK_CUE_PATTERNS:
        if pattern.search(norm):
            matched = phrase
            break
    if matched is None:
        return None
    tickers = tuple(
        t for t in dict.fromkeys(_TICKER_RE.findall(text)) if t not in _CUE_STOPWORDS
    )
    return DeriskCue(matched_cue=matched, tickers=tickers)


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
