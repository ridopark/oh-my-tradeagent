"""Detect whether a Discord message body is the daily watchlist.

The watchlist is a grid of option levels with a comparator, e.g.::

    SPY 762c > 761.00
    753p < 754.00

Each level line is ``[TICKER] STRIKE(C|P) (< | >) PRICE``; the ticker is
optional on continuation lines, which inherit the ticker above. This is a
distinct grammar from the BTO/STC copytrade messages (action verb + ``@``,
no comparator) — so the detector rejects those, and prose, by requiring at
least two comparator-shaped lines. Pure: no I/O, no state.
"""

from __future__ import annotations

import re

# Level line with an OPTIONAL leading ticker: "SPY 762c > 761.00" (ticker form)
# or "753p < 754.00" (continuation form, which inherits the ticker above).
_LEVEL_LINE_RE = re.compile(
    r"^\s*(?:[A-Z]{1,6}\s+)?\d+(?:\.\d+)?\s*[cp]\s*[<>]\s*\d+(?:\.\d+)?\s*$",
    re.IGNORECASE,
)


def is_watchlist(text: str) -> bool:
    """True iff at least two non-blank lines are watchlist-level-shaped."""
    matches = 0
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if _LEVEL_LINE_RE.match(line):
            matches += 1
            if matches >= 2:
                return True
    return False
