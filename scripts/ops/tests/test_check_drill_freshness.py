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


class FutureDatedGuardTest(unittest.TestCase):
    """Item 1 — future-dated 'pass' rows must not satisfy the freshness gate."""

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

    def test_future_dated_kill_switch_does_not_satisfy_gate(self):
        # A 'pass' row dated tomorrow must NOT be treated as fresh — the
        # negative `age` (today - future).days would otherwise sneak past
        # the `age > max_age_days` comparison.
        body = _log_with_entries(
            [
                dict(
                    date=_iso(-1),  # tomorrow
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
        self.assertEqual(rc, 1)
        self.assertIn("kill-switch", err)
        self.assertIn("future-dated", err)


class LogNotFoundTest(unittest.TestCase):
    """Item 5 — exit 2 when --log points at a non-existent path."""

    def test_missing_log_exits_two_with_stderr_message(self):
        with tempfile.TemporaryDirectory() as td:
            missing = pathlib.Path(td) / "does-not-exist.md"
            out, err = io.StringIO(), io.StringIO()
            rc = check.run(
                ["--log", str(missing), "--target-adapter", "alpaca-live"],
                stdout=out,
                stderr=err,
            )
        self.assertEqual(rc, 2)
        self.assertIn("log not found", err.getvalue())


class ParseLogStderrWarningTest(unittest.TestCase):
    """Item 6 — parse_drill_log emits stderr WARN for skipped malformed rows."""

    def test_wrong_column_count_row_emits_warn(self):
        # A row with 6 cells inside an otherwise-valid table must emit a
        # WARN line identifying the row number + reason, then be skipped.
        body = "\n".join(
            [
                "# Drill log",
                "",
                "## Log entries",
                "",
                "| date | drill_type | tenant | strategy | adapter | operator | audit_refs | result |",
                "| --- | --- | --- | --- | --- | --- | --- | --- |",
                "| {d} | kill-switch | dev | copytrade-v1 | alpaca-live | alice |".format(d=_iso(1)),
                "| {d} | rollback | dev | copytrade-v1 | alpaca-live | bob | LivePromotionApproved:xyz | pass |".format(d=_iso(2)),
                "",
            ]
        )
        with tempfile.TemporaryDirectory() as td:
            p = pathlib.Path(td) / "drill-log.md"
            p.write_text(body)
            err = io.StringIO()
            entries = check.parse_drill_log(p, stderr=err)
        stderr_text = err.getvalue()
        self.assertIn("WARN:", stderr_text)
        self.assertIn("drill-log.md", stderr_text)
        # the malformed row is the first data row (after header + separator)
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0].drill_type, "rollback")

    def test_unparseable_date_row_emits_warn(self):
        body = "\n".join(
            [
                "# Drill log",
                "",
                "## Log entries",
                "",
                "| date | drill_type | tenant | strategy | adapter | operator | audit_refs | result |",
                "| --- | --- | --- | --- | --- | --- | --- | --- |",
                "| not-a-date | kill-switch | dev | copytrade-v1 | alpaca-live | alice | KillSwitchResetApproved:abc | pass |",
                "| {d} | rollback | dev | copytrade-v1 | alpaca-live | bob | LivePromotionApproved:xyz | pass |".format(d=_iso(2)),
                "",
            ]
        )
        with tempfile.TemporaryDirectory() as td:
            p = pathlib.Path(td) / "drill-log.md"
            p.write_text(body)
            err = io.StringIO()
            entries = check.parse_drill_log(p, stderr=err)
        stderr_text = err.getvalue()
        self.assertIn("WARN:", stderr_text)
        self.assertIn("not-a-date", stderr_text)
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0].drill_type, "rollback")

    def test_template_placeholder_row_does_not_warn(self):
        # The intentional `<...>` placeholder row in the entry-template
        # section of drill-log.md must remain silent — it's documented
        # as an intentional skip.
        body = "\n".join(
            [
                "# Drill log",
                "",
                "## Log entries",
                "",
                "| date | drill_type | tenant | strategy | adapter | operator | audit_refs | result |",
                "| --- | --- | --- | --- | --- | --- | --- | --- |",
                "| <YYYY-MM-DD> | <kill-switch|rollback> | <tenant> | <strategy> | <provider>-<env> | <operator> | <event_kind:audit_id> | <pass|fail> |",
                "| {d} | rollback | dev | copytrade-v1 | alpaca-live | bob | LivePromotionApproved:xyz | pass |".format(d=_iso(2)),
                "",
            ]
        )
        with tempfile.TemporaryDirectory() as td:
            p = pathlib.Path(td) / "drill-log.md"
            p.write_text(body)
            err = io.StringIO()
            entries = check.parse_drill_log(p, stderr=err)
        self.assertNotIn("WARN:", err.getvalue())
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0].drill_type, "rollback")


class TargetAdapterFormatWarningTest(unittest.TestCase):
    """Item 7 — --target-adapter non-conforming names emit a WARN but proceed."""

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

    def test_non_conforming_adapter_emits_warn_but_does_not_fail_early(self):
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
            ]
        )
        # `foo` does not match `^[a-z0-9-]+-live$`; should WARN but not
        # change exit code from the normal content-driven result.
        rc, _out, err = self._run(body, "foo")
        self.assertIn("WARN:", err)
        self.assertIn("foo", err)
        # `foo` adapter has no entries → normal exit-1 (both drill types
        # missing), NOT an early-exit error.
        self.assertEqual(rc, 1)

    def test_well_formed_adapter_does_not_warn(self):
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
        self.assertEqual(rc, 0)
        self.assertNotIn("WARN: --target-adapter", err)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
