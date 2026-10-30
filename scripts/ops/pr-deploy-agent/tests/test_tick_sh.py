#!/usr/bin/env python3
"""Tests for tick.sh — lock and kill-switch behaviour; no network required.

Run:  python3 -m unittest discover -s scripts/ops/pr-deploy-agent/tests -v
"""
from __future__ import annotations

import os
import pathlib
import subprocess
import sys
import tempfile
import time
import unittest

HERE = pathlib.Path(__file__).resolve()
AGENT_DIR = HERE.parent.parent
TICK_SH = AGENT_DIR / "tick.sh"


def _run_tick(tmp_config: str, tmp_state: str, extra_env: dict | None = None) -> subprocess.CompletedProcess:
    """Run tick.sh with isolated config/state dirs."""
    env = {
        **os.environ,
        "HOME": os.environ["HOME"],  # bash needs $HOME
        "XDG_CONFIG_HOME": tmp_config,
        "XDG_STATE_HOME": tmp_state,
        # Point to a fake gh that exits 0 immediately (no gh needed for lock/kill tests)
        "PYTHONPATH": str(AGENT_DIR),
        "DISCORD_DRY_RUN": "1",
    }
    if extra_env:
        env.update(extra_env)
    return subprocess.run(
        ["bash", str(TICK_SH)],
        capture_output=True,
        text=True,
        timeout=10,
        env=env,
    )


class _FakeGhContext:
    """Install a fake gh that exits 0 with empty response for all calls."""
    def __init__(self, bin_dir_path: str):
        self._bin_dir = pathlib.Path(bin_dir_path)
        self._old_path = ""

    def __enter__(self):
        script = self._bin_dir / "gh"
        script.write_text(
            "#!/usr/bin/env python3\n"
            "import sys, json\n"
            "args = sys.argv[1:]\n"
            "joined = ' '.join(args)\n"
            "if args and args[0] == 'api':\n"
            "    if 'workflows/deploy.yml/runs' in joined:\n"
            "        print(json.dumps({'workflow_runs': []}))\n"
            "        sys.exit(0)\n"
            "    if 'commits/' in joined and '/status' in joined:\n"
            "        print(json.dumps({'statuses': []}))\n"
            "        sys.exit(0)\n"
            "    print('{}')\n"
            "    sys.exit(0)\n"
            "if args and args[0] == 'pr' and len(args) > 1 and args[1] == 'list':\n"
            "    print('[]')\n"
            "    sys.exit(0)\n"
            "sys.exit(0)\n"
        )
        script.chmod(script.stat().st_mode | 0o755)
        self._old_path = os.environ.get("PATH", "")
        os.environ["PATH"] = f"{self._bin_dir}:{self._old_path}"
        return self

    def __exit__(self, *_):
        os.environ["PATH"] = self._old_path


class TestKillSwitch(unittest.TestCase):
    def test_kill_switch_set_exits_0_and_logs(self):
        with tempfile.TemporaryDirectory() as tmp_config, \
             tempfile.TemporaryDirectory() as tmp_state, \
             tempfile.TemporaryDirectory() as tmp_bin:
            # Create DISABLED file in the config dir
            config_subdir = pathlib.Path(tmp_config) / "pr-deploy-agent"
            config_subdir.mkdir(parents=True)
            (config_subdir / "DISABLED").touch()

            with _FakeGhContext(tmp_bin):
                result = _run_tick(tmp_config, tmp_state)

        self.assertEqual(result.returncode, 0)
        self.assertIn("kill switch set", result.stderr)

    def test_no_kill_switch_does_not_log_kill_switch(self):
        with tempfile.TemporaryDirectory() as tmp_config, \
             tempfile.TemporaryDirectory() as tmp_state, \
             tempfile.TemporaryDirectory() as tmp_bin:
            with _FakeGhContext(tmp_bin):
                result = _run_tick(tmp_config, tmp_state)

        self.assertEqual(result.returncode, 0)
        self.assertNotIn("kill switch set", result.stderr)

    def test_removing_kill_switch_resumes(self):
        with tempfile.TemporaryDirectory() as tmp_config, \
             tempfile.TemporaryDirectory() as tmp_state, \
             tempfile.TemporaryDirectory() as tmp_bin:
            config_subdir = pathlib.Path(tmp_config) / "pr-deploy-agent"
            config_subdir.mkdir(parents=True)
            disabled = config_subdir / "DISABLED"
            disabled.touch()

            with _FakeGhContext(tmp_bin):
                result_with = _run_tick(tmp_config, tmp_state)
            self.assertIn("kill switch set", result_with.stderr)

            disabled.unlink()
            with _FakeGhContext(tmp_bin):
                result_without = _run_tick(tmp_config, tmp_state)
            self.assertNotIn("kill switch set", result_without.stderr)
            self.assertEqual(result_without.returncode, 0)


