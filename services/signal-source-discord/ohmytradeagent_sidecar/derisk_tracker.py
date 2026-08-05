"""Per-author recent-BTO tracker for de-risk-cue attribution.

PLAN-2026-08-04-copytrade-derisk-followup-cue. A de-risk cue ("0 or hero" /
"use your own stop") arrives in a separate message with no BTO grammar, so it
must be attributed to the same author's *preceding* BTO. This tracker records
each parsed BTO keyed by its author and resolves the target on demand.

Pure in-memory state, no I/O — unit-tested in ``tests/test_derisk_tracker.py``.
The window/cap bound memory and prevent attaching a cue to a stale entry from
hours earlier. Attribution is ALWAYS scoped to the cue author's own positions
(never another author's, never outside the window).
"""

from __future__ import annotations

from collections import defaultdict, deque
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Iterable


@dataclass(frozen=True)
class RecentBto:
    """A BTO the tracker may attribute a later de-risk cue to."""

    ticker: str
    expiry: date
    strike: float
    right: str
    price: float
    signal_id: str
    posted_at: datetime


class RecentBtoTracker:
    """Bounded, per-author history of recent BTOs, with attribution resolve."""

    def __init__(self, *, window_secs: float = 3600.0, per_author_cap: int = 20) -> None:
        if window_secs <= 0:
            raise ValueError("window_secs must be positive")
        if per_author_cap <= 0:
            raise ValueError("per_author_cap must be positive")
        self._window = timedelta(seconds=window_secs)
        self._cap = per_author_cap
        self._by_author: dict[str, deque[RecentBto]] = defaultdict(
            lambda: deque(maxlen=per_author_cap)
        )

    def record(self, author: str, bto: RecentBto) -> None:
        """Record a BTO under its author (oldest entries evicted past the cap)."""
        self._by_author[author].append(bto)

    def resolve(self, author: str, tickers: Iterable[str], now: datetime) -> RecentBto | None:
        """Resolve the de-risk target for ``author``'s cue.

        - ticker(s) named → the most-recent in-window BTO whose ticker matches;
          a named-but-unheld ticker returns None (never fall back to a different
          lot).
        - no ticker named ("on these") → the most-recent in-window BTO.
        - no in-window history for the author → None.
        """
        entries = self._by_author.get(author)
        if not entries:
            return None
        cutoff = now - self._window
        in_window = [b for b in entries if b.posted_at >= cutoff]
        if not in_window:
            return None

        wanted = {t.upper() for t in tickers}
        if wanted:
            matches = [b for b in in_window if b.ticker.upper() in wanted]
            if not matches:
                return None
            return max(matches, key=lambda b: b.posted_at)
        return max(in_window, key=lambda b: b.posted_at)
