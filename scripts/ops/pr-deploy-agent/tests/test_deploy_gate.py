#!/usr/bin/env python3
"""Tests for deploy_gate.py — uses fake gh and ssh executables; no network required.

Covers:
  - Service class table: all 13 deploy.yml services classified, none unknown
  - is_trading_path_unsafe: market-open and market-closed paths per operator decision 2026-10-29
  - decide_run: all-safe rule, unknown service, signal-watcher timing
  - Full --decide --json invocation via subprocess with fake gh/ssh on PATH
  - Recommend-mode write gate: fake gh records zero write calls

Run:  python3 -m unittest discover -s scripts/ops/pr-deploy-agent/tests -v
"""
from __future__ import annotations

import importlib.util
import json
import os
import pathlib
import stat
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime
from zoneinfo import ZoneInfo

HERE = pathlib.Path(__file__).resolve()
AGENT_DIR = HERE.parent.parent
SCRIPT = AGENT_DIR / "deploy_gate.py"

if str(AGENT_DIR) not in sys.path:
    sys.path.insert(0, str(AGENT_DIR))

# Load the module under test (and its sibling imports).
for mod in ("market_hours", "live_state", "statefile", "notify"):
    mpath = AGENT_DIR / f"{mod}.py"
    spec = importlib.util.spec_from_file_location(mod, mpath)
    assert spec and spec.loader
    m = importlib.util.module_from_spec(spec)
    sys.modules[mod] = m
    spec.loader.exec_module(m)  # type: ignore[union-attr]

_spec = importlib.util.spec_from_file_location("deploy_gate", SCRIPT)
assert _spec and _spec.loader
dg = importlib.util.module_from_spec(_spec)
sys.modules["deploy_gate"] = dg
_spec.loader.exec_module(dg)  # type: ignore[union-attr]

import live_state as _ls
import market_hours as _mh

ET = ZoneInfo("America/New_York")


def _et(year, month, day, hour, minute=0):
    return datetime(year, month, day, hour, minute, tzinfo=ET)


# ---------------------------------------------------------------------------
# Fake run / job builders
# ---------------------------------------------------------------------------

def _make_run(run_id=12345, created_at="2026-10-29T10:00:00Z"):
    return {"id": run_id, "created_at": created_at}


def _make_jobs(*service_names):
    return [
        {"name": f"Roll {svc}", "status": "waiting"}
        for svc in service_names
    ]


def _make_live(pos=0, sig=0, ids=None, paper=None, live=None):
    return _ls.LiveState(
        position_workflows=pos,
        copytrade_signal_workflows=sig,
        position_workflow_ids=ids or [],
        nonterminal_paper=paper or [],
        nonterminal_live=live or [],
    )


def _make_nonterminal_row(tenant="prod_real", symbol="SPY251121P00580000", state="SUBMITTED"):
    return {"tenant_id": tenant, "option_symbol": symbol, "side": "STC",
            "state": state, "last_state_at": "2026-10-29T10:00:00+00"}


# ---------------------------------------------------------------------------
# Helper: parse SERVICES from deploy.yml (Finding 14)
# ---------------------------------------------------------------------------

def _services_from_deploy_yml() -> set[str]:
    """Parse the SERVICES: line from .github/workflows/deploy.yml."""
    deploy_yml = AGENT_DIR.parents[2] / ".github" / "workflows" / "deploy.yml"
    text = deploy_yml.read_text()
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("SERVICES:"):
            # e.g.  SERVICES: "orchestrator exec-alpaca-paper ..."
            val = stripped[len("SERVICES:"):].strip().strip('"').strip("'")
            return set(val.split())
    raise RuntimeError("SERVICES: not found in deploy.yml")


# ---------------------------------------------------------------------------
# Test: service class table completeness
# ---------------------------------------------------------------------------

