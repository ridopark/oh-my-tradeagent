#!/usr/bin/env python3
"""Tests for launch.sh — bootstrap, fast-forward, no-op, fetch-failure,
kill-switch, lock, refusals (dirty/diverged/not-on-main), notify CLI
dedupe, launcher drift and webhook warning; no network, no real GitHub.

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

        # git_env is used for direct git subprocess calls inside tests (not via
        # launch.sh); it inherits os.environ so git can find its own helpers.
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

    def use_real_notify(self) -> None:
        """Replace stub notify.py (and statefile.py) in the clone with the real ones."""
        clone = self.clone_path()
        tick_dir = clone / "scripts" / "ops" / "pr-deploy-agent"
        for name in ("notify.py", "statefile.py"):
            (tick_dir / name).write_text((AGENT_DIR / name).read_text())

    def launch_env(self, extra: dict | None = None) -> dict:
        """Build the environment dict for a launch.sh subprocess.

        Explicitly excludes shell-level vars (DISCORD_WEBHOOK_URL, STATE_DIR,
        XDG_STATE_HOME, XDG_DATA_HOME) so tests are not affected by the developer's
        shell environment.  Finding 5: repro was
        ``DISCORD_WEBHOOK_URL=https://x.invalid python3 -m unittest ...`` causing
        TestWebhookWarning to fail silently.
        """
        # Start from os.environ but strip vars that would interfere with
        # webhook / state-dir tests.
        _STRIP = {
            "DISCORD_WEBHOOK_URL",
            "STATE_DIR",
            "XDG_STATE_HOME",
            "XDG_DATA_HOME",
            "PR_DEPLOY_AGENT_HOME",
            "PR_DEPLOY_AGENT_CLONE_URL",
            "XDG_CONFIG_HOME",
        }
        env = {
            **{k: v for k, v in os.environ.items() if k not in _STRIP},
            **_GIT_IDENTITY,
            "GIT_CONFIG_GLOBAL": str(self.gitconfig),
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

    def push_file_to_origin(
        self,
        rel_path: str,
        content: str,
        executable: bool = False,
    ) -> str:
        """Add or replace a file in seed and push to origin; return new SHA."""
        target = self.seed / rel_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
        if executable:
            target.chmod(0o755)
        _git(self.seed, ["add", rel_path], self.git_env)
        _git(self.seed, ["commit", "-m", f"add {rel_path}"], self.git_env)
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

        # fake make was called with -C <clone> hooks  (finding 4)
        self.assertTrue(self.f.make_log.exists(), "fake make was never called")
        make_output = self.f.make_log.read_text()
        self.assertIn("hooks", make_output)
        self.assertIn("-C", make_output)
        self.assertIn(str(self.f.clone_path()), make_output)

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


# ---------------------------------------------------------------------------
# Step 2: Refusal tests (dirty / diverged / not-on-main)
# ---------------------------------------------------------------------------

class TestRefusal(unittest.TestCase):
    """Clones that are dirty, diverged, or off main must be left untouched and
    produce exactly one alert call (via the stub notify.py log)."""

    def setUp(self) -> None:
        self.f = _Fixture()
        self.f.setup_origin()
        boot = self.f.run_launch()
        self.assertEqual(boot.returncode, 0, boot.stderr[-300:])

    def tearDown(self) -> None:
        self.f.cleanup()

    def _notify_keys(self) -> list[str]:
        """Parse keys passed to stub notify.py via --once flag."""
        if not self.f.notify_log.exists():
            return []
        keys = []
        for line in self.f.notify_log.read_text().splitlines():
            parts = line.split()
            for i, p in enumerate(parts):
                if p == "--once" and i + 1 < len(parts):
                    keys.append(parts[i + 1])
        return keys

    def test_dirty_clone_untouched_and_alerted(self) -> None:
        """Tracked file modified: HEAD and contents unchanged, no tick, alert sent."""
        clone = self.f.clone_path()
        tick_path = clone / "scripts" / "ops" / "pr-deploy-agent" / "tick.sh"
        original = tick_path.read_text()
        head_before = _git(clone, ["rev-parse", "HEAD"], self.f.git_env)

        # Make clone dirty (tracked file modified, not staged)
        tick_path.write_text(original + "# dirty\n")

        # Snapshot both HEAD and porcelain BEFORE the launcher run (finding 2)
        status_before = _git(clone, ["status", "--porcelain"], self.f.git_env)

        result = self.f.run_launch()

        self.assertEqual(result.returncode, 0, result.stderr[-300:])
        self.assertIn("dirty", result.stderr)
        self.assertNotIn("STUB TICK", result.stdout)

        # Clone state is byte-for-byte unchanged — both HEAD and porcelain (finding 2)
        head_after = _git(clone, ["rev-parse", "HEAD"], self.f.git_env)
        status_after = _git(clone, ["status", "--porcelain"], self.f.git_env)
        self.assertEqual(head_before, head_after)
        self.assertEqual(status_before, status_after)
        self.assertEqual(tick_path.read_text(), original + "# dirty\n")

        # Alert was requested with the right key prefix
        keys = self._notify_keys()
        self.assertTrue(
            any(k.startswith(f"clone-refused:dirty:{head_before}") for k in keys),
            f"expected clone-refused:dirty key, got {keys}",
        )

    def test_dirty_two_runs_exactly_one_alert(self) -> None:
        """Finding 2: two runs on a dirty clone with real notify.py → exactly one alert."""
        clone = self.f.clone_path()
        self.f.use_real_notify()

        state_dir = self.f.tmp / "state-real"
        state_dir.mkdir()

        tick_path = clone / "scripts" / "ops" / "pr-deploy-agent" / "tick.sh"
        tick_path.write_text(tick_path.read_text() + "# dirty\n")

        head_before = _git(clone, ["rev-parse", "HEAD"], self.f.git_env)
        status_before = _git(clone, ["status", "--porcelain"], self.f.git_env)

        extra = {
            "STATE_DIR": str(state_dir),
            "DISCORD_DRY_RUN": "1",
            # Prevent notify.py from creating __pycache__ which would change status
            "PYTHONDONTWRITEBYTECODE": "1",
        }
        r1 = self.f.run_launch(extra)
        r2 = self.f.run_launch(extra)

        # Both runs must exit 0 and not run the tick
        self.assertEqual(r1.returncode, 0, r1.stderr[-300:])
        self.assertEqual(r2.returncode, 0, r2.stderr[-300:])
        self.assertNotIn("STUB TICK", r1.stdout)
        self.assertNotIn("STUB TICK", r2.stdout)

        # HEAD and porcelain unchanged across both runs (finding 2)
        head_after = _git(clone, ["rev-parse", "HEAD"], self.f.git_env)
        status_after = _git(clone, ["status", "--porcelain"], self.f.git_env)
        self.assertEqual(head_before, head_after)
        self.assertEqual(status_before, status_after)

        # Exactly ONE alert recorded in state.json (finding 2)
        import json as _json
        state_path = state_dir / "state.json"
        self.assertTrue(state_path.exists(), "state.json not written by real notify.py")
        state = _json.loads(state_path.read_text())
        dirty_keys = [
            k for k in state.get("notified", {})
            if k.startswith(f"clone-refused:dirty:{head_before}")
        ]
        self.assertEqual(len(dirty_keys), 1, f"expected exactly 1 dirty alert; state={state}")

    def test_untracked_is_dirty_env_is_not(self) -> None:
        """Untracked non-.env file = dirty; ignored .env does NOT count."""
        clone = self.f.clone_path()
        head_sha = _git(clone, ["rev-parse", "HEAD"], self.f.git_env)

        # Phase 1: untracked file → refuse
        (clone / "stray.txt").write_text("untracked\n")
        result1 = self.f.run_launch()
        self.assertIn("dirty", result1.stderr)
        self.assertNotIn("STUB TICK", result1.stdout)

        # Phase 2: remove untracked file, add .env (gitignored) → NOT dirty → tick runs
        (clone / "stray.txt").unlink()
        (clone / ".env").write_text("DISCORD_WEBHOOK_URL=https://fake.example\n")
        result2 = self.f.run_launch()
        self.assertNotIn("dirty", result2.stderr)
        self.assertIn(f"STUB TICK {head_sha}", result2.stdout)

    def test_diverged_untouched(self) -> None:
        """Clone has a local commit + origin advanced: local commit stays HEAD, no tick."""
        clone = self.f.clone_path()

        # Make a local commit in the clone
        (clone / "local_change.txt").write_text("local\n")
        _git(clone, ["add", "local_change.txt"], self.f.git_env)
        _git(clone, ["commit", "-m", "local commit"], self.f.git_env)
        local_head = _git(clone, ["rev-parse", "HEAD"], self.f.git_env)

        # Advance origin independently
        self.f.add_origin_commit("origin advance")

        # Snapshot porcelain before (finding 2)
        status_before = _git(clone, ["status", "--porcelain"], self.f.git_env)

        result = self.f.run_launch()

        self.assertEqual(result.returncode, 0, result.stderr[-300:])
        self.assertIn("diverged", result.stderr)
        self.assertNotIn("STUB TICK", result.stdout)

        # Clone HEAD and porcelain unchanged (finding 2)
        head_after = _git(clone, ["rev-parse", "HEAD"], self.f.git_env)
        status_after = _git(clone, ["status", "--porcelain"], self.f.git_env)
        self.assertEqual(local_head, head_after)
        self.assertEqual(status_before, status_after)

        # Alert key present
        keys = self._notify_keys()
        self.assertTrue(
            any(k.startswith(f"clone-refused:diverged:{local_head}") for k in keys),
            f"expected clone-refused:diverged key, got {keys}",
        )

    def test_not_on_main_refused(self) -> None:
        """Clone on a non-main branch: refuses, no tick, alert sent."""
        clone = self.f.clone_path()
        head_sha = _git(clone, ["rev-parse", "HEAD"], self.f.git_env)

        # Switch clone to a different branch
        _git(clone, ["checkout", "-b", "other-branch"], self.f.git_env)

        result = self.f.run_launch()

        self.assertEqual(result.returncode, 0, result.stderr[-300:])
        self.assertIn("not on main", result.stderr)
        self.assertNotIn("STUB TICK", result.stdout)

        keys = self._notify_keys()
        self.assertTrue(
            any(k.startswith(f"clone-refused:not-on-main:{head_sha}") for k in keys),
            f"expected clone-refused:not-on-main key, got {keys}",
        )


# ---------------------------------------------------------------------------
# Step 2: Notify CLI dedupe (Seam B — real notify.py, no HTTP)
# ---------------------------------------------------------------------------

class TestNotifyCli(unittest.TestCase):
    """python3 notify.py --once KEY ... deduplicates via the real statefile."""

    def test_once_dedupes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = pathlib.Path(tmp_str)
            state_dir = tmp / "state"
            state_dir.mkdir()
            real_notify = AGENT_DIR / "notify.py"
            key = "test-key:abc123"

            def run_cli() -> subprocess.CompletedProcess:
                return subprocess.run(
                    [
                        sys.executable, str(real_notify),
                        "--once", key,
                        "--title", "Test title",
                        "--body", "Test body",
                        "--state-dir", str(state_dir),
                    ],
                    capture_output=True,
                    text=True,
                    timeout=10,
                    env={**os.environ, "DISCORD_DRY_RUN": "1"},
                )

            # First call: should succeed, key recorded in state.json
            r1 = run_cli()
            self.assertEqual(r1.returncode, 0, r1.stderr)
            self.assertNotIn("already notified", r1.stdout)

            state_path = state_dir / "state.json"
            self.assertTrue(state_path.exists(), "state.json not created")
            import json as _json
            state = _json.loads(state_path.read_text())
            self.assertIn(key, state.get("notified", {}),
                          f"key not in state.json: {state}")

            # Second call: same key → already notified
            r2 = run_cli()
            self.assertEqual(r2.returncode, 0, r2.stderr)
            self.assertIn("already notified", r2.stdout)


# ---------------------------------------------------------------------------
# Step 2: Launcher drift check
# ---------------------------------------------------------------------------

class TestDrift(unittest.TestCase):
    """When clone's launch.sh differs from the running launcher: warn + tick runs."""

    def setUp(self) -> None:
        self.f = _Fixture()
        self.f.setup_origin()
        # Add an old (different) launch.sh to origin before bootstrapping
        self.f.push_file_to_origin(
            "scripts/ops/pr-deploy-agent/launch.sh",
            "#!/bin/sh\n# old launcher version\n",
            executable=True,
        )
        boot = self.f.run_launch()
        self.assertEqual(boot.returncode, 0, boot.stderr[-300:])

    def tearDown(self) -> None:
        self.f.cleanup()

    def test_drift_warns_and_runs(self) -> None:
        """Clone has a different launch.sh: drift logged, alert sent, tick still runs.

        Finding 3: assert launcher-drift: unconditionally (no if-exists guard) so
        that no-op'ing the drift alert in launch.sh causes this test to fail.
        """
        result = self.f.run_launch()

        self.assertEqual(result.returncode, 0, result.stderr[-300:])
        self.assertIn("installed launcher differs from main", result.stderr)
        # Tick still ran despite drift
        self.assertIn("STUB TICK", result.stdout)

        # Alert key starts with 'launcher-drift:' — asserted unconditionally
        # (finding 3: removing the drift alert in launch.sh must cause this to fail)
        self.assertTrue(self.f.notify_log.exists(), "notify_log not written; drift alert missing")
        logged = self.f.notify_log.read_text()
        self.assertIn("launcher-drift:", logged)

    def test_no_drift_when_matching(self) -> None:
        """Finding 3: when installed launcher matches clone's, no drift alert is sent."""
        # Push the ACTUAL launch.sh (the launcher under test) as the clone's launch.sh
        self.f.push_file_to_origin(
            "scripts/ops/pr-deploy-agent/launch.sh",
            LAUNCH_SH.read_text(),
            executable=True,
        )
        # Re-bootstrap so the clone has the matching launcher
        self.f.run_launch()

        # Clear any prior notify log entries
        if self.f.notify_log.exists():
            self.f.notify_log.unlink()

        result = self.f.run_launch()

        self.assertEqual(result.returncode, 0, result.stderr[-300:])
        self.assertNotIn("installed launcher differs from main", result.stderr)
        # notify_log must NOT contain a launcher-drift: entry
        if self.f.notify_log.exists():
            self.assertNotIn("launcher-drift:", self.f.notify_log.read_text())


