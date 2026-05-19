#!/usr/bin/env python3
"""Tests for `scripts/ops/check_drill_freshness.py`.

Covers the three regression cases called out in the issue acceptance
criteria (verbatim in `docs/plans/PLAN-issue-91-...md` done-when #8):

  1. both drill types fresh (<= 30 days, pass)   -> exit 0
  2. one drill type stale (> 30 days)            -> exit non-zero, names the type
  3. one drill type missing entirely             -> exit non-zero, names the type

The check is parameterised by the target `<provider>-live` adapter; entries
for other adapters in the same log are ignored.

Run standalone:
    python3 -m unittest scripts.ops.tests.test_check_drill_freshness
or via the wrapper:
    bash scripts/tests/test_check_drill_freshness.sh
"""
from __future__ import annotations

import datetime as dt
import importlib.util
import io
import pathlib
import sys
import tempfile
import textwrap
import unittest


# Load the script under test as a module without requiring an __init__.py in
# scripts/ops/. The script has a hyphen-free name so it imports cleanly.
HERE = pathlib.Path(__file__).resolve()
SCRIPT_PATH = HERE.parent.parent / "check_drill_freshness.py"
_spec = importlib.util.spec_from_file_location("check_drill_freshness", SCRIPT_PATH)
assert _spec and _spec.loader, f"could not load {SCRIPT_PATH}"
check = importlib.util.module_from_spec(_spec)
sys.modules["check_drill_freshness"] = check  # needed for @dataclass under cpython 3.13
_spec.loader.exec_module(check)  # type: ignore[union-attr]


