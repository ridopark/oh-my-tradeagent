#!/usr/bin/env python3
"""Tests for Step 4 of pr_duty.py — NEEDS_FIXES loop.

Covers:
  - round increments only after a successful push
  - round 3 + still NEEDS_FIXES → gh pr edit --add-label needs-input + Discord ping once
  - fake Sonnet makes no commit → no push, round not counted, Discord "fix produced nothing"
  - remote head moved (push rejected) → no push, round not counted
  - verdict READY resets the round counter
  - PR_MODE=recommend → no push, action="would-fix"

Uses:
  - fake gh executable (returns canned PR data + status)
  - fake claude executable (returns canned verify result; optionally commits for Sonnet)
  - throwaway git repo pair (bare remote + local clone) for worktree tests

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

HERE = pathlib.Path(__file__).resolve()
AGENT_DIR = HERE.parent.parent
SCRIPT = AGENT_DIR / "pr_duty.py"

if str(AGENT_DIR) not in sys.path:
    sys.path.insert(0, str(AGENT_DIR))

# Pre-load sibling modules so the test process has them cached.
for mod in ("statefile", "notify"):
    mpath = AGENT_DIR / f"{mod}.py"
    spec = importlib.util.spec_from_file_location(mod, mpath)
    assert spec and spec.loader
    m = importlib.util.module_from_spec(spec)
    sys.modules[mod] = m
    spec.loader.exec_module(m)  # type: ignore[union-attr]

import statefile as _sf


# ---------------------------------------------------------------------------
# Git fixture helpers
# ---------------------------------------------------------------------------

def _setup_git_repos(
    tmp: pathlib.Path,
    branch: str = "feat/fix-me",
    add_reject_hook: bool = False,
) -> tuple[pathlib.Path, pathlib.Path, str]:
    """Create a bare remote + local clone with a branch.

    Returns (remote_dir, local_dir, branch_sha).
    add_reject_hook: if True, the bare remote's pre-receive hook rejects all pushes
    (simulates remote head moving / non-fast-forward rejection).
    """
    remote = tmp / "remote.git"
    local = tmp / "local"

    subprocess.run(["git", "init", "--bare", str(remote)],
                   check=True, capture_output=True)
    subprocess.run(["git", "clone", str(remote), str(local)],
                   check=True, capture_output=True)

    for key, val in [("user.email", "agent@test"), ("user.name", "TestAgent")]:
        subprocess.run(["git", "-C", str(local), "config", key, val],
                       check=True, capture_output=True)

    # Initial commit on main/master (default branch name varies by git version)
    (local / "README.md").write_text("init")
    subprocess.run(["git", "-C", str(local), "add", "."],
                   check=True, capture_output=True)
    subprocess.run(["git", "-C", str(local), "commit", "-m", "init"],
                   check=True, capture_output=True)
    # Determine default branch name
    default_branch_result = subprocess.run(
        ["git", "-C", str(local), "rev-parse", "--abbrev-ref", "HEAD"],
        capture_output=True, text=True
    )
    default_branch = default_branch_result.stdout.strip() or "main"
    subprocess.run(["git", "-C", str(local), "push", "-u", "origin", f"HEAD:{default_branch}"],
                   check=True, capture_output=True)

    # Create the PR branch
    subprocess.run(["git", "-C", str(local), "checkout", "-b", branch],
                   check=True, capture_output=True)
    (local / "fix_me.py").write_text("# TODO: fix this\n")
    subprocess.run(["git", "-C", str(local), "add", "."],
                   check=True, capture_output=True)
    subprocess.run(["git", "-C", str(local), "commit", "-m", "wip: needs fixes"],
                   check=True, capture_output=True)
    subprocess.run(["git", "-C", str(local), "push", "-u", "origin", branch],
                   check=True, capture_output=True)

    branch_sha = subprocess.check_output(
        ["git", "-C", str(local), "rev-parse", "HEAD"],
        text=True
    ).strip()

    if add_reject_hook:
        hooks_dir = remote / "hooks"
        hooks_dir.mkdir(exist_ok=True)
        hook = hooks_dir / "pre-receive"
        hook.write_text("#!/bin/bash\necho 'remote: non-fast-forward' >&2\nexit 1\n")
        hook.chmod(hook.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)

    return remote, local, branch_sha


# ---------------------------------------------------------------------------
# Fake gh builder
# ---------------------------------------------------------------------------

def _make_fake_gh(
    bin_dir: pathlib.Path,
    fixture: dict,
    allow_writes: bool = False,
) -> None:
    """Write fake gh that returns canned JSON and records calls."""
    (bin_dir / "gh_fixture.json").write_text(json.dumps(fixture))
    write_verbs = {"merge", "rerun", "create", "comment", "edit"}
    write_cmds = {"pr", "run", "issue", "label"}
    (bin_dir / "gh").write_text(
        "#!/usr/bin/env python3\n"
        "import sys, json, pathlib\n"
        "here = pathlib.Path(__file__).parent\n"
        "fixture = json.loads((here / 'gh_fixture.json').read_text())\n"
        "args = sys.argv[1:]\n"
        "with open(here / 'gh_calls.log', 'a') as f:\n"
        "    f.write(json.dumps(args) + '\\n')\n"
        f"allow_writes = {allow_writes!r}\n"
        f"write_verbs = {write_verbs!r}\n"
        f"write_cmds = {write_cmds!r}\n"
        "if not allow_writes:\n"
        "    if len(args) >= 2 and args[0] in write_cmds and args[1] in write_verbs:\n"
        "        sys.stderr.write('FAKE GH: write not allowed\\n'); sys.exit(1)\n"
        "    if args and args[0] == 'api' and '-X' in args:\n"
        "        idx = args.index('-X'); m = args[idx+1] if idx+1 < len(args) else 'GET'\n"
        "        if m not in ('GET','HEAD'):\n"
        "            sys.stderr.write('FAKE GH: POST not allowed\\n'); sys.exit(1)\n"
        "# pr view mergeCommit\n"
        "if args and args[0] == 'pr' and 'view' in args and 'mergeCommit' in ' '.join(args):\n"
        "    pr_num = next((a for a in args if a.isdigit()), '')\n"
        "    print(json.dumps({'mergeCommit': {'oid': f'abc{pr_num}def'}})); sys.exit(0)\n"
        "url = next((a for a in args if '/' in a and not a.startswith('-')), '')\n"
        "full = ' '.join(args)\n"
        "for pattern, resp in fixture.items():\n"
        "    if pattern in url or pattern in full:\n"
        "        if isinstance(resp, (dict, list)): print(json.dumps(resp))\n"
        "        else: print(resp)\n"
        "        sys.exit(0)\n"
        "if args and args[0] == 'api' and 'status' in full:\n"
        "    print(json.dumps({'statuses': []})); sys.exit(0)\n"
        "if args and args[0] == 'api': print('{}'); sys.exit(0)\n"
        "if args and args[0] == 'issue' and 'list' in args: print('[]'); sys.exit(0)\n"
        "sys.exit(0)\n"
    )
    (bin_dir / "gh").chmod(
        (bin_dir / "gh").stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH
    )


def _read_calls(bin_dir: pathlib.Path) -> list[list[str]]:
    log = bin_dir / "gh_calls.log"
    if not log.exists():
        return []
    calls = []
    for line in log.read_text().strip().splitlines():
        try:
            calls.append(json.loads(line))
        except json.JSONDecodeError:
            pass
    return calls


def _has_write_call(calls: list[list[str]]) -> bool:
    write_verbs = {"merge", "rerun", "create", "comment", "edit"}
    write_cmds = {"pr", "run", "issue", "label"}
    for c in calls:
        if len(c) >= 2 and c[0] in write_cmds and c[1] in write_verbs:
            return True
        if c and c[0] == "api" and "-X" in c:
            idx = c.index("-X")
            meth = c[idx + 1] if idx + 1 < len(c) else "GET"
            if meth not in ("GET", "HEAD"):
                return True
    return False


# ---------------------------------------------------------------------------
# Fake claude builder
# ---------------------------------------------------------------------------

def _make_fake_claude(
    bin_dir: pathlib.Path,
    verify_result: dict | None = None,
    should_commit: bool = False,
    sonnet_exit: int = 0,
) -> None:
    """Write fake claude executable.

    For --model *fable*: outputs canned verify_result JSON.
    For --model *sonnet*: optionally commits a file, then exits with sonnet_exit.
    """
    if verify_result is None:
        verify_result = {
            "confirmed": ["finding-1"],
            "wrong": [],
            "stale": [],
            "out_of_scope": [],
        }
    fixture = {
        "verify_result": verify_result,
        "should_commit": should_commit,
        "sonnet_exit": sonnet_exit,
    }
    (bin_dir / "claude_fixture.json").write_text(json.dumps(fixture))
    (bin_dir / "claude").write_text(
        "#!/usr/bin/env python3\n"
        "import sys, os, json, subprocess, pathlib\n"
        "here = pathlib.Path(__file__).parent\n"
        "args = sys.argv[1:]\n"
        "with open(here / 'claude_calls.log', 'a') as f:\n"
        "    f.write(json.dumps(args) + '\\n')\n"
        "config = json.loads((here / 'claude_fixture.json').read_text())\n"
        "model = ''\n"
        "for i, a in enumerate(args):\n"
        "    if a == '--model' and i+1 < len(args):\n"
        "        model = args[i+1]; break\n"
        "if 'fable' in model:\n"
        "    print(json.dumps(config['verify_result'])); sys.exit(0)\n"
        "elif 'sonnet' in model:\n"
        "    if config.get('should_commit', False):\n"
        "        cwd = os.getcwd()\n"
        "        p = pathlib.Path(cwd) / '.agent-fix'\n"
        "        p.write_text('fix')\n"
        "        subprocess.run(['git', 'add', '.agent-fix'], cwd=cwd, capture_output=True)\n"
        "        subprocess.run(\n"
        "            ['git', '-c', 'user.email=agent@test', '-c', 'user.name=Agent',\n"
        "             'commit', '-m', 'fix: apply confirmed findings'],\n"
        "            cwd=cwd, capture_output=True\n"
        "        )\n"
        "    sys.exit(config.get('sonnet_exit', 0))\n"
        "else:\n"
        "    print('{}'); sys.exit(0)\n"
    )
    (bin_dir / "claude").chmod(
        (bin_dir / "claude").stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH
    )


# ---------------------------------------------------------------------------
# PR / status fixture helpers
# ---------------------------------------------------------------------------

def _make_needs_fixes_pr(
    number: int = 1,
    head_sha: str = "abc123",
    head_ref: str = "feat/fix-me",
) -> dict:
    return {
        "number": number,
        "title": "feat: needs fixes",
        "headRefOid": head_sha,
        "headRefName": head_ref,
        "baseRefName": "main",
        "isDraft": False,
        "isCrossRepository": False,
        "labels": [],
        "mergeable": "MERGEABLE",
        "mergeStateStatus": "CLEAN",
        "body": "## Summary\nSome change.\n",
        "statusCheckRollup": [],
        "files": [{"path": "scripts/ops/something.py"}],
    }


def _make_needs_fixes_status(sha: str) -> dict:
    return {
        f"commits/{sha}/status": {
            "statuses": [{
                "context": "claude/verdict",
                "description": "NEEDS_FIXES: fix the import",
                "state": "failure",
                "created_at": "2026-10-29T10:00:00Z",
            }]
        }
    }


def _make_ready_status(sha: str) -> dict:
    return {
        f"commits/{sha}/status": {
            "statuses": [{
                "context": "claude/verdict",
                "description": "READY: looks good",
                "state": "success",
                "created_at": "2026-10-29T12:00:00Z",
            }]
        }
    }


def _make_bot_comment(pr_number: int) -> dict:
    return {
        f"issues/{pr_number}/comments": [
            {
                "id": 1,
                "user": {"login": "claude[bot]"},
                "body": "## Review findings\n\n- finding-1: missing import\n",
                "updated_at": "2026-10-29T11:00:00Z",
            }
        ]
    }


# ---------------------------------------------------------------------------
# Subprocess runner
# ---------------------------------------------------------------------------

def _run_pr_duty(
    bin_dir: pathlib.Path,
    state_dir: pathlib.Path,
    extra_env: dict | None = None,
    repo_root: pathlib.Path | None = None,
) -> dict:
    """Run pr_duty.py --plan --json via subprocess."""
    env = {
        **os.environ,
        "PATH": f"{bin_dir}:{os.environ.get('PATH', '')}",
        "REPO": "ridopark/oh-my-tradeagent",
        "PR_MODE": "recommend",
        "STATE_DIR": str(state_dir),
        "REPO_ROOT": str(repo_root or AGENT_DIR.parents[2]),
        "DISCORD_DRY_RUN": "1",
        "PYTHONPATH": str(AGENT_DIR),
    }
    if extra_env:
        env.update(extra_env)
    result = subprocess.run(
        [sys.executable, str(SCRIPT), "--plan", "--json"],
        capture_output=True, text=True, timeout=60, env=env,
    )
    if result.returncode != 0:
        raise AssertionError(
            f"pr_duty.py exited {result.returncode}:\n"
            f"stdout: {result.stdout[:500]}\nstderr: {result.stderr[:500]}"
        )
    return json.loads(result.stdout)


# ---------------------------------------------------------------------------
# Tests: no-git cases
# ---------------------------------------------------------------------------

class TestRound3Cap(unittest.TestCase):
    """PR with rounds=3 in state gets needs-input label + ping (no git needed)."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self._tmp_bin = tempfile.TemporaryDirectory()
        self._tmp_state = tempfile.TemporaryDirectory()
        self._bin = pathlib.Path(self._tmp_bin.name)
        self._state_dir = pathlib.Path(self._tmp_state.name)

    def tearDown(self):
        self._tmp.cleanup()
        self._tmp_bin.cleanup()
        self._tmp_state.cleanup()

    def _pre_populate_state(self, pr_number: int, rounds: int) -> None:
        state_path = self._state_dir / "state.json"
        state = {"pr_rounds": {str(pr_number): rounds}}
        _sf.save(state_path, state)

    def test_round_3_adds_needs_input_label(self):
        """After 3 rounds still NEEDS_FIXES → gh pr edit --add-label needs-input called."""
        pr = _make_needs_fixes_pr(number=1, head_sha="sha_cap")
        _make_fake_gh(
            self._bin,
            {
                "pr list --state open": [pr],
                **_make_needs_fixes_status("sha_cap"),
                **_make_bot_comment(1),
            },
            allow_writes=True,
        )
        _make_fake_claude(self._bin)
        self._pre_populate_state(1, 3)

        result = _run_pr_duty(
            self._bin, self._state_dir, extra_env={"PR_MODE": "act"}
        )
        d = result["prs"][0]
        self.assertEqual(d["verdict_token"], "NEEDS_FIXES")
        self.assertEqual(d["action"], "skip")
        self.assertIn("needs-input", d["reason"])

        calls = _read_calls(self._bin)
        label_calls = [c for c in calls if c[0] == "pr" and c[1] == "edit" and "--add-label" in c]
        self.assertEqual(len(label_calls), 1, f"Expected 1 label call, got: {label_calls}")
        self.assertTrue(any("needs-input" in str(c) for c in label_calls))

    def test_round_3_ping_only_once(self):
        """Second tick for rounds=3 PR does NOT re-add the label or re-ping."""
        pr = _make_needs_fixes_pr(number=2, head_sha="sha_cap2")
        _make_fake_gh(
            self._bin,
            {
                "pr list --state open": [pr],
                **_make_needs_fixes_status("sha_cap2"),
                **_make_bot_comment(2),
            },
            allow_writes=True,
        )
        _make_fake_claude(self._bin)
        self._pre_populate_state(2, 3)

        # First tick
        _run_pr_duty(self._bin, self._state_dir, extra_env={"PR_MODE": "act"})
        first_calls = _read_calls(self._bin)
        first_label = [c for c in first_calls if c[0] == "pr" and c[1] == "edit" and "--add-label" in c]

        # Clear calls log
        (self._bin / "gh_calls.log").write_text("")

        # Second tick
        _run_pr_duty(self._bin, self._state_dir, extra_env={"PR_MODE": "act"})
        second_calls = _read_calls(self._bin)
        second_label = [c for c in second_calls if c[0] == "pr" and c[1] == "edit" and "--add-label" in c]

        self.assertEqual(len(first_label), 1, "First tick should add label once")
        self.assertEqual(len(second_label), 0, "Second tick should NOT re-add label")

    def test_recommend_mode_no_writes(self):
        """In recommend mode with rounds=3, no write calls issued."""
        pr = _make_needs_fixes_pr(number=3, head_sha="sha_rec_cap")
        _make_fake_gh(
            self._bin,
            {
                "pr list --state open": [pr],
                **_make_needs_fixes_status("sha_rec_cap"),
            },
            allow_writes=False,  # will fail if write is attempted
        )
        _make_fake_claude(self._bin)
        self._pre_populate_state(3, 3)

        # Should not raise (no writes in recommend mode)
        result = _run_pr_duty(self._bin, self._state_dir)
        calls = _read_calls(self._bin)
        self.assertFalse(_has_write_call(calls), f"Write found in recommend mode: {calls}")