# ---------------------------------------------------------------------------
# Step 2: Webhook presence warning
# ---------------------------------------------------------------------------

class TestWebhookWarning(unittest.TestCase):
    """Without a Discord webhook the launcher logs a warning each tick."""

    def setUp(self) -> None:
        self.f = _Fixture()
        self.f.setup_origin()
        boot = self.f.run_launch()
        self.assertEqual(boot.returncode, 0, boot.stderr[-300:])

    def tearDown(self) -> None:
        self.f.cleanup()

    def test_no_webhook_logs_warning(self) -> None:
        """No env var and no .env in clone → webhook warning in stderr."""
        result = self.f.run_launch()
        self.assertEqual(result.returncode, 0, result.stderr[-200:])
        self.assertIn("no Discord webhook configured", result.stderr)

    def test_with_dotenv_no_warning(self) -> None:
        """Clone .env with DISCORD_WEBHOOK_URL set → no webhook warning."""
        clone = self.f.clone_path()
        (clone / ".env").write_text("DISCORD_WEBHOOK_URL=https://fake.example\n")
        result = self.f.run_launch()
        self.assertEqual(result.returncode, 0, result.stderr[-200:])
        self.assertNotIn("no Discord webhook configured", result.stderr)


# ---------------------------------------------------------------------------
# Finding 1: fail closed on git command failure
# ---------------------------------------------------------------------------

