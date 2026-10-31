#!/usr/bin/env python3
"""Tests for launch.sh — bootstrap, fast-forward, no-op, fetch-failure,
kill-switch and lock behaviour; no network, no real GitHub.

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
LAUNCH_SH = AGENT_DIR / "launch.sh"

# Git identity env — used for every git operation in the test fixture so the
# workstation's global config and credential helpers cannot interfere.
_GIT_IDENTITY = {
    "GIT_AUTHOR_NAME": "Test",
    "GIT_AUTHOR_EMAIL": "test@test.invalid",
    "GIT_COMMITTER_NAME": "Test",
    "GIT_COMMITTER_EMAIL": "test@test.invalid",
    "GIT_CONFIG_NOSYSTEM": "1",
}

# Stub tick.sh: prints the repo's HEAD SHA to stdout so the test can
# assert which commit the tick ran at.
_STUB_TICK = """\
#!/usr/bin/env bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
echo "STUB TICK $(git -C "$REPO_ROOT" rev-parse HEAD)"
"""

# Stub notify.py: appends argv to $NOTIFY_LOG_FILE (used in Step 2 tests).
_STUB_NOTIFY = """\
#!/usr/bin/env python3
import sys, os
log = os.environ.get('NOTIFY_LOG_FILE', os.devnull)
with open(log, 'a') as f:
    f.write(' '.join(sys.argv[1:]) + '\\n')
