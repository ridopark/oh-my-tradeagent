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
            "if args and args[0] == 'api':\n"
            "    if 'workflows/deploy.yml/runs' in ' '.join(args):\n"
            "        print(json.dumps({'workflow_runs': []}))\n"
            "        sys.exit(0)\n"
            "    print('{}')\n"
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
                holder.wait()

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
                holder.wait()

        self.assertEqual(result.returncode, 0)
        self.assertIn("another tick holds the lock", result.stderr)
        # Should exit almost immediately (< 2 seconds)
        self.assertLess(elapsed, 2.0, f"Expected fast exit but took {elapsed:.2f}s")
