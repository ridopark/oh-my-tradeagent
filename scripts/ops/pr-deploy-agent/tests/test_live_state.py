#!/usr/bin/env python3
"""Tests for live_state.py — uses a fake ssh executable; no network required.

Run:  python3 -m unittest discover -s scripts/ops/pr-deploy-agent/tests -v
"""
from __future__ import annotations

import importlib.util
import json
import os
import pathlib
import stat
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve()
AGENT_DIR = HERE.parent.parent
SCRIPT = AGENT_DIR / "live_state.py"

# We need to make sure the agent dir is on sys.path so live_state can find
# its sibling modules (statefile, etc.) when imported.
if str(AGENT_DIR) not in sys.path:
    sys.path.insert(0, str(AGENT_DIR))

_spec = importlib.util.spec_from_file_location("live_state", SCRIPT)
assert _spec and _spec.loader
ls = importlib.util.module_from_spec(_spec)
sys.modules["live_state"] = ls
_spec.loader.exec_module(ls)  # type: ignore[union-attr]


def _make_fake_ssh(bin_dir: pathlib.Path, responses: dict) -> None:
    """Write a fake ssh executable to bin_dir.

    `responses` maps a substring (matched against the remote command string)
    to {"stdout": str, "exit": int}.  First match wins.
    The fake also records calls to bin_dir/ssh_calls.log.
    """
    script = bin_dir / "ssh"
    fixture = bin_dir / "ssh_fixture.json"
    fixture.write_text(json.dumps(responses))
    script.write_text(
        "#!/usr/bin/env python3\n"
        "import sys, os, json, pathlib\n"
        "here = pathlib.Path(__file__).parent\n"
        "fixture = json.loads((here / 'ssh_fixture.json').read_text())\n"
        "cmd = ' '.join(sys.argv[1:])\n"
        "with open(here / 'ssh_calls.log', 'a') as f:\n"
        "    f.write(cmd + '\\n')\n"
        "for pattern, resp in fixture.items():\n"
        "    if pattern in cmd:\n"
        "        if resp.get('exit', 0) != 0:\n"
        "            sys.stderr.write(resp.get('stderr', 'error\\n'))\n"
        "            sys.exit(resp['exit'])\n"
        "        sys.stdout.write(resp.get('stdout', ''))\n"
        "        sys.exit(0)\n"
        "sys.exit(0)\n"
    )
    script.chmod(script.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)


class FakeSsh:
    """Context manager: installs a fake ssh and restores PATH on exit."""

    def __init__(self, responses: dict):
        self._responses = responses
        self._tmp: tempfile.TemporaryDirectory | None = None
        self._old_path = ""

    def __enter__(self) -> "FakeSsh":
        self._tmp = tempfile.TemporaryDirectory()
        bin_dir = pathlib.Path(self._tmp.name)
        _make_fake_ssh(bin_dir, self._responses)
        self._old_path = os.environ.get("PATH", "")
        os.environ["PATH"] = f"{bin_dir}:{self._old_path}"
        self._bin_dir = bin_dir
        return self

    def __exit__(self, *_):
        os.environ["PATH"] = self._old_path
        if self._tmp:
            self._tmp.cleanup()

    def calls(self) -> list[str]:
        log = self._bin_dir / "ssh_calls.log"
        if not log.exists():
            return []
        return [l for l in log.read_text().splitlines() if l]


_TEMPORAL_COUNT_POS = 'workflow count'         # count PositionWorkflow (no -o json)
_TEMPORAL_COUNT_SIG = 'CopytradeSignalWorkflow'  # count CopytradeSignalWorkflow
_TEMPORAL_LIST_POS = '-o json'                   # list PositionWorkflow (has -o json flag)
_PSQL_PAPER = "exec_alpaca_paper"
_PSQL_LIVE = "exec_alpaca_live"


def _base_responses(pos_count=0, sig_count=0, pos_ids=None, paper_rows="", live_rows=""):
    """Build fake ssh response map.

    Pattern-match order matters: most specific patterns first.
    The list command has both 'workflow count'... wait, no: list has 'workflow list'
    and '-o json'; count has 'workflow count'. These are distinct sub-commands.
    Use '-o json' for the list and 'workflow count' for the count commands,
    checking CopytradeSignalWorkflow before PositionWorkflow in count so the SIG
    count doesn't accidentally match 'workflow count' before the POS count does.
    The fake iterates keys in insertion order (Python 3.7+ dicts), so put the most
    specific patterns first.
    """
    ids_json = json.dumps([{"workflowId": wid} for wid in (pos_ids or [])])
    return {
        # Most specific first: list has '-o json', count does not.
        _TEMPORAL_LIST_POS: {"stdout": ids_json + "\n"},
        # CopytradeSignalWorkflow count contains its type name; check before generic 'workflow count'
        _TEMPORAL_COUNT_SIG: {"stdout": f"Total: {sig_count}\n"},
        # PositionWorkflow count: 'workflow count' without '-o json'
        _TEMPORAL_COUNT_POS: {"stdout": f"Total: {pos_count}\n"},
        _PSQL_PAPER: {"stdout": paper_rows},
        _PSQL_LIVE: {"stdout": live_rows},
    }


