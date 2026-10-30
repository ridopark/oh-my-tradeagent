#!/usr/bin/env python3
"""PR duty for pr-deploy-agent (Steps 3 & 4).

Reads the claude/verdict commit status for each open PR and, in act mode:
  - merges READY/SKIPPED PRs whose other checks are green and that are mergeable
  - re-runs an unrelated failing Java check once after linking it to an issue
  - pings once on NOT_REVIEWED / BLOCKED / NO_VERDICT
  - posts the merged PR's operator steps to Discord after its deploy completes
  - runs the NEEDS_FIXES loop: Fable verifies, Sonnet fixes and commits, script pushes

CLI:
  python3 pr_duty.py --plan [--json]   evaluate all open PRs

Exit 0 always (errors are Discord-posted or logged).

Env:
  REPO       GitHub repo slug  (default ridopark/oh-my-tradeagent)
  PR_MODE    recommend|act     (default recommend)
  STATE_DIR  path to state dir (default ~/.local/state/pr-deploy-agent)
  REPO_ROOT  absolute path to the repository root (set by tick.sh)
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import re
import subprocess
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

import notify as _notify
import statefile as _sf
from _shared import _gh, _parse_claude_json

_log = logging.getLogger(__name__)

_DEFAULT_REPO = "ridopark/oh-my-tradeagent"
_DEFAULT_STATE_DIR = Path.home() / ".local" / "state" / "pr-deploy-agent"
_UTC = timezone.utc

# Heading pattern that marks operator / manual steps in a PR body.
_OP_STEPS_HEADING_RE = re.compile(
    r"(?i)^#{1,4}\s+(operator|manual|after\s+merge|runbook)\b"
)

# Check name pattern for Java module checks: "Java (services/orchestrator)"
_JAVA_CHECK_RE = re.compile(r"^Java \((.+)\)$")

# Regex to detect "merge commit" request in PR body/title.
_MERGE_COMMIT_RE = re.compile(r"(?i)merge\s+commit")

# PR files that make a failing Java check "related" (never treat as unrelated).
_SHARED_PATH_PREFIXES = ("contract/", "infra/docker/")
_SHARED_EXACT_FILES = {"pom.xml"}

# Test-file path pattern used to guard against commits that delete tests.
_TEST_PATH_RE = re.compile(
    r"(/tests?/|test_[^/]+\.py$|[A-Z][^/]*Test\.java$|\.test\.[^/]+$)"
)


# ---------------------------------------------------------------------------
# Data type
# ---------------------------------------------------------------------------

@dataclass
class PRDecision:
    pr_number: int
    title: str
    head_sha: str
    verdict_token: str  # READY|NEEDS_FIXES|BLOCKED|SKIPPED|NOT_REVIEWED|NO_VERDICT|PENDING
    action: str         # "would-merge"|"merge"|"skip"|"ping"|"pending"|"unrelated-rerun"
    reason: str


# ---------------------------------------------------------------------------
# GitHub API helpers
# ---------------------------------------------------------------------------

def _list_prs(repo: str) -> list[dict]:
    """List open, non-draft, same-repo PRs with their checks and files."""
    fields = (
        "number,isDraft,isCrossRepository,labels,headRefOid,headRefName,"
        "baseRefName,mergeable,mergeStateStatus,title,body,statusCheckRollup,files"
    )
    raw = _gh(
        "pr", "list", "--state", "open", "--json", fields,
        "--repo", repo,
    )
    data = json.loads(raw)
    return data if isinstance(data, list) else []


def _read_verdict_status(repo: str, sha: str) -> tuple[str, str, str]:
    """Read the claude/verdict commit status for a SHA.

    Returns (token, full_description, created_at).
    token is one of: READY NEEDS_FIXES BLOCKED SKIPPED NOT_REVIEWED NO_VERDICT PENDING.
    PENDING means no claude/verdict status was found.
    created_at is the ISO-8601 timestamp of the status entry, or "" if absent.
    """
    raw = _gh("api", f"repos/{repo}/commits/{sha}/status")
    data = json.loads(raw)
    for s in data.get("statuses", []):
        if s.get("context") == "claude/verdict":
            desc = s.get("description") or ""
            token = desc.split(":")[0].strip().upper() if ":" in desc else desc.strip().upper()
            created_at = s.get("created_at") or ""
            return token, desc, created_at
    return "PENDING", "", ""


def _classify_checks(rollup: list[dict]) -> tuple[str, list[dict]]:
    """Classify non-Claude checks in statusCheckRollup.

    Returns ("green" | "pending" | "failing", failing_checks).
    """
    failing: list[dict] = []
    pending = False

    for item in rollup:
        # Distinguish StatusContext (has "context") from CheckRun (has "name")
        if "context" in item:
            # StatusContext
            context = item.get("context", "") or ""
            if context == "claude/verdict":
                continue
            state = (item.get("state") or "").upper()
            if state == "PENDING":
                pending = True
            elif state not in ("SUCCESS", "NEUTRAL"):
                failing.append(item)
        else:
            # CheckRun
            name = (item.get("name") or "").lower()
            if "claude" in name:
                continue
            status = (item.get("status") or "").upper()
            conclusion = (item.get("conclusion") or "").upper()
            if status != "COMPLETED":
                pending = True
            elif conclusion not in ("SUCCESS", "SKIPPED", "NEUTRAL"):
                failing.append(item)

    if failing:
        return "failing", failing
    if pending:
        return "pending", []
    return "green", []


# ---------------------------------------------------------------------------
# Unrelated-check rule helpers
# ---------------------------------------------------------------------------

def _is_unrelated_java_failure(check: dict, pr_files: list[str]) -> bool:
    """Return True if this is a Java check for a module not touched by the PR.

    Only applies to "Java (<module>)" check names.
    The PR must not touch contract/, pom.xml, or infra/docker/.
    """
    name = check.get("name") or ""
    m = _JAVA_CHECK_RE.match(name)
    if not m:
        return False
    module_path = m.group(1) + "/"

    # PR touches shared paths → related, not unrelated
    for f in pr_files:
        if any(f.startswith(p) for p in _SHARED_PATH_PREFIXES):
            return False
        if f in _SHARED_EXACT_FILES:
            return False

    # PR touches the module → related
    if any(f.startswith(module_path) for f in pr_files):
        return False

    return True


def _get_run_id_from_check(check: dict) -> int | None:
    """Extract the Actions run ID from a check run's detailsUrl."""
    url = check.get("detailsUrl") or check.get("url") or ""
    m = re.search(r"/actions/runs/(\d+)", url)
    if m:
        return int(m.group(1))
    return None