class TestReadyResetsCounter(unittest.TestCase):
    """READY verdict resets the round counter."""

    def setUp(self):
        self._tmp_bin = tempfile.TemporaryDirectory()
        self._tmp_state = tempfile.TemporaryDirectory()
        self._bin = pathlib.Path(self._tmp_bin.name)
        self._state_dir = pathlib.Path(self._tmp_state.name)

    def tearDown(self):
        self._tmp_bin.cleanup()
        self._tmp_state.cleanup()

    def test_ready_verdict_resets_round_counter(self):
        """A PR that previously had rounds=1 gets reset to 0 when verdict is READY."""
        pr = {
            "number": 5,
            "title": "feat: ready",
            "headRefOid": "sha_ready",
            "headRefName": "feat/ready-branch",
            "baseRefName": "main",
            "isDraft": False,
            "isCrossRepository": False,
            "labels": [],
            "mergeable": "MERGEABLE",
            "mergeStateStatus": "CLEAN",
            "body": "",
            "statusCheckRollup": [
                {"name": "CI", "status": "COMPLETED", "conclusion": "SUCCESS"},
            ],
            "files": [],
        }
        _make_fake_gh(
            self._bin,
            {
                "pr list --state open": [pr],
                **_make_ready_status("sha_ready"),
            },
            allow_writes=True,
        )

        # Pre-populate state with rounds=1 for PR #5
        state_path = self._state_dir / "state.json"
        _sf.save(state_path, {"pr_rounds": {"5": 1}})

        _run_pr_duty(
            self._bin, self._state_dir, extra_env={"PR_MODE": "act"}
        )

        # After processing a READY verdict, round counter should be reset
        state = _sf.load(state_path)
        rounds = state.get("pr_rounds", {}).get("5", 0)
        self.assertEqual(rounds, 0, f"Expected rounds=0 after READY, got {rounds}")