"""


# ---------------------------------------------------------------------------
# Fixture helpers
# ---------------------------------------------------------------------------

def _git(cwd: pathlib.Path, args: list[str], env: dict) -> str:
    """Run a git command; raise on failure; return stdout stripped."""
    result = subprocess.run(
        ["git"] + args,
        cwd=str(cwd),
        env=env,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(f"git {args} in {cwd} failed:\n{result.stderr}")
    return result.stdout.strip()


class _Fixture:
    """
    Isolated test fixture.

    Creates a temp directory with:
      - origin.git  — bare git repo seeded with stub tick + notify
      - seed/       — non-bare repo used to push new commits during tests
      - agent-home/ — where the launcher stores its clone and lock
      - config/     — XDG_CONFIG_HOME (kill-switch location)
      - bin/        — fake make on PATH; records calls to make.log
    """

    def __init__(self) -> None:
        self._td = tempfile.TemporaryDirectory()
        self.tmp = pathlib.Path(self._td.name)

        # Empty gitconfig so nothing leaks from the workstation
        self.gitconfig = self.tmp / "gitconfig"
        self.gitconfig.write_text("")

        self.git_env = {
            **os.environ,
            **_GIT_IDENTITY,
            "GIT_CONFIG_GLOBAL": str(self.gitconfig),
        }

        self.agent_home = self.tmp / "agent-home"
        self.config_dir = self.tmp / "config"
        self.make_log = self.tmp / "make.log"
        self.notify_log = self.tmp / "notify.log"
        self.bin_dir = self.tmp / "bin"
        self.bin_dir.mkdir()

        self.seed: pathlib.Path | None = None
        self.origin: pathlib.Path | None = None
        self.sha_a: str = ""

    def setup_origin(self) -> None:
        """
        Create a bare origin.git with one seed commit containing stub tick.sh
        and stub notify.py, plus a .gitignore that ignores .env.
        """
        # --- seed non-bare repo ---
        self.seed = self.tmp / "seed"
        self.seed.mkdir()
        _git(self.seed, ["init"], self.git_env)
        _git(self.seed, ["symbolic-ref", "HEAD", "refs/heads/main"], self.git_env)

        # Add tree
        tick_dir = self.seed / "scripts" / "ops" / "pr-deploy-agent"
        tick_dir.mkdir(parents=True)
        (tick_dir / "tick.sh").write_text(_STUB_TICK)
        (tick_dir / "tick.sh").chmod(0o755)
        (tick_dir / "notify.py").write_text(_STUB_NOTIFY)
        (self.seed / ".gitignore").write_text(".env\n")

        _git(self.seed, ["add", "."], self.git_env)
        _git(self.seed, ["commit", "-m", "init"], self.git_env)

        # --- bare origin ---
        self.origin = self.tmp / "origin.git"
        self.origin.mkdir()
        _git(self.origin, ["init", "--bare"], self.git_env)
        _git(self.seed, ["remote", "add", "origin", str(self.origin)], self.git_env)
        _git(self.seed, ["push", "-u", "origin", "main"], self.git_env)

        self.sha_a = _git(self.seed, ["rev-parse", "HEAD"], self.git_env)

        # --- fake make: records "$PWD $@" ---
        fake_make = self.bin_dir / "make"
        fake_make.write_text(
            "#!/bin/sh\n"
            f'echo "$PWD $@" >> {self.make_log}\n'
            "exit 0\n"
        )
        fake_make.chmod(0o755)

    def launch_env(self, extra: dict | None = None) -> dict:
        """Build the environment dict for a launch.sh subprocess."""
        env = {
            **self.git_env,
            # Give bash a safe $HOME so it never reads the workstation's .bashrc
            "HOME": str(self.tmp / "home"),
            "PR_DEPLOY_AGENT_HOME": str(self.agent_home),
            "PR_DEPLOY_AGENT_CLONE_URL": str(self.origin),
            "XDG_CONFIG_HOME": str(self.config_dir),
            "PATH": f"{self.bin_dir}:{os.environ.get('PATH', '')}",
            "DISCORD_DRY_RUN": "1",
            "NOTIFY_LOG_FILE": str(self.notify_log),
        }
        if extra:
            env.update(extra)
        return env

    def run_launch(self, extra_env: dict | None = None) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["bash", str(LAUNCH_SH)],
            capture_output=True,
            text=True,
            timeout=60,
            env=self.launch_env(extra_env),
        )

    def clone_path(self) -> pathlib.Path:
        return self.agent_home / "repo"

    def add_origin_commit(self, message: str = "update") -> str:
        """Add a commit to origin via seed; return the new full SHA."""
        tick_path = self.seed / "scripts" / "ops" / "pr-deploy-agent" / "tick.sh"
        tick_path.write_text(_STUB_TICK + f"# {message}\n")
        _git(self.seed, ["add", "."], self.git_env)
        _git(self.seed, ["commit", "-m", message], self.git_env)
        _git(self.seed, ["push", "origin", "main"], self.git_env)
        return _git(self.seed, ["rev-parse", "HEAD"], self.git_env)

    def enable_kill_switch(self) -> None:
        ks_dir = self.config_dir / "pr-deploy-agent"
        ks_dir.mkdir(parents=True, exist_ok=True)
        (ks_dir / "DISABLED").touch()

    def disable_kill_switch(self) -> None:
        ks = self.config_dir / "pr-deploy-agent" / "DISABLED"
        ks.unlink(missing_ok=True)

    def cleanup(self) -> None:
        self._td.cleanup()


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class TestBootstrap(unittest.TestCase):
    """launch.sh with the clone absent must create it and run the stub tick."""

    def setUp(self) -> None:
        self.f = _Fixture()
        self.f.setup_origin()

    def tearDown(self) -> None:
        self.f.cleanup()

    def test_bootstrap_creates_clone_and_runs_tick(self) -> None:
        """Clone absent: one run creates it, calls make hooks, runs tick."""
        result = self.f.run_launch()

        self.assertEqual(result.returncode, 0, result.stderr[-500:])
        self.assertIn("bootstrapping clone", result.stderr)
        self.assertIn("cloned", result.stderr)
        self.assertIn(self.f.sha_a[:7], result.stderr)

        # fake make was called with 'hooks'
        self.assertTrue(self.f.make_log.exists(), "fake make was never called")
        make_output = self.f.make_log.read_text()
        self.assertIn("hooks", make_output)

        # clone directory exists
        self.assertTrue((self.f.clone_path() / ".git").is_dir())

        # stub tick ran and printed the correct SHA
        self.assertIn(f"STUB TICK {self.f.sha_a}", result.stdout)


class TestUpdate(unittest.TestCase):
    """launch.sh on an existing clone: fast-forward, no-op, fetch-failure."""

    def setUp(self) -> None:
        self.f = _Fixture()
        self.f.setup_origin()
        # Bootstrap the clone first
        boot = self.f.run_launch()
        self.assertEqual(boot.returncode, 0, boot.stderr[-300:])

    def tearDown(self) -> None:
        self.f.cleanup()

    def test_fast_forward_runs_new_code_same_tick(self) -> None:
        """After origin advances, the same tick that does the ff runs the new tick."""
        sha_b = self.f.add_origin_commit("v2")
        sha_b_7 = sha_b[:7]
        sha_a_7 = self.f.sha_a[:7]

        result = self.f.run_launch()

        self.assertEqual(result.returncode, 0, result.stderr[-500:])
        self.assertIn(f"clone updated {sha_a_7}→{sha_b_7}", result.stderr)

        # The stub tick printed the NEW SHA (same-tick new code)
        self.assertIn(f"STUB TICK {sha_b}", result.stdout)
        self.assertNotIn(self.f.sha_a, result.stdout)

    def test_up_to_date_is_noop(self) -> None:
        """No change in origin: logs 'up to date', HEAD unchanged, tick runs."""
        sha = self.f.sha_a
        result = self.f.run_launch()

        self.assertEqual(result.returncode, 0, result.stderr[-300:])
        self.assertIn("up to date", result.stderr)

        # Clone HEAD is still sha_a
        clone_head = subprocess.run(
            ["git", "-C", str(self.f.clone_path()), "rev-parse", "HEAD"],
            capture_output=True, text=True, env=self.f.git_env,
        ).stdout.strip()
        self.assertEqual(clone_head, sha)

        # Tick still ran
        self.assertIn(f"STUB TICK {sha}", result.stdout)

    def test_fetch_failure_runs_current_head(self) -> None:
        """Origin path gone → fetch fails, tick still runs on current HEAD."""
        # Break origin by renaming it
        broken = self.f.tmp / "origin.git.broken"
        self.f.origin.rename(broken)
        try:
            result = self.f.run_launch()
        finally:
            broken.rename(self.f.origin)

        self.assertEqual(result.returncode, 0, result.stderr[-300:])
        self.assertIn("fetch failed", result.stderr)
        # Tick ran on the existing HEAD
        self.assertIn(f"STUB TICK {self.f.sha_a}", result.stdout)


class TestKillSwitch(unittest.TestCase):
    """launch.sh with DISABLED present must not run the tick."""

    def setUp(self) -> None:
        self.f = _Fixture()
        self.f.setup_origin()

    def tearDown(self) -> None:
        self.f.cleanup()

    def test_kill_switch_blocks_tick_clone_exists(self) -> None:
        """Clone exists, DISABLED set: logs kill switch set, tick does not run."""
        # Bootstrap first (kill switch not set yet)
        boot = self.f.run_launch()
        self.assertEqual(boot.returncode, 0, boot.stderr[-200:])

        # Push a new commit so we can verify it is NOT merged
        sha_b = self.f.add_origin_commit("blocked")

        self.f.enable_kill_switch()
        result = self.f.run_launch()

        self.assertEqual(result.returncode, 0, result.stderr[-200:])
        self.assertIn("kill switch set", result.stderr)
        # Tick did not run
        self.assertNotIn("STUB TICK", result.stdout)
        # Clone still at sha_a (new commit not merged)
        clone_head = subprocess.run(
            ["git", "-C", str(self.f.clone_path()), "rev-parse", "HEAD"],
            capture_output=True, text=True, env=self.f.git_env,
        ).stdout.strip()
        self.assertEqual(clone_head, self.f.sha_a)

    def test_kill_switch_allows_bootstrap_then_blocks_tick(self) -> None:
        """DISABLED set before first run: clone IS created, tick is NOT run."""
        self.f.enable_kill_switch()
        result = self.f.run_launch()

        self.assertEqual(result.returncode, 0, result.stderr[-200:])
        # Bootstrap happened (clone exists)
        self.assertTrue((self.f.clone_path() / ".git").is_dir(),
                        "clone must be created even when kill switch is set")
        self.assertIn("kill switch set", result.stderr)
        self.assertNotIn("STUB TICK", result.stdout)


class TestLock(unittest.TestCase):
    """launch.sh with the launcher lock already held must exit fast with a log line."""

    def setUp(self) -> None:
        self.f = _Fixture()
        self.f.setup_origin()

    def tearDown(self) -> None:
        self.f.cleanup()

    def test_lock_held_exits_0_with_log(self) -> None:
        """launcher.lock held by another process: exit 0, log line, no tick."""
        self.f.agent_home.mkdir(parents=True, exist_ok=True)
        lock_file = self.f.agent_home / "launcher.lock"
        lock_file.touch()

        holder = subprocess.Popen(
            ["bash", "-c", f"exec 8>{lock_file!s}; flock -n 8 || exit 1; sleep 10"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        time.sleep(0.2)  # give holder time to acquire
        try:
            result = self.f.run_launch()
        finally:
            holder.terminate()
            holder.communicate()

        self.assertEqual(result.returncode, 0)
        self.assertIn("another tick holds the lock", result.stderr)
        self.assertNotIn("STUB TICK", result.stdout)

    def test_lock_held_exits_fast(self) -> None:
        """Second tick must detect the lock and exit in under 2 seconds."""
        self.f.agent_home.mkdir(parents=True, exist_ok=True)
        lock_file = self.f.agent_home / "launcher.lock"
        lock_file.touch()

        holder = subprocess.Popen(
            ["bash", "-c", f"exec 8>{lock_file!s}; flock -n 8 || exit 1; sleep 10"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        time.sleep(0.2)
        try:
            start = time.monotonic()
            result = self.f.run_launch()
            elapsed = time.monotonic() - start
        finally:
            holder.terminate()
            holder.communicate()

        self.assertEqual(result.returncode, 0)
        self.assertIn("another tick holds the lock", result.stderr)
        self.assertLess(elapsed, 2.0, f"expected fast exit, took {elapsed:.2f}s")