def _get_failing_test_class(run_id: int, repo: str) -> str | None:
    """Return the first failing Java test class from `gh run view --log-failed`."""
    try:
        text = _gh("run", "view", str(run_id), "--log-failed", "--repo", repo, timeout=30)
    except (RuntimeError, FileNotFoundError, OSError):
        return None
    # Match Java test class names (uppercase, ends with Test or Tests)
    m = re.search(r"\b([A-Z][A-Za-z0-9_$]*(?:Test|Tests)[A-Za-z0-9_$]*)\b", text)
    if m:
        return m.group(1)
    return None


def _handle_unrelated_check(
    pr_number: int,
    check: dict,
    repo: str,
    state: dict,
    repo_root: Path,
    pr_mode: str,
) -> bool:
    """Link issue and rerun once per (PR, head SHA, check name).

    Returns True if a rerun was issued or had already been issued for this key.
    Returns False if the rerun was skipped (e.g., second failure → Discord ping).
    """
    check_name = check.get("name") or ""
    # State key for tracking the rerun
    # head SHA is part of the check name context; use run_id as uniqueness key
    run_id = _get_run_id_from_check(check)
    if run_id is None:
        return False

    rerun_key = f"pr:{pr_number}:rerun:{run_id}"
    second_key = f"pr:{pr_number}:second-fail:{check_name}"

    if _sf.is_notified(state, rerun_key):
        # Already rerun this run — if still failing, this is the second failure.
        if not _sf.is_notified(state, second_key):
            m = _JAVA_CHECK_RE.match(check_name)
            module = m.group(1) if m else check_name
            _notify.notify_once(
                state, second_key, repo_root,
                "[pr-deploy-agent] PR unrelated check still failing",
                f"PR #{pr_number}: {check_name} failed again after rerun "
                f"(run {run_id}); module `{module}` — check manually.",
                "yellow",
            )
        return False  # second failure: no rerun

    if pr_mode != "act":
        return True  # would rerun but recommend mode

    # Find or create the issue, then rerun.
    m = _JAVA_CHECK_RE.match(check_name)
    module = m.group(1) if m else check_name
    test_class = _get_failing_test_class(run_id, repo)
    run_url = f"https://github.com/{repo}/actions/runs/{run_id}"

    if test_class:
        try:
            issues_raw = _gh(
                "issue", "list",
                "--state", "all",
                "--search", test_class,
                "--json", "number,state,title",
                "--repo", repo,
            )
            issues = json.loads(issues_raw)
            open_issues = [i for i in issues if i.get("state") == "OPEN"]
            if open_issues:
                # Comment on the newest open match
                newest = max(open_issues, key=lambda i: i["number"])
                _gh(
                    "issue", "comment", str(newest["number"]),
                    "--body", f"Seen in PR #{pr_number}, run {run_url}",
                    "--repo", repo,
                )
            else:
                _gh(
                    "issue", "create",
                    "--title", f"flaky: {test_class} ({module})",
                    "--label", "bug",
                    "--body", (
                        f"Seen in PR #{pr_number}, run {run_url}\n\n"
                        f"Module: `{module}`, test: `{test_class}`"
                    ),
                    "--repo", repo,
                )
        except RuntimeError:
            pass  # best-effort; still rerun

    try:
        _gh("run", "rerun", str(run_id), "--failed", "--repo", repo)
    except RuntimeError:
        return False

    _sf.mark_notified(state, rerun_key)
    return True


# ---------------------------------------------------------------------------
# Merge helpers
# ---------------------------------------------------------------------------