class TestServiceClassTable(unittest.TestCase):
    # Services parsed from deploy.yml rather than hard-coded (Finding 14).
    _DEPLOY_SERVICES = _services_from_deploy_yml()

    def test_all_deploy_services_in_table(self):
        for svc in self._DEPLOY_SERVICES:
            self.assertIn(
                svc, dg.SERVICE_CLASS,
                f"Service '{svc}' from deploy.yml is missing from SERVICE_CLASS table",
            )

    def test_exec_alpaca_live_not_in_table(self):
        self.assertNotIn("exec-alpaca-live", dg.SERVICE_CLASS)

    def test_trading_path_services(self):
        for svc in ("orchestrator", "exec-alpaca-paper", "market-data", "lotto-signal"):
            self.assertEqual(dg.SERVICE_CLASS[svc], "trading-path")

    def test_signal_watcher_services(self):
        for svc in ("signal-source-discord", "discord-lotto-mirror"):
            self.assertEqual(dg.SERVICE_CLASS[svc], "signal-watcher")

    def test_any_time_services(self):
        for svc in ("dashboard", "tenant-dashboard-bff", "api-gateway",
                    "discord-chat-mirror", "discord-signals-mirror",
                    "darkpool-collector", "stc-intent-service"):
            self.assertEqual(dg.SERVICE_CLASS[svc], "any-time")


# ---------------------------------------------------------------------------
# Test: is_trading_path_unsafe (THE ONE RULE)
# ---------------------------------------------------------------------------

class TestTradingPathRule(unittest.TestCase):
    def test_market_open_with_positions_unsafe(self):
        live = _make_live(pos=16, ids=["pw-1", "pw-2"])
        unsafe, reason = dg.is_trading_path_unsafe(live, market_open=True)
        self.assertTrue(unsafe)
        self.assertIn("16", reason)
        self.assertIn("PositionWorkflow", reason)

    def test_market_open_no_positions_no_orders_safe(self):
        live = _make_live(pos=0)
        unsafe, reason = dg.is_trading_path_unsafe(live, market_open=True)
        self.assertFalse(unsafe)
        self.assertEqual(reason, "")

    def test_market_open_with_nonterminal_order_unsafe(self):
        live = _make_live(pos=0, paper=[_make_nonterminal_row()])
        unsafe, reason = dg.is_trading_path_unsafe(live, market_open=True)
        self.assertTrue(unsafe)
        self.assertIn("non-terminal", reason)

    def test_market_closed_with_positions_safe_if_no_orders(self):
        """Key operator decision: overnight positions do NOT block rolling."""
        live = _make_live(pos=16, ids=["pw-smci"])
        unsafe, reason = dg.is_trading_path_unsafe(live, market_open=False)
        self.assertFalse(unsafe, f"Expected safe but got: {reason}")
        self.assertEqual(reason, "")

    def test_market_closed_with_nonterminal_order_unsafe(self):
        live = _make_live(pos=0, paper=[_make_nonterminal_row()])
        unsafe, reason = dg.is_trading_path_unsafe(live, market_open=False)
        self.assertTrue(unsafe)
        self.assertIn("non-terminal", reason)

    def test_market_closed_with_copytrade_signal_no_orders_safe(self):
        """CopytradeSignalWorkflows do not block on market-closed path."""
        live = _make_live(pos=0, sig=2)
        unsafe, reason = dg.is_trading_path_unsafe(live, market_open=False)
        self.assertFalse(unsafe)

    def test_market_open_with_copytrade_signal_unsafe(self):
        live = _make_live(pos=0, sig=2)
        unsafe, reason = dg.is_trading_path_unsafe(live, market_open=True)
        self.assertTrue(unsafe)
        self.assertIn("CopytradeSignalWorkflow", reason)

    def test_live_state_error_always_unsafe(self):
        err = _ls.LiveStateError(which="psql exec_alpaca_paper: connection refused")
        unsafe_open, reason_open = dg.is_trading_path_unsafe(err, market_open=True)
        unsafe_closed, reason_closed = dg.is_trading_path_unsafe(err, market_open=False)
        self.assertTrue(unsafe_open)
        self.assertTrue(unsafe_closed)
        self.assertIn("live-state read failed", reason_open)
        self.assertIn("live-state read failed", reason_closed)

    def test_workflow_ids_in_reason(self):
        ids = [f"pw-tenant-{i}" for i in range(3)]
        live = _make_live(pos=3, ids=ids)
        _, reason = dg.is_trading_path_unsafe(live, market_open=True)
        for wid in ids:
            self.assertIn(wid, reason)