def _log_with_entries(rows: list[dict]) -> str:
    """Render a minimal valid drill-log.md body with the given table rows.

    The header, separator, and data rows must be contiguous (no blank line
    between them) — markdown table semantics, which the parser respects.
    """
    lines = [
        "# Drill log",
        "",
        "Some preamble that the parser must skip.",
        "",
        "## Log entries",
        "",
        "| date | drill_type | tenant | strategy | adapter | operator | audit_refs | result |",
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for row in rows:
        lines.append(
            "| {date} | {drill_type} | {tenant} | {strategy} | {adapter} | {operator} | {audit_refs} | {result} |".format(
                **row
            )
        )
    lines.append("")
    return "\n".join(lines)


def _today() -> dt.date:
    return dt.date.today()


def _iso(days_ago: int) -> str:
    return (_today() - dt.timedelta(days=days_ago)).isoformat()


class ParseLogTest(unittest.TestCase):
    """Confirm the parser skips preamble and reads structured rows."""

    def test_parses_rows_ignoring_preamble_and_other_tables(self):
        body = textwrap.dedent(
            f"""\
            # Drill log

            ## Format

            | column | meaning |
            | --- | --- |
            | date | ISO-8601 date |

            ## Log entries

            | date | drill_type | tenant | strategy | adapter | operator | audit_refs | result |
            | --- | --- | --- | --- | --- | --- | --- | --- |
            | {_iso(1)} | kill-switch | dev | copytrade-v1 | alpaca-live | alice | KillSwitchResetApproved:abc | pass |
            | {_iso(2)} | rollback | dev | copytrade-v1 | alpaca-live | bob | LivePromotionApproved:xyz | pass |
            """
        )
        with tempfile.TemporaryDirectory() as td:
            p = pathlib.Path(td) / "drill-log.md"
            p.write_text(body)
            entries = check.parse_drill_log(p)
        self.assertEqual(len(entries), 2)
        self.assertEqual({e.drill_type for e in entries}, {"kill-switch", "rollback"})
        self.assertTrue(all(e.result == "pass" for e in entries))


class CheckFreshnessTest(unittest.TestCase):
    """Three regression cases mandated by done-when #8."""

    def _run(self, body: str, target: str) -> tuple[int, str, str]:
        with tempfile.TemporaryDirectory() as td:
            p = pathlib.Path(td) / "drill-log.md"
            p.write_text(body)
            out, err = io.StringIO(), io.StringIO()
            rc = check.run(
                ["--log", str(p), "--target-adapter", target],
                stdout=out,
                stderr=err,
            )
            return rc, out.getvalue(), err.getvalue()

    def test_both_fresh_exits_zero(self):
        body = _log_with_entries(
            [
                dict(
                    date=_iso(1),
                    drill_type="kill-switch",
                    tenant="dev",
                    strategy="copytrade-v1",
                    adapter="alpaca-live",
                    operator="alice",
                    audit_refs="KillSwitchResetApproved:abc",
                    result="pass",
                ),
                dict(
                    date=_iso(5),
                    drill_type="rollback",
                    tenant="dev",
                    strategy="copytrade-v1",
                    adapter="alpaca-live",
                    operator="bob",
                    audit_refs="LivePromotionApproved:xyz",
                    result="pass",
                ),
            ]
        )
        rc, _out, _err = self._run(body, "alpaca-live")
        self.assertEqual(rc, 0)

    def test_stale_kill_switch_exits_non_zero_and_names_drill(self):
        body = _log_with_entries(
            [
                dict(
                    date=_iso(45),  # stale
                    drill_type="kill-switch",
                    tenant="dev",
                    strategy="copytrade-v1",
                    adapter="alpaca-live",
                    operator="alice",
                    audit_refs="KillSwitchResetApproved:abc",
                    result="pass",
                ),
                dict(
                    date=_iso(2),
                    drill_type="rollback",
                    tenant="dev",
                    strategy="copytrade-v1",
                    adapter="alpaca-live",
                    operator="bob",
                    audit_refs="LivePromotionApproved:xyz",
                    result="pass",
                ),
            ]
        )
        rc, _out, err = self._run(body, "alpaca-live")
        self.assertNotEqual(rc, 0)
        self.assertIn("kill-switch", err)

    def test_stale_rollback_exits_non_zero_and_names_drill(self):
        body = _log_with_entries(
            [
                dict(
                    date=_iso(1),
                    drill_type="kill-switch",
                    tenant="dev",
                    strategy="copytrade-v1",
                    adapter="alpaca-live",
                    operator="alice",
                    audit_refs="KillSwitchResetApproved:abc",
                    result="pass",
                ),
                dict(
                    date=_iso(60),  # stale
                    drill_type="rollback",
                    tenant="dev",
                    strategy="copytrade-v1",
                    adapter="alpaca-live",
                    operator="bob",
                    audit_refs="LivePromotionApproved:xyz",
                    result="pass",
                ),
            ]
        )
        rc, _out, err = self._run(body, "alpaca-live")
        self.assertNotEqual(rc, 0)
        self.assertIn("rollback", err)

    def test_missing_kill_switch_exits_non_zero_and_names_drill(self):
        body = _log_with_entries(
            [
                dict(
                    date=_iso(2),
                    drill_type="rollback",
                    tenant="dev",
                    strategy="copytrade-v1",
                    adapter="alpaca-live",
                    operator="bob",
                    audit_refs="LivePromotionApproved:xyz",
                    result="pass",
                ),
            ]
        )
        rc, _out, err = self._run(body, "alpaca-live")
        self.assertNotEqual(rc, 0)
        self.assertIn("kill-switch", err)

    def test_missing_rollback_exits_non_zero_and_names_drill(self):
        body = _log_with_entries(
            [
                dict(
                    date=_iso(2),
                    drill_type="kill-switch",
                    tenant="dev",
                    strategy="copytrade-v1",
                    adapter="alpaca-live",
                    operator="alice",
                    audit_refs="KillSwitchResetApproved:abc",
                    result="pass",
                ),
            ]
        )
        rc, _out, err = self._run(body, "alpaca-live")
        self.assertNotEqual(rc, 0)
        self.assertIn("rollback", err)

    def test_fail_result_does_not_count_as_fresh(self):
        # A 'fail' entry within the window must not satisfy the gate — only
        # 'pass' entries count toward freshness.
        body = _log_with_entries(
            [
                dict(
                    date=_iso(1),
                    drill_type="kill-switch",
                    tenant="dev",
                    strategy="copytrade-v1",
                    adapter="alpaca-live",
                    operator="alice",
                    audit_refs="KillSwitchResetApproved:abc",
                    result="fail",  # fresh but failed
                ),
                dict(
                    date=_iso(2),
                    drill_type="rollback",
                    tenant="dev",
                    strategy="copytrade-v1",
                    adapter="alpaca-live",
                    operator="bob",
                    audit_refs="LivePromotionApproved:xyz",
                    result="pass",
                ),
            ]
        )
        rc, _out, err = self._run(body, "alpaca-live")
        self.assertNotEqual(rc, 0)
        self.assertIn("kill-switch", err)

    def test_other_adapter_entries_are_ignored(self):
        # An entry for `tradier-live` must NOT satisfy a check targeted at
        # `alpaca-live`.
        body = _log_with_entries(
            [
                dict(
                    date=_iso(1),
                    drill_type="kill-switch",
                    tenant="dev",
                    strategy="copytrade-v1",
                    adapter="tradier-live",  # wrong adapter
                    operator="alice",
                    audit_refs="KillSwitchResetApproved:abc",
                    result="pass",
                ),
                dict(
                    date=_iso(2),
                    drill_type="rollback",
                    tenant="dev",
                    strategy="copytrade-v1",
                    adapter="alpaca-live",
                    operator="bob",
                    audit_refs="LivePromotionApproved:xyz",
                    result="pass",
                ),
            ]
        )
        rc, _out, err = self._run(body, "alpaca-live")
        self.assertNotEqual(rc, 0)
        self.assertIn("kill-switch", err)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