def _merge_pr(repo: str, pr_number: int, title: str, body: str, head_sha: str = "") -> str | None:
    """Squash-merge (or merge-commit) the PR and return the merge commit SHA.

    head_sha: the SHA the claude/verdict status was read on; passed as
    --match-head-commit so the merge is refused if the head has since moved.
    Returns None (without calling gh) if head_sha is empty.
    """
    if not head_sha:
        _log.warning("PR #%s: refusing to merge without a head SHA", pr_number)
        return None
    use_merge_commit = bool(_MERGE_COMMIT_RE.search(title) or _MERGE_COMMIT_RE.search(body))
    strategy = "--merge" if use_merge_commit else "--squash"
    cmd = [
        "pr", "merge", str(pr_number), strategy, "--delete-branch",
        "--match-head-commit", head_sha,
        "--repo", repo,
    ]
    try:
        _gh(*cmd)
    except RuntimeError:
        return None
    # Fetch the merge commit SHA.
    try:
        raw = _gh("pr", "view", str(pr_number), "--json", "mergeCommit", "--repo", repo)
        data = json.loads(raw)
        return (data.get("mergeCommit") or {}).get("oid") or ""
    except (RuntimeError, json.JSONDecodeError):
        return ""


# ---------------------------------------------------------------------------
# NEEDS_FIXES loop helpers (Step 4)
# ---------------------------------------------------------------------------

# JSON schema for Fable's verify output.
_VERIFY_SCHEMA = json.dumps({
    "type": "object",
    "required": ["confirmed", "wrong", "stale", "out_of_scope"],
    "properties": {
        "confirmed": {"type": "array", "items": {"type": "string"}},
        "wrong": {
            "type": "array",
            "items": {
                "type": "object",
                "required": ["title", "evidence"],
                "properties": {
                    "title": {"type": "string"},
                    "evidence": {"type": "string"},
                },
            },
        },
        "stale": {"type": "array", "items": {"type": "string"}},
        "out_of_scope": {"type": "array", "items": {"type": "string"}},
    },
})


_FINDINGS_CONTENT_RE = re.compile(r"^\s*(?:[-*]|\d+\.)", re.MULTILINE)


def _get_findings_comment(repo: str, pr_number: int, verdict_created_at: str) -> str | None:
    """Return the body of the latest claude[bot] comment on the PR.

    Only comments from login == "claude[bot]" (exact) are accepted; bots such
    as "github-actions[bot]" are intentionally excluded.

    The comment is only used if:
      - its updated_at is >= verdict_created_at, AND
      - its body contains at least one list-item line (has findings content).

    If the comment has no findings content, return None so the fix round is
    skipped rather than posting an empty "findings not fixed" comment.
    """
    try:
        raw = _gh("api", f"repos/{repo}/issues/{pr_number}/comments")
        comments = json.loads(raw)
        claude_comments = [
            c for c in (comments if isinstance(comments, list) else [])
            if (c.get("user") or {}).get("login") == "claude[bot]"
        ]
        if not claude_comments:
            return None
        claude_comments.sort(key=lambda c: c.get("updated_at", ""), reverse=True)
        latest = claude_comments[0]
        if verdict_created_at and (latest.get("updated_at") or "") < verdict_created_at:
            return None
        body = latest.get("body") or ""
        # Require at least one list-item line to have findings content.
        if not _FINDINGS_CONTENT_RE.search(body):
            return None
        return body
    except (RuntimeError, json.JSONDecodeError):
        return None


def _setup_worktree(
    worktree_path: Path,
    head_ref: str,
    repo_root: Path,
    *,
    _fetch_timeout: int = 60,
    _add_timeout: int = 30,
) -> bool:
    """Fetch the PR branch and create a git worktree.

    Returns True on success, False on failure.
    Removes any stale worktree at the same path before creating a new one.

    _fetch_timeout and _add_timeout are keyword-only parameters used in tests
    to trigger TimeoutExpired without mocking subprocess.run.
    """
    # Remove stale worktree if present.
    if worktree_path.exists():
        subprocess.run(
            ["git", "worktree", "remove", "--force", str(worktree_path)],
            cwd=str(repo_root), capture_output=True,
        )

    # Fetch the branch.
    try:
        r = subprocess.run(
            ["git", "fetch", "origin", head_ref],
            cwd=str(repo_root), capture_output=True, text=True, timeout=_fetch_timeout,
        )
    except subprocess.TimeoutExpired:
        return False
    if r.returncode != 0:
        return False

    # Create worktree.
    worktree_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        r = subprocess.run(
            ["git", "worktree", "add", str(worktree_path), f"origin/{head_ref}"],
            cwd=str(repo_root), capture_output=True, text=True, timeout=_add_timeout,
        )
    except subprocess.TimeoutExpired:
        return False
    return r.returncode == 0


def _remove_worktree(worktree_path: Path, repo_root: Path) -> None:
    """Remove a git worktree, ignoring errors."""
    subprocess.run(
        ["git", "worktree", "remove", "--force", str(worktree_path)],
        cwd=str(repo_root), capture_output=True,
    )