class TestLiveStateZero(unittest.TestCase):
    def test_all_quiet(self):
        with FakeSsh(_base_responses()) as fssh:
            result = ls.read(homelab_ssh="fake@host")
        self.assertIsInstance(result, ls.LiveState)
        self.assertEqual(result.position_workflows, 0)
        self.assertEqual(result.copytrade_signal_workflows, 0)
        self.assertEqual(result.nonterminal_paper, [])
        self.assertEqual(result.nonterminal_live, [])

    def test_no_list_call_when_count_zero(self):
        with FakeSsh(_base_responses(pos_count=0)) as fssh:
            ls.read(homelab_ssh="fake@host")
            calls = " ".join(fssh.calls())
        self.assertNotIn("workflow list", calls)


class TestLiveStateSixteen(unittest.TestCase):
    def test_sixteen_positions(self):
        ids = [f"pw-tenant-{i}" for i in range(16)]
        with FakeSsh(_base_responses(pos_count=16, pos_ids=ids)):
            result = ls.read(homelab_ssh="fake@host")
        self.assertIsInstance(result, ls.LiveState)
        self.assertEqual(result.position_workflows, 16)
        self.assertEqual(len(result.position_workflow_ids), 16)
        self.assertIn("pw-tenant-0", result.position_workflow_ids)

    def test_list_call_made_when_count_positive(self):
        with FakeSsh(_base_responses(pos_count=2, pos_ids=["pw-a", "pw-b"])) as fssh:
            ls.read(homelab_ssh="fake@host")
            calls = " ".join(fssh.calls())
        self.assertIn("workflow list", calls)


class TestLiveStateNonterminalOrders(unittest.TestCase):
    def test_parses_paper_rows(self):
        rows = "prod_real|SPY251121P00580000|STC|SUBMITTED|2026-10-29 09:00:00+00\n"
        with FakeSsh(_base_responses(paper_rows=rows)):
            result = ls.read(homelab_ssh="fake@host")
        self.assertEqual(len(result.nonterminal_paper), 1)
        self.assertEqual(result.nonterminal_paper[0]["state"], "SUBMITTED")
        self.assertEqual(result.nonterminal_paper[0]["tenant_id"], "prod_real")

    def test_parses_live_rows(self):
        rows = "prod-kipark|AMZN251121C00200000|BTO|RECORDED|2026-10-29 10:00:00+00\n"
        with FakeSsh(_base_responses(live_rows=rows)):
            result = ls.read(homelab_ssh="fake@host")
        self.assertEqual(len(result.nonterminal_live), 1)

    def test_empty_rows(self):
        with FakeSsh(_base_responses(paper_rows="", live_rows="")):
            result = ls.read(homelab_ssh="fake@host")
        self.assertEqual(result.nonterminal_paper, [])
        self.assertEqual(result.nonterminal_live, [])


class TestLiveStateErrors(unittest.TestCase):
    def test_temporal_count_failure_returns_error(self):
        responses = {
            _TEMPORAL_LIST_POS: {"stdout": "[]"},
            _TEMPORAL_COUNT_SIG: {"stdout": "Total: 0\n"},
            _TEMPORAL_COUNT_POS: {"exit": 255, "stderr": "connection refused"},
            _PSQL_PAPER: {"stdout": ""},
            _PSQL_LIVE: {"stdout": ""},
        }
        with FakeSsh(responses):
            result = ls.read(homelab_ssh="fake@host")
        self.assertIsInstance(result, ls.LiveStateError)
        self.assertIn("PositionWorkflow", result.which)

    def test_psql_failure_returns_error(self):
        responses = {
            _TEMPORAL_LIST_POS: {"stdout": "[]"},
            _TEMPORAL_COUNT_SIG: {"stdout": "Total: 0\n"},
            _TEMPORAL_COUNT_POS: {"stdout": "Total: 0\n"},
            _PSQL_PAPER: {"exit": 1, "stderr": "psql error"},
            _PSQL_LIVE: {"stdout": ""},
        }
        with FakeSsh(responses):
            result = ls.read(homelab_ssh="fake@host")
        self.assertIsInstance(result, ls.LiveStateError)
        self.assertIn("exec_alpaca_paper", result.which)

    def test_unparsable_count_returns_error(self):
        responses = {
            _TEMPORAL_LIST_POS: {"stdout": "[]"},
            _TEMPORAL_COUNT_SIG: {"stdout": "Total: 0\n"},
            _TEMPORAL_COUNT_POS: {"stdout": "unexpected output\n"},
            _PSQL_PAPER: {"stdout": ""},
            _PSQL_LIVE: {"stdout": ""},
        }
        with FakeSsh(responses):
            result = ls.read(homelab_ssh="fake@host")
        self.assertIsInstance(result, ls.LiveStateError)