class TestGitError(unittest.TestCase):
    """launch.sh must refuse (no tick, git-error alert) when a gating git command fails."""

    def setUp(self) -> None:
        self.f = _Fixture()
        self.f.setup_origin()
        boot = self.f.run_launch()
        self.assertEqual(boot.returncode, 0, boot.stderr[-300:])

    def tearDown(self) -> None:
        self.f.cleanup()

    def _notify_keys(self) -> list[str]:
        if not self.f.notify_log.exists():
            return []
        keys = []
        for line in self.f.notify_log.read_text().splitlines():
            parts = line.split()
            for i, p in enumerate(parts):
                if p == "--once" and i + 1 < len(parts):
                    keys.append(parts[i + 1])
        return keys

    def test_corrupt_index_fails_closed(self) -> None:
        """Corrupt .git/index makes git status exit non-zero: no tick, git-error alert sent."""
        clone = self.f.clone_path()
        index = clone / ".git" / "index"
        index.write_bytes(b"NOT A VALID GIT INDEX FILE CONTENT")

        result = self.f.run_launch()

        self.assertEqual(result.returncode, 0, result.stderr[-300:])
        self.assertNotIn("STUB TICK", result.stdout)
        # Some log about the git failure must appear
        self.assertTrue(
            "git" in result.stderr.lower() or "refused" in result.stderr.lower(),
            f"expected a git-error log line; stderr={result.stderr[-300:]}",
        )

        # A git-error alert key must have been sent
        keys = self._notify_keys()
        self.assertTrue(
            any(k.startswith("clone-refused:git-error:") for k in keys),
            f"expected clone-refused:git-error key, got {keys!r}; stderr={result.stderr[-300:]}",
        )