def _worktree_head_sha(worktree_path: Path) -> str:
    """Return the current HEAD SHA of the worktree."""
    r = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=str(worktree_path), capture_output=True, text=True,
    )
    return r.stdout.strip() if r.returncode == 0 else ""


def _worktree_is_clean(worktree_path: Path) -> bool:
    """Return True if the worktree has no uncommitted changes."""
    r = subprocess.run(
        ["git", "status", "--porcelain"],
        cwd=str(worktree_path), capture_output=True, text=True,
    )
    return r.returncode == 0 and r.stdout.strip() == ""


def _run_fable_verify(
    worktree_path: Path,
    findings_text: str,
    timeout: int = 600,
) -> dict | None:
    """Run Fable 5.1 in the worktree to verify findings.

    Returns {confirmed, wrong, stale, out_of_scope} or None on failure.
    """
    prompt_file = Path(__file__).parent / "prompts" / "pr-verify.md"
    system_text = prompt_file.read_text() if prompt_file.exists() else ""
    full_prompt = (
        f"{system_text}\n\n---\n\n## Findings to classify\n\n{findings_text}"
    )
    try:
        r = subprocess.run(
            [
                "claude", "-p",
                "--model", "claude-fable-5-1",
                "--output-format", "json",
                "--json-schema", _VERIFY_SCHEMA,
                "--max-turns", "20",
                "--allowedTools",
                "Read,Grep,Glob,"
                "Bash(git log:*),Bash(git diff:*),Bash(uv run pytest:*)",
                full_prompt,
            ],
            cwd=str(worktree_path),
            capture_output=True, text=True, timeout=timeout,
        )
    except (subprocess.TimeoutExpired, FileNotFoundError, OSError):
        return None

    if r.returncode != 0:
        return None

    return _parse_claude_json(r.stdout, key="confirmed")


def _run_sonnet_fix(
    worktree_path: Path,
    confirmed_findings: list[str],
    timeout: int = 1800,
) -> bool:
    """Run Sonnet 4.6 in the worktree to fix confirmed findings.

    Returns True if at least one new commit was made, False otherwise.

    Git credentials are disabled so only the script's own push can authenticate.
    On TimeoutExpired, still checks whether a commit was made before the timeout.
    """
    prompt_file = Path(__file__).parent / "prompts" / "pr-fix.md"
    system_text = prompt_file.read_text() if prompt_file.exists() else ""
    findings_block = "\n".join(f"- {f}" for f in confirmed_findings)
    full_prompt = f"{system_text}\n\n---\n\n{findings_block}"

    head_before = _worktree_head_sha(worktree_path)

    # Disable git credential prompts so only the script's own push can authenticate.
    extra_env = {
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_CONFIG_COUNT": "1",
        "GIT_CONFIG_KEY_0": "credential.helper",
        "GIT_CONFIG_VALUE_0": "",
    }
    env = {**os.environ, **extra_env}

    try:
        subprocess.run(
            [
                "claude", "-p",
                "--model", "claude-sonnet-4-6",
                "--permission-mode", "acceptEdits",
                "--max-turns", "20",
                "--allowedTools",
                "Read,Edit,Write,Glob,Grep,"
                "Bash(mvn:*),Bash(uv run:*),"
                "Bash(git add:*),Bash(git commit:*)",
                full_prompt,
            ],
            cwd=str(worktree_path),
            capture_output=True, text=True, timeout=timeout,
            env=env,
        )
    except subprocess.TimeoutExpired:
        # Timed out — but a commit may already have been made; check HEAD.
        head_after = _worktree_head_sha(worktree_path)
        return bool(head_before and head_after and head_before != head_after)
    except (FileNotFoundError, OSError):
        return False

    head_after = _worktree_head_sha(worktree_path)
    return bool(head_before and head_after and head_before != head_after)


def _post_rejected_findings_comment(
    repo: str,
    pr_number: int,
    wrong: list[dict],
    out_of_scope: list[str],
) -> None:
    """Post a PR comment listing findings that were not fixed (wrong or out-of-scope)."""
    lines: list[str] = ["### pr-deploy-agent: findings not fixed\n"]
    if wrong:
        lines.append("**Wrong** (finding is incorrect):")
        for w in wrong:
            lines.append(f"- **{w.get('title', '?')}**: {w.get('evidence', '')}")
    if out_of_scope:
        lines.append("\n**Out of scope** (valid but not in this PR's scope):")
        for title in out_of_scope:
            lines.append(f"- {title}")
    body = "\n".join(lines)
    try:
        _gh(
            "pr", "comment", str(pr_number),
            "--body", body,
            "--repo", repo,
        )
    except RuntimeError:
        pass  # best-effort