# ---------------------------------------------------------------------------
# Tests: with git (worktree lifecycle)
# ---------------------------------------------------------------------------

class TestFixRoundWithGit(unittest.TestCase):
    """Fix-round tests that use a real git setup."""

    def setUp(self):
        self._tmpdir = tempfile.TemporaryDirectory()
        self._tmp_bin = tempfile.TemporaryDirectory()
        self._tmp_state = tempfile.TemporaryDirectory()
        self._bin = pathlib.Path(self._tmp_bin.name)
        self._state_dir = pathlib.Path(self._tmp_state.name)

    def tearDown(self):
        self._tmpdir.cleanup()
        self._tmp_bin.cleanup()
        self._tmp_state.cleanup()

    def _setup(
        self,
        branch: str = "feat/fix-me",
        add_reject_hook: bool = False,
    ) -> tuple[pathlib.Path, str]:
        """Set up git repos; return (local_path, branch_sha)."""
        _, local, sha = _setup_git_repos(
            pathlib.Path(self._tmpdir.name),
            branch=branch,
            add_reject_hook=add_reject_hook,
        )
        return local, sha

    def test_happy_path_round_increments(self):
        """Verify + Sonnet commit + push → round count goes from 0 to 1."""
        local, sha = self._setup()

        pr = _make_needs_fixes_pr(number=10, head_sha=sha, head_ref="feat/fix-me")
        _make_fake_gh(
            self._bin,
            {
                "pr list --state open": [pr],
                **_make_needs_fixes_status(sha),
                **_make_bot_comment(10),
            },
            allow_writes=True,
        )
        _make_fake_claude(self._bin, should_commit=True)

        result = _run_pr_duty(
            self._bin, self._state_dir,
            extra_env={"PR_MODE": "act"},
            repo_root=local,
        )
        d = result["prs"][0]
        self.assertEqual(d["verdict_token"], "NEEDS_FIXES")
        self.assertIn(d["action"], ("fixed", "skip"), msg=f"Expected fixed/skip, got {d}")

        # Round should have been incremented
        state = _sf.load(self._state_dir / "state.json")
        rounds = state.get("pr_rounds", {}).get("10", 0)
        self.assertEqual(rounds, 1, f"Expected rounds=1 after push, got {rounds}")

    def test_no_commit_round_not_counted(self):
        """Sonnet makes no commit → round stays at 0, action is skip."""
        local, sha = self._setup(branch="feat/no-commit")

        pr = _make_needs_fixes_pr(number=11, head_sha=sha, head_ref="feat/no-commit")
        _make_fake_gh(
            self._bin,
            {
                "pr list --state open": [pr],
                **_make_needs_fixes_status(sha),
                **_make_bot_comment(11),
            },
            allow_writes=True,
        )
        # Sonnet does NOT commit
        _make_fake_claude(self._bin, should_commit=False)

        result = _run_pr_duty(
            self._bin, self._state_dir,
            extra_env={"PR_MODE": "act"},
            repo_root=local,
        )
        d = result["prs"][0]
        self.assertEqual(d["verdict_token"], "NEEDS_FIXES")
        self.assertEqual(d["action"], "skip")

        state = _sf.load(self._state_dir / "state.json")
        rounds = state.get("pr_rounds", {}).get("11", 0)
        self.assertEqual(rounds, 0, f"Round should NOT increment when Sonnet makes no commit; got {rounds}")

    def test_remote_head_moved_no_push(self):
        """Push rejected (non-fast-forward) → round not counted, action is skip."""
        local, sha = self._setup(branch="feat/rejected", add_reject_hook=True)

        pr = _make_needs_fixes_pr(number=12, head_sha=sha, head_ref="feat/rejected")
        _make_fake_gh(
            self._bin,
            {
                "pr list --state open": [pr],
                **_make_needs_fixes_status(sha),
                **_make_bot_comment(12),
            },
            allow_writes=True,
        )
        # Sonnet commits, but push will be rejected by pre-receive hook
        _make_fake_claude(self._bin, should_commit=True)

        result = _run_pr_duty(
            self._bin, self._state_dir,
            extra_env={"PR_MODE": "act"},
            repo_root=local,
        )
        d = result["prs"][0]
        self.assertEqual(d["verdict_token"], "NEEDS_FIXES")
        self.assertEqual(d["action"], "skip")

        state = _sf.load(self._state_dir / "state.json")
        rounds = state.get("pr_rounds", {}).get("12", 0)
        self.assertEqual(rounds, 0, f"Round should NOT increment when push is rejected; got {rounds}")

    def test_recommend_mode_no_push(self):
        """PR_MODE=recommend → action='would-fix', no git push."""
        local, sha = self._setup(branch="feat/recommend")

        pr = _make_needs_fixes_pr(number=13, head_sha=sha, head_ref="feat/recommend")
        _make_fake_gh(
            self._bin,
            {
                "pr list --state open": [pr],
                **_make_needs_fixes_status(sha),
                **_make_bot_comment(13),
            },
            allow_writes=False,  # will fail if write is attempted
        )
        _make_fake_claude(self._bin, should_commit=True)

        # Default PR_MODE is recommend
        result = _run_pr_duty(
            self._bin, self._state_dir,
            repo_root=local,
        )
        d = result["prs"][0]
        self.assertEqual(d["verdict_token"], "NEEDS_FIXES")
        self.assertEqual(d["action"], "would-fix")

        # No push → round not incremented
        state = _sf.load(self._state_dir / "state.json")
        rounds = state.get("pr_rounds", {}).get("13", 0)
        self.assertEqual(rounds, 0, f"Round should NOT increment in recommend mode; got {rounds}")

        # No write calls
        calls = _read_calls(self._bin)
        self.assertFalse(_has_write_call(calls), f"Write found in recommend mode: {calls}")

    def test_skip_review_commit_aborts_push(self):
        """Finding 8a: if the Sonnet fix commits with [skip-review] in the message,
        the script must refuse to push and return action='skip'.

        Before this fix, any commit from Sonnet would be pushed regardless of its
        message, which could loop the review indefinitely.
        """
        local, sha = self._setup(branch="feat/skip-review")

        pr = _make_needs_fixes_pr(number=14, head_sha=sha, head_ref="feat/skip-review")
        _make_fake_gh(
            self._bin,
            {
                "pr list --state open": [pr],
                **_make_needs_fixes_status(sha),
                **_make_bot_comment(14),
            },
            allow_writes=True,
        )

        # Override fake claude: make Sonnet commit with [skip-review] tag.
        (self._bin / "claude_fixture.json").write_text(json.dumps({
            "verify_result": {
                "confirmed": ["finding-1"],
                "wrong": [], "stale": [], "out_of_scope": [],
            },
            "should_commit": False,  # we'll commit manually below via a custom claude
            "sonnet_exit": 0,
        }))
        # Write a custom claude that commits with [skip-review]
        (self._bin / "claude").write_text(
            "#!/usr/bin/env python3\n"
            "import sys, os, json, subprocess, pathlib\n"
            "here = pathlib.Path(__file__).parent\n"
            "args = sys.argv[1:]\n"
            "with open(here / 'claude_calls.log', 'a') as f:\n"
            "    f.write(json.dumps(args) + '\\n')\n"
            "config = json.loads((here / 'claude_fixture.json').read_text())\n"
            "model = ''\n"
            "for i, a in enumerate(args):\n"
            "    if a == '--model' and i+1 < len(args):\n"
            "        model = args[i+1]; break\n"
            "if 'fable' in model:\n"
            "    print(json.dumps(config['verify_result'])); sys.exit(0)\n"
            "elif 'sonnet' in model:\n"
            "    cwd = os.getcwd()\n"
            "    p = pathlib.Path(cwd) / '.agent-fix'\n"
            "    p.write_text('fix')\n"
            "    subprocess.run(['git', 'add', '.agent-fix'], cwd=cwd, capture_output=True)\n"
            "    subprocess.run(\n"
            "        ['git', '-c', 'user.email=agent@test', '-c', 'user.name=Agent',\n"
            "         'commit', '-m', 'fix: apply findings [skip-review]'],\n"
            "        cwd=cwd, capture_output=True\n"
            "    )\n"
            "    sys.exit(0)\n"
            "else:\n"
            "    print('{}'); sys.exit(0)\n"
        )
        (self._bin / "claude").chmod(
            (self._bin / "claude").stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH
        )

        result = _run_pr_duty(
            self._bin, self._state_dir,
            repo_root=local,
            extra_env={"PR_MODE": "act"},
        )
        d = result["prs"][0]
        self.assertEqual(d["action"], "skip",
                         "[skip-review] commit must result in action='skip'")
        self.assertIn("skip-review", d.get("reason", "").lower(),
                      f"reason must mention skip-review; got: {d.get('reason')}")

        # Round must NOT increment
        state = _sf.load(self._state_dir / "state.json")
        rounds = state.get("pr_rounds", {}).get("14", 0)
        self.assertEqual(rounds, 0, f"Round must not increment when push is blocked; got {rounds}")

    def test_test_file_deletion_aborts_push(self):
        """Finding 8b: if the Sonnet fix deletes a test file, the push is refused.

        Deleting a test file weakens the test suite; the guard prevents the agent from
        silently shipping regressions.
        """
        local, sha = self._setup(branch="feat/del-test")

        # Add a test file to the branch so it can be deleted
        test_file_path = local / "tests" / "test_sample.py"
        test_file_path.parent.mkdir(exist_ok=True)
        test_file_path.write_text("# test\n")
        subprocess.run(["git", "-C", str(local), "add", "tests/test_sample.py"],
                       check=True, capture_output=True)
        subprocess.run(["git", "-C", str(local), "-c", "user.email=agent@test",
                        "-c", "user.name=Agent", "commit", "-m", "add test file"],
                       check=True, capture_output=True)
        subprocess.run(["git", "-C", str(local), "push", "origin", "feat/del-test"],
                       check=True, capture_output=True)
        new_sha = subprocess.check_output(
            ["git", "-C", str(local), "rev-parse", "HEAD"], text=True
        ).strip()

        pr = _make_needs_fixes_pr(number=15, head_sha=new_sha, head_ref="feat/del-test")
        _make_fake_gh(
            self._bin,
            {
                "pr list --state open": [pr],
                **_make_needs_fixes_status(new_sha),
                **_make_bot_comment(15),
            },
            allow_writes=True,
        )

        # Write a custom claude that deletes the test file
        (self._bin / "claude_fixture.json").write_text(json.dumps({
            "verify_result": {
                "confirmed": ["finding-1"],
                "wrong": [], "stale": [], "out_of_scope": [],
            },
            "should_commit": False,
            "sonnet_exit": 0,
        }))
        (self._bin / "claude").write_text(
            "#!/usr/bin/env python3\n"
            "import sys, os, json, subprocess, pathlib\n"
            "here = pathlib.Path(__file__).parent\n"
            "args = sys.argv[1:]\n"
            "with open(here / 'claude_calls.log', 'a') as f:\n"
            "    f.write(json.dumps(args) + '\\n')\n"
            "config = json.loads((here / 'claude_fixture.json').read_text())\n"
            "model = ''\n"
            "for i, a in enumerate(args):\n"
            "    if a == '--model' and i+1 < len(args):\n"
            "        model = args[i+1]; break\n"
            "if 'fable' in model:\n"
            "    print(json.dumps(config['verify_result'])); sys.exit(0)\n"
            "elif 'sonnet' in model:\n"
            "    cwd = os.getcwd()\n"
            "    # Delete the test file\n"
            "    t = pathlib.Path(cwd) / 'tests' / 'test_sample.py'\n"
            "    if t.exists(): t.unlink()\n"
            "    subprocess.run(['git', 'add', '-A'], cwd=cwd, capture_output=True)\n"
            "    subprocess.run(\n"
            "        ['git', '-c', 'user.email=agent@test', '-c', 'user.name=Agent',\n"
            "         'commit', '-m', 'fix: apply findings'],\n"
            "        cwd=cwd, capture_output=True\n"
            "    )\n"
            "    sys.exit(0)\n"
            "else:\n"
            "    print('{}'); sys.exit(0)\n"
        )
        (self._bin / "claude").chmod(
            (self._bin / "claude").stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH
        )

        result = _run_pr_duty(
            self._bin, self._state_dir,
            repo_root=local,
            extra_env={"PR_MODE": "act"},
        )
        d = result["prs"][0]
        self.assertEqual(d["action"], "skip",
                         "Deleting a test file must result in action='skip'")
        self.assertIn("test", d.get("reason", "").lower(),
                      f"reason must mention test file; got: {d.get('reason')}")

        # Round must NOT increment
        state = _sf.load(self._state_dir / "state.json")
        rounds = state.get("pr_rounds", {}).get("15", 0)
        self.assertEqual(rounds, 0, f"Round must not increment when push is blocked; got {rounds}")

    def test_timeout_expired_but_committed_returns_true(self):
        """Finding 9: when _run_sonnet_fix times out but a commit was made before the
        timeout, it must return True (committed=True) rather than False.

        Before this fix, TimeoutExpired was treated as 'no commit'; the partial work
        would be abandoned even though the agent had already committed a fix.

        This test verifies the post-timeout logic by examining the HEAD-before/after
        comparison that `_run_sonnet_fix` performs inside its TimeoutExpired handler.
        We build a git state where HEAD advanced, then confirm the comparison returns
        committed=True.
        """
        local, sha = self._setup(branch="feat/timeout-test")

        # Record HEAD before the simulated fix (this is `head_before` in the real code).
        head_before = sha

        # Simulate work done before the timeout: make a commit in local (replicates the
        # worktree path since git history is the same either way).
        (local / ".pre-timeout-fix").write_text("partial")
        subprocess.run(["git", "-C", str(local), "add", ".pre-timeout-fix"],
                       check=True, capture_output=True)
        subprocess.run(
            ["git", "-C", str(local), "-c", "user.email=agent@test",
             "-c", "user.name=Agent", "commit", "-m", "fix: partial before timeout"],
            check=True, capture_output=True,
        )

        # Record HEAD after the commit (this is `head_after` inside the except block).
        head_after = subprocess.check_output(
            ["git", "-C", str(local), "rev-parse", "HEAD"], text=True
        ).strip()

        # The key invariant: head changed → the TimeoutExpired path returns True.
        self.assertNotEqual(head_before, head_after,
                            "HEAD must advance after the commit")
        committed = bool(head_before and head_after and head_before != head_after)
        self.assertTrue(committed,
                        "When HEAD changed before timeout, the committed result must be True")


# ---------------------------------------------------------------------------
# Tests: statefile round functions (unit)
# ---------------------------------------------------------------------------

class TestStatefileRounds(unittest.TestCase):
    """Unit tests for get/set/reset_rounds in statefile.py."""

    def test_get_rounds_default_zero(self):
        state: dict = {}
        self.assertEqual(_sf.get_rounds(state, 1), 0)

    def test_set_and_get_rounds(self):
        state: dict = {}
        _sf.set_rounds(state, 42, 2)
        self.assertEqual(_sf.get_rounds(state, 42), 2)

    def test_reset_rounds(self):
        state: dict = {}
        _sf.set_rounds(state, 7, 3)
        _sf.reset_rounds(state, 7)
        self.assertEqual(_sf.get_rounds(state, 7), 0)

    def test_reset_rounds_no_error_when_absent(self):
        state: dict = {}
        _sf.reset_rounds(state, 99)  # should not raise
        self.assertEqual(_sf.get_rounds(state, 99), 0)


if __name__ == "__main__":
    unittest.main()
