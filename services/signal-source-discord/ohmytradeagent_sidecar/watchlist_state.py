"""Once-per-ET-day mirror state.

Durable idempotency for the watchlist mirror: ``DailyMirrorState`` records the
last America/New_York calendar date on which a watchlist was mirrored, so a pod
restart on the same day does not re-emit. Temporal's REJECT_DUPLICATE keyed on
source_message_id is the hard dedupe; this file is the cheaper "did I already
post today" gate that also enforces once-per-day across changing message ids.
"""

from __future__ import annotations

import json
import os
import pathlib
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

_ET = ZoneInfo("America/New_York")


def et_today() -> str:
    """Today's America/New_York calendar date as ISO ``YYYY-MM-DD``."""
    return datetime.now(timezone.utc).astimezone(_ET).date().isoformat()


class DailyMirrorState:
    """File-backed record of the last ET date a watchlist was mirrored."""

    def __init__(self, path: pathlib.Path) -> None:
        self._path = path

    def already_mirrored_today(self, et_date: str) -> bool:
        """True iff the stored last_mirrored_date equals ``et_date``.

        A missing or corrupt file reads as "not mirrored" (fail-open to emit;
        Temporal's REJECT_DUPLICATE remains the durable backstop).
        """
        try:
            data = json.loads(self._path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return False
        return data.get("last_mirrored_date") == et_date

    def record(self, *, et_date: str, source_message_id: str) -> None:
        """Atomically persist that ``et_date`` was mirrored from a message."""
        payload = {
            "last_mirrored_date": et_date,
            "source_message_id": source_message_id,
            "mirrored_at_utc": datetime.now(timezone.utc).isoformat(),
        }
        tmp = self._path.with_suffix(self._path.suffix + ".tmp")
        tmp.write_text(json.dumps(payload), encoding="utf-8")
        os.replace(tmp, self._path)