def _run_needs_fixes_round(
    pr: dict,
    verdict_created_at: str,
    repo: str,
    state: dict,
    state_dir: Path,
    repo_root: Path,
    pr_mode: str,
) -> "PRDecision":
    """Run one fix round for a NEEDS_FIXES PR.

    Returns a PRDecision with action one of:
      "fixed"      — push succeeded, round incremented
      "would-fix"  — recommend mode, no action taken
      "skip"       — round skipped for any reason (no commit, push failed, cap, etc.)
    """
    pr_number: int = pr["number"]
    title: str = pr.get("title") or ""
    head_sha: str = pr.get("headRefOid") or ""
    head_ref: str = pr.get("headRefName") or ""

    # At most one attempt per (pr, head_sha).  A new push will produce a new head_sha,
    # resetting this guard (the key is different).
    attempt_key = f"pr:{pr_number}:{head_sha}:attempt"
    if _sf.is_notified(state, attempt_key):
        return PRDecision(
            pr_number, title, head_sha, "NEEDS_FIXES", "skip",
            "already attempted this head",
        )

    rounds = _sf.get_rounds(state, pr_number)

    # Cap: 3 rounds exhausted, apply needs-input label and ping once.
    if rounds >= 3:
        key = f"pr:{pr_number}:needs-input"
        if not _sf.is_notified(state, key):
            if pr_mode == "act":
                try:
                    _gh(
                        "pr", "edit", str(pr_number),
                        "--add-label", "needs-input",
                        "--repo", repo,
                    )
                except RuntimeError:
                    pass
            _notify.notify_once(
                state, key, repo_root,
                f"[pr-deploy-agent] PR #{pr_number} needs human input",
                f"PR #{pr_number} \"{title}\" reached 3 fix rounds without READY → needs-input",
                "red",
            )
        return PRDecision(
            pr_number, title, head_sha, "NEEDS_FIXES", "skip",
            "needs-input (3 rounds exhausted)",
        )

    # Recommend mode: report what we would do but take no action.
    if pr_mode != "act":
        return PRDecision(
            pr_number, title, head_sha, "NEEDS_FIXES", "would-fix",
            f"round {rounds + 1}/3 pending (recommend mode)",
        )

    # Act mode: run a full fix round.
    # Mark this (pr, head) attempt immediately so the same SHA is not retried on the
    # next tick.  Increment the round count only when Sonnet actually commits something
    # (regardless of whether the subsequent push succeeds).
    _sf.mark_notified(state, attempt_key)

    worktree_path = state_dir / "worktrees" / f"pr-{pr_number}"

    # 1. Set up worktree.
    if not _setup_worktree(worktree_path, head_ref, repo_root):
        wt_key = f"pr:{pr_number}:{head_sha}:worktree-failed"
        _notify.notify_once(
            state, wt_key, repo_root,
            f"[pr-deploy-agent] PR #{pr_number} worktree setup failed",
            f"PR #{pr_number} \"{title}\": could not fetch/create worktree for {head_ref}",
            "red",
        )
        return PRDecision(pr_number, title, head_sha, "NEEDS_FIXES", "skip", "worktree setup failed")

    try:
        # 2. Get findings comment.
        findings_text = _get_findings_comment(repo, pr_number, verdict_created_at)
        if not findings_text:
            return PRDecision(pr_number, title, head_sha, "NEEDS_FIXES", "skip", "no findings comment")

        # 3. Run Fable verify.
        verify_result = _run_fable_verify(worktree_path, findings_text)
        if verify_result is None:
            vf_key = f"pr:{pr_number}:{head_sha}:verify-failed"
            _notify.notify_once(
                state, vf_key, repo_root,
                f"[pr-deploy-agent] PR #{pr_number} verify failed",
                f"PR #{pr_number} \"{title}\": Fable verify returned no result (round {rounds + 1})",
                "red",
            )
            return PRDecision(pr_number, title, head_sha, "NEEDS_FIXES", "skip", "verify failed")

        confirmed = verify_result.get("confirmed") or []
        wrong = verify_result.get("wrong") or []
        out_of_scope = verify_result.get("out_of_scope") or []

        if not confirmed:
            # Nothing to fix — only post comment if there is something to say.
            if wrong or out_of_scope:
                _post_rejected_findings_comment(repo, pr_number, wrong, out_of_scope)
            return PRDecision(
                pr_number, title, head_sha, "NEEDS_FIXES", "skip",
                "no confirmed findings",
            )

        # 4. Run Sonnet fix.
        committed = _run_sonnet_fix(worktree_path, confirmed)
        if not committed:
            fn_key = f"pr:{pr_number}:{head_sha}:fix-nothing"
            _notify.notify_once(
                state, fn_key, repo_root,
                f"[pr-deploy-agent] PR #{pr_number} fix produced nothing",
                f"PR #{pr_number} \"{title}\": Sonnet made no commit on round {rounds + 1}",
                "yellow",
            )
            return PRDecision(pr_number, title, head_sha, "NEEDS_FIXES", "skip", "fix produced nothing")

        # 5. Refuse if worktree is dirty after Sonnet.
        if not _worktree_is_clean(worktree_path):
            return PRDecision(pr_number, title, head_sha, "NEEDS_FIXES", "skip", "worktree dirty after fix")

        # 5a. Push guard: refuse if any new commit message contains [skip-review].
        head_after = _worktree_head_sha(worktree_path)
        skip_check = subprocess.run(
            ["git", "log", f"{head_sha}..{head_after}", "--format=%s%n%b"],
            cwd=str(worktree_path), capture_output=True, text=True,
        )
        if "[skip-review]" in (skip_check.stdout or ""):
            return PRDecision(
                pr_number, title, head_sha, "NEEDS_FIXES", "skip",
                "commit contains [skip-review]",
            )

        # 5b. Push guard: refuse if the diff deletes or renames any test file.
        diff_check = subprocess.run(
            ["git", "diff", "--name-status", f"{head_sha}..{head_after}"],
            cwd=str(worktree_path), capture_output=True, text=True,
        )
        for diff_line in (diff_check.stdout or "").splitlines():
            # D (deleted) or R (renamed) followed by the old path
            parts = diff_line.split("\t")
            status = parts[0] if parts else ""
            if status.startswith(("D", "R")) and len(parts) >= 2:
                if _TEST_PATH_RE.search(parts[1]):
                    return PRDecision(
                        pr_number, title, head_sha, "NEEDS_FIXES", "skip",
                        f"commit deletes/renames a test file: {parts[1]}",
                    )

        # 6. Push to the PR branch (never --force).
        try:
            push_result = subprocess.run(
                ["git", "push", "origin", f"HEAD:{head_ref}"],
                cwd=str(worktree_path),
                capture_output=True, text=True, timeout=60,
            )
        except subprocess.TimeoutExpired:
            pf_key = f"pr:{pr_number}:{head_sha}:push-failed"
            _notify.notify_once(
                state, pf_key, repo_root,
                f"[pr-deploy-agent] PR #{pr_number} push failed",
                f"PR #{pr_number} \"{title}\": push timed out",
                "red",
            )
            return PRDecision(pr_number, title, head_sha, "NEEDS_FIXES", "skip", "push timed out")

        if push_result.returncode != 0:
            err = push_result.stderr.strip()
            reason = "remote head moved" if ("non-fast-forward" in err or "rejected" in err) else "push failed"
            pf_key = f"pr:{pr_number}:{head_sha}:push-failed"
            _notify.notify_once(
                state, pf_key, repo_root,
                f"[pr-deploy-agent] PR #{pr_number} push failed",
                f"PR #{pr_number} \"{title}\": {reason} — {err[:120]}",
                "red",
            )
            return PRDecision(pr_number, title, head_sha, "NEEDS_FIXES", "skip", reason)

        # Push succeeded — count the round now.
        _sf.set_rounds(state, pr_number, rounds + 1)

        # 7. Post rejected-findings comment (only when there is content).
        if wrong or out_of_scope:
            _post_rejected_findings_comment(repo, pr_number, wrong, out_of_scope)

        return PRDecision(
            pr_number, title, head_sha, "NEEDS_FIXES", "fixed",
            f"round {rounds + 1}/3 pushed",
        )

    finally:
        _remove_worktree(worktree_path, repo_root)