# ---------------------------------------------------------------------------
# Finding 7: fd 8 must not be inherited by long-lived children of tick.sh
# ---------------------------------------------------------------------------

# Stub tick that closes stdout + stderr BEFORE backgrounding a sleep so that
# Python's subprocess.run(capture_output=True) returns immediately (without
# waiting for the sleep to exit).  The background sleep inherits fd 8 only if
# launch.sh does NOT close it before exec'ing tick.sh.
_STUB_TICK_BG = """\
#!/usr/bin/env bash
# Close stdout and stderr first so the test harness does not wait for the sleep.
exec 1>&-
exec 2>&-
sleep 4 &
"""


class TestFdInheritance(unittest.TestCase):
    """fd 8 (launcher lock) must not block the next launcher run via background children."""

    def setUp(self) -> None:
        self.f = _Fixture()
        self.f.setup_origin()
        # Push a tick that backgrounds a sleep
        self.f.push_file_to_origin(
            "scripts/ops/pr-deploy-agent/tick.sh",
            _STUB_TICK_BG,
            executable=True,
        )
        boot = self.f.run_launch()
        self.assertEqual(boot.returncode, 0, boot.stderr[-300:])

    def tearDown(self) -> None:
        # Kill any stray sleep processes to avoid polluting subsequent tests.
        subprocess.run(["pkill", "[s]leep"], capture_output=True)
        self.f.cleanup()

    def test_background_child_does_not_block_next_run(self) -> None:
        """A sleep backgrounded by tick.sh must not hold fd 8 and block the next run.

        Finding 7: before the fix, the sleep inherits fd 8 and the second
        launcher invocation blocks with 'another tick holds the lock'.
        After the fix (exec 8>&- before exec tick.sh), sleep has no fd 8.
        """
        r1 = self.f.run_launch()
        # r1 returns quickly because tick.sh closed stdout before starting sleep
        self.assertEqual(r1.returncode, 0, r1.stderr[-300:])

        # Second run: must NOT be blocked by the sleep holding fd 8
        r2 = self.f.run_launch()
        self.assertEqual(r2.returncode, 0, r2.stderr[-300:])
        self.assertNotIn("another tick holds the lock", r2.stderr,
                         "fd 8 was inherited by the backgrounded sleep; close it before exec")
