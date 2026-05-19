#!/usr/bin/env python3
"""Phase 7 promotion-gate precondition: verify drill freshness.

Parses `docs/ops/drill-log.md` and asserts that both required drill types —
`kill-switch` and `rollback` — have a `pass` entry within the last 30 days
for the target `<provider>-live` adapter.

Phase 7 gate criteria (f) and (h) in `docs/plans/PLAN.md` require these
drills to have a passing run logged within 30 days of the promotion
decision. This script is the operator-side check, intended to run as a
hard precondition before issuing the dual-control `LivePromotionApproved`
(see `docs/ops/live-promotion-rollback.md` § Sign-off recording).

Exit codes:
  0  -- both drill types have a passing entry <= 30 days old for the
        target adapter
  1  -- one or both drill types are stale (>30 days) or missing; stderr
        names every drill type that failed the check

Usage:
    scripts/ops/check_drill_freshness.py --target-adapter alpaca-live
    scripts/ops/check_drill_freshness.py --target-adapter tradier-live \\
        --log docs/ops/drill-log.md --max-age-days 30

Refs: issue #91; docs/plans/PLAN.md Phase 7 row, criteria (f) and (h).
"""
from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import pathlib
import re
import sys
from typing import IO, Iterable


REQUIRED_DRILL_TYPES: tuple[str, ...] = ("kill-switch", "rollback")
DEFAULT_MAX_AGE_DAYS: int = 30
DEFAULT_LOG_PATH: str = "docs/ops/drill-log.md"
EXPECTED_COLUMNS: tuple[str, ...] = (
    "date",
    "drill_type",
    "tenant",
    "strategy",
    "adapter",
    "operator",
    "audit_refs",
    "result",
)


@dataclasses.dataclass(frozen=True)
class DrillEntry:
    date: dt.date
    drill_type: str
    tenant: str
    strategy: str
    adapter: str
    operator: str
    audit_refs: str
    result: str


def _split_row(line: str) -> list[str]:
    """Split a markdown table row into cell values (trimmed)."""
    # Strip a leading and trailing pipe if present, then split.
    inner = line.strip()
    if inner.startswith("|"):
        inner = inner[1:]
    if inner.endswith("|"):
        inner = inner[:-1]
    return [cell.strip() for cell in inner.split("|")]


def _is_separator_row(cells: list[str]) -> bool:
    """A markdown table separator row is all `---`/`:---:` cells."""
    return bool(cells) and all(re.fullmatch(r":?-{3,}:?", c) for c in cells)


def parse_drill_log(path: pathlib.Path) -> list[DrillEntry]:
    """Parse `drill-log.md` and return the structured entry rows.

    The log MUST contain exactly one table whose header columns match
    `EXPECTED_COLUMNS` in order. Any other markdown tables in the file
    (e.g. a "Format" reference table) are ignored — only the table with
    the canonical header is read.

    Rows whose `date` cannot be parsed as ISO-8601 are skipped silently;
    rows whose `drill_type` is not one of `REQUIRED_DRILL_TYPES` are still
    returned (the freshness check filters later). Rows where any cell is
    a template placeholder (`<...>`) are skipped — the entry template
    section in the doc itself must not satisfy the gate.
    """
    text = path.read_text(encoding="utf-8")
    entries: list[DrillEntry] = []
    lines = text.splitlines()

    in_target_table = False
    saw_separator = False

    for raw in lines:
        line = raw.rstrip()
        if not line.strip().startswith("|"):
            # Leaving any table.
            in_target_table = False
            saw_separator = False
            continue

        cells = _split_row(line)

        if not in_target_table:
            # Header detection: cells match canonical column order.
            if [c.lower() for c in cells] == list(EXPECTED_COLUMNS):
                in_target_table = True
                saw_separator = False
            continue

        # We're inside the target table.
        if not saw_separator:
            if _is_separator_row(cells):
                saw_separator = True
            # Either the separator or a malformed pre-separator row — skip.
            continue

        # Data row.
        if len(cells) != len(EXPECTED_COLUMNS):
            continue
        if any(cell.startswith("<") and cell.endswith(">") for cell in cells):
            # Template placeholder row — not a real drill entry.
            continue
        try:
            date_val = dt.date.fromisoformat(cells[0])
        except ValueError:
            continue
        entries.append(
            DrillEntry(
                date=date_val,
                drill_type=cells[1],
                tenant=cells[2],
                strategy=cells[3],
                adapter=cells[4],
                operator=cells[5],
                audit_refs=cells[6],
                result=cells[7],
            )
        )

    return entries


def _latest_pass(
    entries: Iterable[DrillEntry], drill_type: str, target_adapter: str
) -> DrillEntry | None:
    matches = [
        e
        for e in entries
        if e.drill_type == drill_type
        and e.adapter == target_adapter
        and e.result == "pass"
    ]
    if not matches:
        return None
    return max(matches, key=lambda e: e.date)


def _check_one(
    entries: list[DrillEntry],
    drill_type: str,
    target_adapter: str,
    today: dt.date,
    max_age_days: int,
) -> str | None:
    """Return None if drill_type is fresh; else a human-readable failure."""
    latest = _latest_pass(entries, drill_type, target_adapter)
    if latest is None:
        return (
            f"{drill_type}: no passing entry for adapter {target_adapter!r} found in "
            f"the drill log"
        )
    age = (today - latest.date).days
    if age > max_age_days:
        return (
            f"{drill_type}: latest passing entry for adapter {target_adapter!r} is "
            f"{age} days old (limit {max_age_days})"
        )
    return None


def run(
    argv: list[str],
    *,
    stdout: IO[str] | None = None,
    stderr: IO[str] | None = None,
    today: dt.date | None = None,
) -> int:
    stdout = stdout or sys.stdout
    stderr = stderr or sys.stderr
    today = today or dt.date.today()

    parser = argparse.ArgumentParser(
        description="Verify drill-log freshness for Phase 7 promotion gate."
    )
    parser.add_argument(
        "--target-adapter",
        required=True,
        help='Provider-live adapter to check, e.g. "alpaca-live".',
    )
    parser.add_argument(
        "--log",
        default=DEFAULT_LOG_PATH,
        help=f"Path to the drill log markdown file (default: {DEFAULT_LOG_PATH}).",
    )
    parser.add_argument(
        "--max-age-days",
        type=int,
        default=DEFAULT_MAX_AGE_DAYS,
        help=f"Freshness window in days (default: {DEFAULT_MAX_AGE_DAYS}).",
    )
    args = parser.parse_args(argv)

    log_path = pathlib.Path(args.log)
    if not log_path.exists():
        print(f"check_drill_freshness: log not found: {log_path}", file=stderr)
        return 2

    entries = parse_drill_log(log_path)

    failures: list[str] = []
    for drill_type in REQUIRED_DRILL_TYPES:
        msg = _check_one(
            entries,
            drill_type,
            args.target_adapter,
            today,
            args.max_age_days,
        )
        if msg is not None:
            failures.append(msg)

    if failures:
        for msg in failures:
            print(f"FAIL: {msg}", file=stderr)
        print(
            f"check_drill_freshness: {len(failures)} drill type(s) failed freshness "
            f"check for {args.target_adapter!r} (limit {args.max_age_days} days).",
            file=stderr,
        )
        return 1

    print(
        f"PASS: both drill types have a passing entry within the last "
        f"{args.max_age_days} days for adapter {args.target_adapter!r}.",
        file=stdout,
    )
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(run(sys.argv[1:]))