# ---------------------------------------------------------------------------
# Test: decide_run
# ---------------------------------------------------------------------------

class TestDecideRun(unittest.TestCase):
    def test_dashboard_any_time_safe(self):
        run = _make_run()
        jobs = _make_jobs("dashboard")
        d = dg.decide_run(run, jobs, live=None, at=_et(2026, 10, 27, 10, 0))
        self.assertEqual(d.verdict, "safe")
        self.assertEqual(d.reasons, [])

    def test_signal_source_discord_market_open_unsafe(self):
        run = _make_run()
        jobs = _make_jobs("signal-source-discord")
        d = dg.decide_run(run, jobs, live=None, at=_et(2026, 10, 27, 10, 0))  # Tue 10:00 ET
        self.assertEqual(d.verdict, "unsafe")
        self.assertTrue(any("market open" in r for r in d.reasons))

    def test_signal_source_discord_saturday_safe(self):
        run = _make_run()
        jobs = _make_jobs("signal-source-discord")
        d = dg.decide_run(run, jobs, live=None, at=_et(2026, 10, 31, 10, 0))  # Saturday
        self.assertEqual(d.verdict, "safe")

    def test_orchestrator_market_open_positions_unsafe(self):
        run = _make_run()
        jobs = _make_jobs("orchestrator")
        live = _make_live(pos=16, ids=["pw-smci"])
        # Tue 10:00 ET — market open
        d = dg.decide_run(run, jobs, live=live, at=_et(2026, 10, 27, 10, 0))
        self.assertEqual(d.verdict, "unsafe")
        self.assertTrue(any("PositionWorkflow" in r for r in d.reasons))
        # Verify the workflow ID is in the reason
        self.assertTrue(any("pw-smci" in r for r in d.reasons))

    def test_orchestrator_market_closed_positions_safe_no_orders(self):
        """Tue 20:00 ET (market closed): 16 positions but no non-terminal orders → SAFE."""
        run = _make_run()
        jobs = _make_jobs("orchestrator")
        live = _make_live(pos=16, ids=["pw-smci"])
        d = dg.decide_run(run, jobs, live=live, at=_et(2026, 10, 27, 20, 0))  # Tue 20:00 ET
        self.assertEqual(d.verdict, "safe")
        self.assertEqual(d.reasons, [])

    def test_orchestrator_market_closed_with_order_unsafe(self):
        """Tue 20:00 ET (market closed): non-terminal order → UNSAFE."""
        run = _make_run()
        jobs = _make_jobs("orchestrator")
        live = _make_live(pos=0, paper=[_make_nonterminal_row()])
        d = dg.decide_run(run, jobs, live=live, at=_et(2026, 10, 27, 20, 0))  # Tue 20:00 ET
        self.assertEqual(d.verdict, "unsafe")

    def test_ssh_failure_unsafe(self):
        run = _make_run()
        jobs = _make_jobs("orchestrator")
        err = _ls.LiveStateError(which="temporal PositionWorkflow count: ssh exit 255")
        d = dg.decide_run(run, jobs, live=err, at=_et(2026, 10, 27, 10, 0))
        self.assertEqual(d.verdict, "unsafe")
        self.assertTrue(any("live-state read failed" in r for r in d.reasons))

    def test_unknown_service_unsafe(self):
        run = _make_run()
        jobs = _make_jobs("new-svc")
        d = dg.decide_run(run, jobs, live=None, at=_et(2026, 10, 27, 10, 0))
        self.assertEqual(d.verdict, "unsafe")
        self.assertTrue(any("unknown service" in r for r in d.reasons))

    def test_dashboard_and_orchestrator_market_open_positions_unsafe(self):
        """All-safe rule: one unsafe service makes the whole run unsafe."""
        run = _make_run()
        jobs = _make_jobs("dashboard", "orchestrator")
        live = _make_live(pos=3, ids=["pw-x"])
        d = dg.decide_run(run, jobs, live=live, at=_et(2026, 10, 27, 10, 0))
        self.assertEqual(d.verdict, "unsafe")

    def test_multiple_any_time_services_safe(self):
        run = _make_run()
        jobs = _make_jobs("dashboard", "tenant-dashboard-bff", "api-gateway")
        d = dg.decide_run(run, jobs, live=None, at=_et(2026, 10, 27, 10, 0))
        self.assertEqual(d.verdict, "safe")

    def test_thanksgiving_market_closed(self):
        """2026-11-26 (Thanksgiving) 11:00 ET — market closed."""
        run = _make_run()
        jobs = _make_jobs("signal-source-discord")
        # Signal-watcher is safe when market closed
        d = dg.decide_run(run, jobs, live=None, at=_et(2026, 11, 26, 11, 0))
        self.assertEqual(d.verdict, "safe")