# ---------------------------------------------------------------------------
# Operator steps
# ---------------------------------------------------------------------------

def _extract_operator_steps(body: str) -> str | None:
    """Extract the first operator/manual/after-merge/runbook section from the PR body."""
    lines = body.splitlines()
    in_section = False
    section_lines: list[str] = []
    for line in lines:
        if _OP_STEPS_HEADING_RE.match(line):
            if in_section:
                break  # another heading ended this section
            in_section = True
            section_lines.append(line)
        elif in_section:
            # Stop at the next heading
            if re.match(r"^#{1,4}\s", line):
                break
            section_lines.append(line)
    if not section_lines:
        return None
    return "\n".join(section_lines).strip() or None


def _post_operator_steps(
    repo: str,
    state: dict,
    repo_root: Path,
) -> None:
    """Post operator steps for merged PRs whose deploy run has completed.

    Also posts after 30 minutes if no deploy run is found (docs-only merges).
    """
    merged_prs = state.get("merged_prs", {})
    for pr_str, meta in list(merged_prs.items()):
        if meta.get("op_steps_posted"):
            continue
        merge_commit = meta.get("merge_commit", "")
        merged_at_str = meta.get("merged_at", "")
        title = meta.get("title", "")
        pr_number = int(pr_str)

        # Use pre-extracted op_steps_text if present; fall back to body for
        # state entries written by older versions.
        if "op_steps_text" in meta:
            steps = meta["op_steps_text"] or None
        else:
            body = meta.get("body", "")
            steps = _extract_operator_steps(body)
        # Check if enough time has passed or deploy is done.
        deploy_completed = False
        try:
            merged_at = datetime.fromisoformat(merged_at_str)
            if merged_at.tzinfo is None:
                merged_at = merged_at.replace(tzinfo=_UTC)
            elapsed = (datetime.now(tz=_UTC) - merged_at).total_seconds()
        except (ValueError, TypeError):
            elapsed = 0

        if merge_commit:
            try:
                raw = _gh(
                    "api",
                    f"repos/{repo}/actions/workflows/deploy.yml/runs"
                    f"?head_sha={merge_commit}&per_page=5",
                )
                runs_data = json.loads(raw)
                for run in runs_data.get("workflow_runs", []):
                    if run.get("status") == "completed":
                        deploy_completed = True
                        break
            except (RuntimeError, json.JSONDecodeError):
                pass

        if not deploy_completed and elapsed < 1800:  # 30 minutes
            continue

        # Post operator steps (if any).
        op_key = f"pr:{pr_number}:op-steps"
        if steps:
            _notify.notify_once(
                state, op_key, repo_root,
                f"[pr-deploy-agent] PR #{pr_number} operator steps",
                f"**{title}**\n\n{steps}",
                "yellow",
            )
        else:
            _sf.mark_notified(state, op_key)  # no steps to post; mark done

        merged_prs[pr_str]["op_steps_posted"] = True


