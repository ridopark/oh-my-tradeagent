"""OCC symbol helpers — expiry parsing and DTE calculation.

OCC symbols are padded: 'SMCI 261120C00050000'.
The YYMMDD block sits at len-15 of the space-stripped form.
"""
from __future__ import annotations

from datetime import date


def expiry_from_occ(symbol: str) -> date | None:
    """Return the expiry date from a padded OCC symbol, or None on parse failure."""
    compact = symbol.replace(" ", "")
    if len(compact) < 15:
        return None
    # YYMMDD is at len-15 (6 chars)
    start = len(compact) - 15
    ymd = compact[start : start + 6]
    try:
        yy = int(ymd[0:2])
        mm = int(ymd[2:4])
        dd = int(ymd[4:6])
        return date(2000 + yy, mm, dd)
    except (ValueError, IndexError):
        return None


def dte(symbol: str, today: date) -> int | None:
    """Calendar days from *today* to the OCC expiry, or None on parse failure."""
    expiry = expiry_from_occ(symbol)
    if expiry is None:
        return None
    return (expiry - today).days