# ---------------------------------------------------------------------------
# Test: --decide --json subprocess with fake gh/ssh, recommend mode
# ---------------------------------------------------------------------------

def _make_fake_gh(bin_dir: pathlib.Path, gh_responses: dict, allow_writes: bool = False) -> pathlib.Path:
    """Write fake gh executable that returns canned responses and records calls."""
    fixture = bin_dir / "gh_fixture.json"
    fixture.write_text(json.dumps(gh_responses))
    call_log = bin_dir / "gh_calls.log"
    script = bin_dir / "gh"
    write_subcommands = {"merge", "rerun", "create", "edit", "comment"}
    write_commands = {"pr", "run", "issue", "label"}
    script.write_text(
        "#!/usr/bin/env python3\n"
        "import sys, os, json, pathlib\n"
        "here = pathlib.Path(__file__).parent\n"
        "fixture = json.loads((here / 'gh_fixture.json').read_text())\n"
        "args = sys.argv[1:]\n"
        "# Record call\n"
        "with open(here / 'gh_calls.log', 'a') as f:\n"
        "    f.write(json.dumps(args) + '\\n')\n"
        f"allow_writes = {allow_writes!r}\n"
        f"write_commands = {write_commands!r}\n"
        f"write_subcommands = {write_subcommands!r}\n"
        "if not allow_writes:\n"
        "    if len(args) >= 2 and args[0] in write_commands and args[1] in write_subcommands:\n"
        "        sys.stderr.write('FAKE GH: write op not allowed in test\\n')\n"
        "        sys.exit(1)\n"
        "    if args and args[0] == 'api' and '-X' in args:\n"
        "        idx = args.index('-X'); method = args[idx+1] if idx+1 < len(args) else 'GET'\n"
        "        if method not in ('GET', 'HEAD'):\n"
        "            sys.stderr.write('FAKE GH: POST not allowed in recommend mode\\n')\n"
        "            sys.exit(1)\n"
        "# Find fixture by URL substring\n"
        "url = next((a for a in args if '/' in a and not a.startswith('-')), '')\n"
        "for pattern, resp in fixture.items():\n"
        "    if pattern in url or pattern in ' '.join(args):\n"
        "        if isinstance(resp, (dict, list)):\n"
        "            print(json.dumps(resp))\n"
        "        else:\n"
        "            print(resp)\n"
        "        sys.exit(0)\n"
        "# Default\n"
        "if args and args[0] == 'api':\n"
        "    print('{}')\n"
        "sys.exit(0)\n"
    )
    script.chmod(script.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
    return bin_dir


def _make_fake_ssh_for_gate(bin_dir: pathlib.Path, responses: dict) -> None:
    """Write fake ssh that records calls."""
    fixture = bin_dir / "ssh_gate_fixture.json"
    fixture.write_text(json.dumps(responses))
    script = bin_dir / "ssh"
    script.write_text(
        "#!/usr/bin/env python3\n"
        "import sys, os, json, pathlib\n"
        "here = pathlib.Path(__file__).parent\n"
        "fixture = json.loads((here / 'ssh_gate_fixture.json').read_text())\n"
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


def _run_deploy_gate(bin_dir: pathlib.Path, tmp_state: pathlib.Path,
                     extra_env: dict | None = None) -> dict:
    """Run deploy_gate.py --decide --json in a subprocess with fake gh/ssh on PATH."""
    env = {
        **os.environ,
        "PATH": f"{bin_dir}:{os.environ.get('PATH', '')}",
        "REPO": "ridopark/oh-my-tradeagent",
        "HOMELAB_SSH": "fake@host",
        "DEPLOY_MODE": "recommend",
        "STATE_DIR": str(tmp_state),
        "REPO_ROOT": str(AGENT_DIR.parents[2]),
        "DISCORD_DRY_RUN": "1",
        "PYTHONPATH": str(AGENT_DIR),
    }
    if extra_env:
        env.update(extra_env)
    result = subprocess.run(
        [sys.executable, str(SCRIPT), "--decide", "--json"],
        capture_output=True, text=True, timeout=15, env=env,
    )
    if result.returncode != 0:
        raise AssertionError(
            f"deploy_gate.py exited {result.returncode}:\n{result.stderr}"
        )
    return json.loads(result.stdout)


def _pending_env(env_id=15500297208):
    return [{"id": env_id, "current_user_can_approve": True}]


class TestDecideJson(unittest.TestCase):
    def _setup(self, services: list[str], ssh_responses: dict | None = None) -> tuple:
        """Return (bin_dir, tmp_state) TemporaryDirectory objects."""
        tmp_bin = tempfile.TemporaryDirectory()
        tmp_state = tempfile.TemporaryDirectory()
        bin_dir = pathlib.Path(tmp_bin.name)

        run_id = 99001
        runs_resp = {"workflow_runs": [_make_run(run_id, "2026-10-29T10:00:00Z")]}
        jobs_resp = {"jobs": _make_jobs(*services)}
        pending_resp = _pending_env()

        gh_fixture = {
            f"runs/{run_id}/jobs": jobs_resp,
            f"runs/{run_id}/pending_deployments": pending_resp,
            "workflows/deploy.yml/runs": runs_resp,
        }
        _make_fake_gh(bin_dir, gh_fixture)

        if ssh_responses is not None:
            _make_fake_ssh_for_gate(bin_dir, ssh_responses)

        return tmp_bin, tmp_state

    def test_no_waiting_runs(self):
        with tempfile.TemporaryDirectory() as tmp_bin, \
             tempfile.TemporaryDirectory() as tmp_state:
            bin_dir = pathlib.Path(tmp_bin)
            gh_fixture = {"workflows/deploy.yml/runs": {"workflow_runs": []}}
            _make_fake_gh(bin_dir, gh_fixture)
            out = _run_deploy_gate(bin_dir, pathlib.Path(tmp_state))
        self.assertEqual(out, {"runs": []})

    def test_dashboard_safe_no_ssh(self):
        with tempfile.TemporaryDirectory() as tmp_bin, \
             tempfile.TemporaryDirectory() as tmp_state:
            bin_dir = pathlib.Path(tmp_bin)
            run_id = 99002
            gh_fixture = {
                "workflows/deploy.yml/runs": {"workflow_runs": [_make_run(run_id)]},
                f"runs/{run_id}/jobs": {"jobs": _make_jobs("dashboard")},
                f"runs/{run_id}/pending_deployments": _pending_env(),
            }
            _make_fake_gh(bin_dir, gh_fixture)
            # No ssh fake — if ssh is called, it won't exist and will error
            out = _run_deploy_gate(bin_dir, pathlib.Path(tmp_state))
            # Check no ssh calls were made
            ssh_log = bin_dir / "ssh_calls.log"
            self.assertFalse(ssh_log.exists(), "ssh should not be called for any-time services")
        self.assertEqual(len(out["runs"]), 1)
        self.assertEqual(out["runs"][0]["verdict"], "safe")

    def test_unknown_service_unsafe(self):
        with tempfile.TemporaryDirectory() as tmp_bin, \
             tempfile.TemporaryDirectory() as tmp_state:
            bin_dir = pathlib.Path(tmp_bin)
            run_id = 99003
            gh_fixture = {
                "workflows/deploy.yml/runs": {"workflow_runs": [_make_run(run_id)]},
                f"runs/{run_id}/jobs": {"jobs": _make_jobs("new-svc")},
                f"runs/{run_id}/pending_deployments": _pending_env(),
            }
            _make_fake_gh(bin_dir, gh_fixture)
            out = _run_deploy_gate(bin_dir, pathlib.Path(tmp_state))
        self.assertEqual(out["runs"][0]["verdict"], "unsafe")
        self.assertTrue(any("unknown" in r for r in out["runs"][0]["reasons"]))

    def test_zero_gh_write_calls_in_recommend_mode(self):
        """Fake gh must record zero write operations across the whole test."""
        with tempfile.TemporaryDirectory() as tmp_bin, \
             tempfile.TemporaryDirectory() as tmp_state:
            bin_dir = pathlib.Path(tmp_bin)
            run_id = 99004
            gh_fixture = {
                "workflows/deploy.yml/runs": {"workflow_runs": [_make_run(run_id)]},
                f"runs/{run_id}/jobs": {"jobs": _make_jobs("dashboard")},
                f"runs/{run_id}/pending_deployments": _pending_env(),
            }
            _make_fake_gh(bin_dir, gh_fixture, allow_writes=False)
            # This will fail if any write attempt is made (fake gh exits 1 on writes)
            out = _run_deploy_gate(bin_dir, pathlib.Path(tmp_state))
            # Inspect call log for write ops
            call_log = bin_dir / "gh_calls.log"
            if call_log.exists():
                calls = [json.loads(line) for line in call_log.read_text().splitlines() if line]
                write_ops = {("pr", "merge"), ("run", "rerun"), ("issue", "create"),
                             ("pr", "edit"), ("pr", "comment")}
                for call in calls:
                    if len(call) >= 2:
                        self.assertNotIn(
                            (call[0], call[1]), write_ops,
                            f"Write call in recommend mode: {call}",
                        )
                    if call and call[0] == "api":
                        idx = next((i for i, a in enumerate(call) if a == "-X"), None)
                        if idx is not None and idx + 1 < len(call):
                            self.assertIn(
                                call[idx + 1], ("GET", "HEAD"),
                                f"Non-GET api call in recommend mode: {call}",
                            )

    def test_zero_services_unsafe(self):
        """Finding 3: a run whose jobs list has no 'Roll <svc>' entries → UNSAFE.

        Before this fix, decide_run returned safe with an empty services list.
        """
        with tempfile.TemporaryDirectory() as tmp_bin, \
             tempfile.TemporaryDirectory() as tmp_state:
            bin_dir = pathlib.Path(tmp_bin)
            run_id = 99010
            # Jobs are not "Roll <svc>" — they are something else entirely.
            non_roll_jobs = {"jobs": [{"name": "Setup", "status": "waiting"},
                                      {"name": "Build", "status": "waiting"}]}
            gh_fixture = {
                "workflows/deploy.yml/runs": {"workflow_runs": [_make_run(run_id)]},
                f"runs/{run_id}/jobs": non_roll_jobs,
                f"runs/{run_id}/pending_deployments": _pending_env(),
            }
            _make_fake_gh(bin_dir, gh_fixture)
            out = _run_deploy_gate(bin_dir, pathlib.Path(tmp_state))
        self.assertEqual(out["runs"][0]["verdict"], "unsafe",
                         "Expected unsafe when no Roll jobs found")
        reasons = out["runs"][0]["reasons"]
        self.assertTrue(any("no waiting Roll jobs" in r for r in reasons),
                        f"Expected 'no waiting Roll jobs' in reasons; got {reasons}")


# ---------------------------------------------------------------------------
# Tests: token guard in _verify_service (Finding 10)
# ---------------------------------------------------------------------------

class TestVerifyServiceTokenGuard(unittest.TestCase):
    """Finding 10: _verify_service must reject service names with non-[a-z0-9-] chars."""

    def test_bad_service_name_raises(self):
        """A service name like '../etc/passwd' must raise ValueError before any ssh call."""
        with self.assertRaises(ValueError) as ctx:
            dg._verify_service(
                "../etc/passwd",
                "fake@host",
                pathlib.Path("."),
            )
        self.assertIn("unsafe", str(ctx.exception).lower())

    def test_good_service_name_does_not_raise_on_token_check(self):
        """A valid service name like 'dashboard' passes the token guard (ssh will fail, not the guard)."""
        # We expect RuntimeError (ssh failure) or ValueError only for the token, not both.
        try:
            dg._verify_service("dashboard", "fake@host", pathlib.Path("."))
        except RuntimeError:
            pass  # ssh failure is expected — the token guard passed
        except ValueError as exc:
            self.fail(f"Token guard incorrectly rejected a valid name: {exc}")