# ---------------------------------------------------------------------------
# Main decision logic
# ---------------------------------------------------------------------------

def plan_all(
    repo: str,
    state: dict,
    repo_root: Path,
    pr_mode: str = "recommend",
    state_dir: Path | None = None,
) -> list[PRDecision]:
    """Evaluate all open PRs and return decisions.

    In act mode, merges READY/SKIPPED PRs, reruns unrelated checks, pings on issues,
    and runs the NEEDS_FIXES fix loop.
    Side-effect: posts Discord messages and updates state (caller must persist).
    """
    if state_dir is None:
        state_dir = _DEFAULT_STATE_DIR
    # Post operator steps for previously merged PRs.
    _post_operator_steps(repo, state, repo_root)

    try:
        prs = _list_prs(repo)
    except Exception as exc:
        # Use an hourly key so repeated failures produce at most one message per hour.
        hour_key = f"list-prs-failed:{datetime.now(tz=_UTC).strftime('%Y-%m-%dT%H')}"
        _notify.notify_once(
            state, hour_key, repo_root,
            "[pr-deploy-agent] list-prs failed",
            str(exc),
            "red",
        )
        return []
    decisions: list[PRDecision] = []

    for pr in prs:
        pr_number: int = pr["number"]
        title: str = pr.get("title") or ""
        body: str = pr.get("body") or ""
        head_sha: str = pr.get("headRefOid") or ""
        base: str = pr.get("baseRefName") or ""
        labels: list[str] = [lb["name"] for lb in pr.get("labels") or []]
        mergeable: str = pr.get("mergeable") or "UNKNOWN"
        merge_state: str = pr.get("mergeStateStatus") or "UNKNOWN"
        rollup: list[dict] = pr.get("statusCheckRollup") or []
        files: list[str] = [f["path"] for f in (pr.get("files") or [])]

        # Skip drafts and cross-repo forks.
        if pr.get("isDraft") or pr.get("isCrossRepository"):
            continue

        # Skip do-not-merge or needs-input labels.
        if "do-not-merge" in labels or "needs-input" in labels:
            decisions.append(PRDecision(
                pr_number, title, head_sha, "N/A", "skip",
                "label: " + next(lbl for lbl in labels if lbl in ("do-not-merge", "needs-input")),
            ))
            continue

        # Skip stacked PRs (base != main) — ping once.
        if base != "main":
            key = f"pr:{pr_number}:{head_sha}:stacked"
            _notify.notify_once(
                state, key, repo_root,
                f"[pr-deploy-agent] PR #{pr_number} is stacked (base={base})",
                f"PR #{pr_number} \"{title}\" has base `{base}` not `main` — skipped.",
                "yellow",
            )
            decisions.append(PRDecision(
                pr_number, title, head_sha, "N/A", "skip", f"base={base}",
            ))
            continue

        # Read the claude/verdict commit status.
        try:
            token, description, verdict_created_at = _read_verdict_status(repo, head_sha)
        except (RuntimeError, json.JSONDecodeError, KeyError):
            decisions.append(PRDecision(
                pr_number, title, head_sha, "PENDING", "pending", "verdict read failed",
            ))
            continue

        # Ping-once tokens — never merge.
        if token in ("NOT_REVIEWED", "BLOCKED", "NO_VERDICT"):
            key = f"pr:{pr_number}:{head_sha}:{token.lower().replace('_', '-')}"
            _notify.notify_once(
                state, key, repo_root,
                f"[pr-deploy-agent] PR #{pr_number}: {token}",
                f"PR #{pr_number} \"{title}\" ({head_sha[:8]}): {description}",
                "yellow",
            )
            decisions.append(PRDecision(
                pr_number, title, head_sha, token, "ping", description,
            ))
            continue

        # PENDING — no verdict yet, do nothing this tick.
        if token == "PENDING":
            decisions.append(PRDecision(
                pr_number, title, head_sha, token, "pending", "verdict pending",
            ))
            continue

        # NEEDS_FIXES — run one fix round per tick (Step 4).
        if token == "NEEDS_FIXES":
            decisions.append(
                _run_needs_fixes_round(
                    pr, verdict_created_at, repo, state, state_dir, repo_root, pr_mode,
                )
            )
            continue

        # READY or SKIPPED — reset round counter (previous fix rounds are done),
        # then evaluate checks and mergeability.
        _sf.reset_rounds(state, pr_number)
        check_state, failing_checks = _classify_checks(rollup)

        if check_state == "pending":
            decisions.append(PRDecision(
                pr_number, title, head_sha, token, "pending", "checks pending",
            ))
            continue

        if check_state == "failing":
            # Apply the unrelated-check rule to each failing check.
            all_unrelated = True
            any_rerun = False
            for check in failing_checks:
                if _is_unrelated_java_failure(check, files):
                    rerun = _handle_unrelated_check(
                        pr_number, check, repo, state, repo_root, pr_mode,
                    )
                    if rerun:
                        any_rerun = True
                else:
                    all_unrelated = False

            if all_unrelated and any_rerun:
                decisions.append(PRDecision(
                    pr_number, title, head_sha, token, "unrelated-rerun",
                    "unrelated check rerun",
                ))
            else:
                cf_key = f"pr:{pr_number}:{head_sha}:checks-failing"
                _notify.notify_once(
                    state, cf_key, repo_root,
                    f"[pr-deploy-agent] PR #{pr_number} checks failing",
                    f"PR #{pr_number} \"{title}\": checks failing, not merging: "
                    f"{[c.get('name') for c in failing_checks]}",
                    "yellow",
                )
                decisions.append(PRDecision(
                    pr_number, title, head_sha, token, "skip",
                    f"checks failing: {[c.get('name') for c in failing_checks]}",
                ))
            continue

        # Checks are green — evaluate mergeability.
        if mergeable == "UNKNOWN":
            decisions.append(PRDecision(
                pr_number, title, head_sha, token, "pending", "mergeable UNKNOWN",
            ))
            continue

        if mergeable != "MERGEABLE":
            key = f"pr:{pr_number}:{head_sha}:not-mergeable"
            _notify.notify_once(
                state, key, repo_root,
                f"[pr-deploy-agent] PR #{pr_number} not mergeable",
                f"PR #{pr_number} \"{title}\": mergeable={mergeable}",
                "yellow",
            )
            decisions.append(PRDecision(
                pr_number, title, head_sha, token, "skip",
                f"not mergeable: {mergeable}",
            ))
            continue

        if merge_state == "UNKNOWN":
            decisions.append(PRDecision(
                pr_number, title, head_sha, token, "pending", "mergeStateStatus UNKNOWN",
            ))
            continue

        if merge_state not in ("CLEAN", "HAS_HOOKS"):
            key = f"pr:{pr_number}:{head_sha}:dirty"
            _notify.notify_once(
                state, key, repo_root,
                f"[pr-deploy-agent] PR #{pr_number} cannot merge",
                f"PR #{pr_number} \"{title}\": mergeStateStatus={merge_state}",
                "yellow",
            )
            decisions.append(PRDecision(
                pr_number, title, head_sha, token, "skip",
                f"merge state {merge_state}",
            ))
            continue

        # All conditions met — merge or would-merge.
        if pr_mode == "act":
            merge_commit = _merge_pr(repo, pr_number, title, body, head_sha=head_sha)
            if merge_commit is not None:
                strategy = "merge-commit" if _MERGE_COMMIT_RE.search(title) or _MERGE_COMMIT_RE.search(body) else "squash"
                _notify.post(
                    repo_root,
                    f"[pr-deploy-agent] PR #{pr_number} merged",
                    f"PR #{pr_number} \"{title}\" merged ({strategy}): `{merge_commit}`",
                    "green",
                )
                # Remember for operator-steps tracking.
                # Store only extracted steps text, not the whole PR body.
                op_steps_text = _extract_operator_steps(body) or ""
                state.setdefault("merged_prs", {})[str(pr_number)] = {
                    "merge_commit": merge_commit,
                    "title": title,
                    "op_steps_text": op_steps_text,
                    "merged_at": datetime.now(tz=_UTC).isoformat(),
                    "op_steps_posted": False,
                }
                decisions.append(PRDecision(
                    pr_number, title, head_sha, token, "merge",
                    merge_commit or "merged",
                ))
            else:
                decisions.append(PRDecision(
                    pr_number, title, head_sha, token, "skip", "merge failed",
                ))
        else:
            decisions.append(PRDecision(
                pr_number, title, head_sha, token, "would-merge", "recommend mode",
            ))

    return decisions


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="PR duty — evaluate open PRs")
    parser.add_argument("--plan", action="store_true", required=True,
                        help="evaluate open PRs")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    repo = os.environ.get("REPO", _DEFAULT_REPO)
    state_dir = Path(os.environ.get("STATE_DIR", str(_DEFAULT_STATE_DIR)))
    repo_root = Path(os.environ.get("REPO_ROOT", str(Path(__file__).resolve().parents[3])))
    pr_mode = os.environ.get("PR_MODE", "recommend")

    state_path = state_dir / "state.json"
    state = _sf.load(state_path)
    _sf.prune(state)

    decisions = plan_all(repo, state, repo_root, pr_mode=pr_mode, state_dir=state_dir)
    _sf.save(state_path, state)

    if args.json:
        out = {"prs": [asdict(d) for d in decisions]}
        print(json.dumps(out))
    else:
        if not decisions:
            print("no PRs to act on")
        for d in decisions:
            print(f"PR #{d.pr_number} [{d.verdict_token}] {d.action}: {d.reason}")


if __name__ == "__main__":
    main()