class TestLock(unittest.TestCase):
    def test_second_tick_exits_0_when_lock_held(self):
        """Simulate a held lock; a second tick invocation should exit 0 with a log line."""
        with tempfile.TemporaryDirectory() as tmp_config, \
             tempfile.TemporaryDirectory() as tmp_state, \
             tempfile.TemporaryDirectory() as tmp_bin:
            state_subdir = pathlib.Path(tmp_state) / "pr-deploy-agent"
            state_subdir.mkdir(parents=True)
            lock_file = state_subdir / "lock"

            # Hold the lock in a background subprocess.
            holder = subprocess.Popen(
                ["bash", "-c", f"exec 9>{lock_file!s}; flock -n 9 || exit 1; sleep 5"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            time.sleep(0.2)  # Give the holder time to acquire
            try:
                with _FakeGhContext(tmp_bin):
                    result = _run_tick(tmp_config, tmp_state)
            finally:
                holder.terminate()
                holder.communicate()  # drain pipes and reap

        self.assertEqual(result.returncode, 0)
        self.assertIn("another tick holds the lock", result.stderr)

    def test_concurrent_ticks_second_exits_fast(self):
        """The second tick should detect the lock and exit without waiting."""
        with tempfile.TemporaryDirectory() as tmp_config, \
             tempfile.TemporaryDirectory() as tmp_state, \
             tempfile.TemporaryDirectory() as tmp_bin:
            state_subdir = pathlib.Path(tmp_state) / "pr-deploy-agent"
            state_subdir.mkdir(parents=True)
            lock_file = state_subdir / "lock"

            holder = subprocess.Popen(
                ["bash", "-c", f"exec 9>{lock_file!s}; flock -n 9 || exit 1; sleep 5"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            time.sleep(0.2)
            try:
                start = time.monotonic()
                with _FakeGhContext(tmp_bin):
                    result = _run_tick(tmp_config, tmp_state)
                elapsed = time.monotonic() - start
            finally:
                holder.terminate()
                holder.communicate()  # drain pipes and reap

        self.assertEqual(result.returncode, 0)
        self.assertIn("another tick holds the lock", result.stderr)
        # Should exit almost immediately (< 2 seconds)
        self.assertLess(elapsed, 2.0, f"Expected fast exit but took {elapsed:.2f}s")


# ---------------------------------------------------------------------------
# Test: duty isolation — a failing python duty must not abort subsequent ones
# ---------------------------------------------------------------------------

class TestDutyIsolation(unittest.TestCase):
    """Finding 5: set -e in tick.sh must not cause one duty's exit 1 to abort the rest.

    Before the fix, the tick used $(python3 ... ) which propagates non-zero exits
    through set -e, aborting the remaining duties.  The fix uses || EXIT=$? so each
    duty always runs.
    """

    def test_failing_decide_does_not_abort_verify_and_plan(self):
        """If deploy_gate.py --decide exits 1, the --verify and pr_duty.py --plan duties
        must still run, and tick.sh must still exit 0.
        """
        with tempfile.TemporaryDirectory() as tmp_config, \
             tempfile.TemporaryDirectory() as tmp_state, \
             tempfile.TemporaryDirectory() as tmp_bin:

            bin_dir = pathlib.Path(tmp_bin)

            # Write a fake python3 that makes --decide exit 1 but records all invocations.
            # Use /usr/bin/python3 (not /usr/bin/env python3) in the shebang so the
            # fake does not recurse into itself when the OS resolves "python3" on PATH.
            invoke_log = bin_dir / "python3_invocations.log"
            real_python = sys.executable  # absolute path to the running interpreter
            fake_python = bin_dir / "python3"
            fake_python.write_text(
                f"#!{real_python}\n"
                "import sys, pathlib\n"
                "args = sys.argv[1:]\n"
                f"log = pathlib.Path({str(invoke_log)!r})\n"
                "with open(log, 'a') as f:\n"
                "    f.write(' '.join(args) + '\\n')\n"
                "# Make --decide fail, but --verify and --plan succeed with empty output\n"
                "if '--decide' in args:\n"
                "    sys.stderr.write('fake decide error\\n')\n"
                "    sys.exit(1)\n"
                "if '--verify' in args:\n"
                "    print('{\"verifications\": []}')\n"
                "    sys.exit(0)\n"
                "if '--plan' in args:\n"
                "    print('{\"prs\": []}')\n"
                "    sys.exit(0)\n"
                "sys.exit(0)\n"
            )
            fake_python.chmod(fake_python.stat().st_mode | 0o755)

            # Also need a fake gh on PATH (to satisfy any early path checks)
            fake_gh = bin_dir / "gh"
            fake_gh.write_text(
                "#!/bin/sh\necho '{}'\nexit 0\n"
            )
            fake_gh.chmod(fake_gh.stat().st_mode | 0o755)

            env = {
                **os.environ,
                "HOME": os.environ["HOME"],
                "XDG_CONFIG_HOME": tmp_config,
                "XDG_STATE_HOME": tmp_state,
                "PATH": f"{bin_dir}:{os.environ.get('PATH', '')}",
                "PYTHONPATH": str(AGENT_DIR),
                "DISCORD_DRY_RUN": "1",
            }
            result = subprocess.run(
                ["bash", str(TICK_SH)],
                capture_output=True, text=True, timeout=30, env=env,
            )

            # tick.sh must exit 0 even when --decide fails
            self.assertEqual(result.returncode, 0,
                             f"tick.sh must exit 0 when a duty fails; stderr={result.stderr[:300]}")

            # All three duties must have been invoked (check while tmp dir still exists)
            if invoke_log.exists():
                invocations = invoke_log.read_text().splitlines()
            else:
                invocations = []
            duty_flags = [
                any("--decide" in inv for inv in invocations),
                any("--verify" in inv for inv in invocations),
                any("--plan" in inv for inv in invocations),
            ]
            self.assertTrue(all(duty_flags),
                            f"All three duties must run; invocations={invocations}")


class TestPrDecisionLogging(unittest.TestCase):
    """Every PR decision must reach the journal, one line each (recommend mode was invisible:
    tick.sh logged only the PR duty's exit code, so a skipped PR left no trace)."""

    def test_each_pr_decision_is_logged_on_its_own_line(self):
        with tempfile.TemporaryDirectory() as tmp_config, \
             tempfile.TemporaryDirectory() as tmp_state, \
             tempfile.TemporaryDirectory() as tmp_bin:
            bin_dir = pathlib.Path(tmp_bin)
            fake_python = bin_dir / "python3"
            fake_python.write_text(
                f"#!{sys.executable}\n"
                "import sys\n"
                "args = sys.argv[1:]\n"
                "if '--decide' in args:\n"
                "    print('{\"runs\": []}')\n"
                "elif '--verify' in args:\n"
                "    print('{\"verifications\": []}')\n"
                "elif '--plan' in args:\n"
                "    print('{\"prs\": [{}, {}]}')\n"
                "    sys.stderr.write('PR #7 [READY] merge: all checks green\\n')\n"
                "    sys.stderr.write('PR #8 [NOT_REVIEWED] ping: workflow PR\\n')\n"
                "sys.exit(0)\n"
            )
            fake_python.chmod(fake_python.stat().st_mode | 0o755)
            fake_gh = bin_dir / "gh"
            fake_gh.write_text("#!/bin/sh\necho '{}'\nexit 0\n")
            fake_gh.chmod(fake_gh.stat().st_mode | 0o755)
            env = {
                **os.environ,
                "XDG_CONFIG_HOME": tmp_config,
                "XDG_STATE_HOME": tmp_state,
                "PATH": f"{bin_dir}:{os.environ.get('PATH', '')}",
                "DISCORD_DRY_RUN": "1",
            }
            result = subprocess.run(["bash", str(TICK_SH)], capture_output=True, text=True,
                                    timeout=30, env=env)
            self.assertEqual(result.returncode, 0, result.stderr[:300])
            lines = [l for l in result.stderr.splitlines() if " pr: PR #" in l]
            self.assertEqual(len(lines), 2, result.stderr)
            self.assertTrue(lines[0].endswith("pr: PR #7 [READY] merge: all checks green"), lines)
            self.assertTrue(lines[1].endswith("pr: PR #8 [NOT_REVIEWED] ping: workflow PR"), lines)
