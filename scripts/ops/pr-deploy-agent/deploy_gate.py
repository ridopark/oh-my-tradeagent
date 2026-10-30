#!/usr/bin/env python3
"""Deploy-gate duty for pr-deploy-agent (Steps 1 & 2).

Discovers waiting deploy.yml runs, classifies their services, checks market
hours and live state, asks Fable 5.1 for a second opinion, and (in act mode)
approves doubly-SAFE runs via the pending_deployments API.  Also performs
post-roll verification and detects human overrides on deferred runs.

CLI:
  python3 deploy_gate.py --decide [--json]   evaluate all waiting runs
  python3 deploy_gate.py --verify [--json]   check approved-run pod health

Exit 0 always (caller logs failures; individual run errors are in the JSON).

Env:
  REPO         GitHub repo slug  (default ridopark/oh-my-tradeagent)
  HOMELAB_SSH  ssh target for live-state reads (default ridopark@192.168.10.123)
  DEPLOY_MODE  recommend|act (default recommend)
  STATE_DIR    path to state directory (default ~/.local/state/pr-deploy-agent)
  REPO_ROOT    absolute path to the repository root (set by tick.sh)
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path

import live_state as _live
import market_hours as _mh
import notify as _notify
import statefile as _sf

# ---------------------------------------------------------------------------
# Authoritative service-class table.
# Every service that can appear in a "Roll <service>" job must be listed here.
# A name absent from this dict → class "unknown" → the run is UNSAFE.
# Cross-checked against deploy.yml SERVICES variable; exec-alpaca-live must
# never appear (it is not in the deploy matrix).
# ---------------------------------------------------------------------------
SERVICE_CLASS: dict[str, str] = {
    # signal-watcher: safe only while the market is CLOSED
    "signal-source-discord": "signal-watcher",
    "discord-lotto-mirror": "signal-watcher",
    # trading-path: see is_trading_path_unsafe() below — THE ONE PLACE for that rule
    "orchestrator": "trading-path",
    "exec-alpaca-paper": "trading-path",
    "market-data": "trading-path",
    "lotto-signal": "trading-path",
    # any-time: always safe
    "dashboard": "any-time",
    "tenant-dashboard-bff": "any-time",
    "api-gateway": "any-time",
    "discord-chat-mirror": "any-time",
    "discord-signals-mirror": "any-time",
    "darkpool-collector": "any-time",
    "stc-intent-service": "any-time",
}

# Environment gate for the production-homelab GitHub Environment.
_PRODUCTION_HOMELAB_ENV_ID = 15500297208

_DEFAULT_REPO = "ridopark/oh-my-tradeagent"
_DEFAULT_HOMELAB_SSH = "ridopark@192.168.10.123"
_DEFAULT_STATE_DIR = Path.home() / ".local" / "state" / "pr-deploy-agent"

_UTC = timezone.utc

# JSON schema for Claude's structured second-opinion response.
_SECOND_OPINION_SCHEMA = json.dumps({
    "type": "object",
    "required": ["verdict", "reason"],
    "properties": {
        "verdict": {"type": "string", "enum": ["approve", "defer"]},
        "reason": {"type": "string"},
    },
})


# ---------------------------------------------------------------------------
# THE ONE RULE: when are trading-path services unsafe to roll?
#
# Operator decision 2026-10-29:
#   Market OPEN:  any running PositionWorkflow OR any non-terminal order → UNSAFE
#   Market CLOSED: only non-terminal orders block; open positions do NOT block
#
# To change this rule, edit ONLY this function.
# ---------------------------------------------------------------------------
def is_trading_path_unsafe(
    live: "_live.LiveState | _live.LiveStateError",
    market_open: bool,
) -> tuple[bool, str]:
    """Return (unsafe, reason_str).  unsafe=True means the run must be deferred.

    This is the ONE authoritative place for the trading-path safety rule.
    """
    if isinstance(live, _live.LiveStateError):
        return True, f"live-state read failed ({live.which})"

    if market_open:
        if live.position_workflows > 0:
            ids_str = ", ".join(live.position_workflow_ids[:5])
            suffix = (
                f" +{live.position_workflows - 5} more"
                if live.position_workflows > 5
                else ""
            )
            return True, (
                f"market open, {live.position_workflows} running PositionWorkflow(s): "
                f"{ids_str}{suffix}"
            )
        if live.copytrade_signal_workflows > 0:
            return True, (
                f"market open, {live.copytrade_signal_workflows} running "
                f"CopytradeSignalWorkflow(s)"
            )

    # Both market-open and market-closed paths block on non-terminal orders.
    nonterminal = live.nonterminal_paper + live.nonterminal_live
    if nonterminal:
        preview = "; ".join(
            f"{r['tenant_id']}:{r['option_symbol']}:{r['state']}"
            for r in nonterminal[:3]
        )
        suffix = f" +{len(nonterminal) - 3} more" if len(nonterminal) > 3 else ""
        return True, f"non-terminal orders: {preview}{suffix}"

    return False, ""


# ---------------------------------------------------------------------------
# Data types
# ---------------------------------------------------------------------------
@dataclass
class RunDecision:
    run_id: int
    services: list[str]
    service_classes: dict[str, str]
    verdict: str  # "safe" | "unsafe"
    reasons: list[str]
    created_at: str
    pending: bool  # production-homelab env is still waiting for approval


@dataclass
class VerificationResult:
    run_id: int
    result: str   # "healthy" | "unhealthy"
    message: str  # human-readable summary


# ---------------------------------------------------------------------------
# GitHub API helpers
# ---------------------------------------------------------------------------
def _gh(*args: str, timeout: int = 30, stdin_data: str | None = None) -> str:
    """Run `gh <args>` and return stdout; raise RuntimeError on failure."""
    result = subprocess.run(
        ["gh"] + list(args),
        input=stdin_data,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    if result.returncode != 0:
        raise RuntimeError(f"gh {args[0] if args else '?'} failed: {result.stderr.strip()[:200]}")
    return result.stdout


def _list_waiting_runs(repo: str) -> list[dict]:
    url = f"repos/{repo}/actions/workflows/deploy.yml/runs?status=waiting&per_page=20"
    raw = _gh("api", url)
    data = json.loads(raw)
    return data.get("workflow_runs", [])


def _get_run_jobs(repo: str, run_id: int) -> list[dict]:
    url = f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100"
    raw = _gh("api", url)
    data = json.loads(raw)
    return data.get("jobs", [])


def _get_pending_deployments(repo: str, run_id: int) -> list[dict]:
    url = f"repos/{repo}/actions/runs/{run_id}/pending_deployments"
    raw = _gh("api", url)
    return json.loads(raw)


def _get_run_approvals(repo: str, run_id: int) -> list[dict]:
    url = f"repos/{repo}/actions/runs/{run_id}/approvals"
    raw = _gh("api", url)
    data = json.loads(raw)
    return data if isinstance(data, list) else []


def _get_run_info(repo: str, run_id: int) -> dict:
    url = f"repos/{repo}/actions/runs/{run_id}"
    raw = _gh("api", url)
    return json.loads(raw)


def _approve_run(repo: str, run_id: int, comment: str) -> None:
    """POST an approval to the production-homelab environment for this run."""
    payload = json.dumps({
        "environment_ids": [_PRODUCTION_HOMELAB_ENV_ID],
        "state": "approved",
        "comment": comment,
    })
    _gh(
        "api", "-X", "POST",
        f"repos/{repo}/actions/runs/{run_id}/pending_deployments",
        "--input", "-",
        stdin_data=payload,
    )


# ---------------------------------------------------------------------------
# Claude second-opinion call
# ---------------------------------------------------------------------------
def _call_claude_second_opinion(bundle: dict, timeout: int = 120) -> tuple[str, str]:
    """Ask Fable 5.1 for a second opinion on a run the script assessed as SAFE.

    Returns (verdict, reason) where verdict is "approve" or "defer".
    Rule: Claude can only TIGHTEN (safe → defer), never loosen (unsafe → approve).
    A Claude failure (non-zero exit, bad JSON, timeout) returns ("defer", <error>).
    This function should only be called when the script verdict is "safe".
    """
    prompt_file = Path(__file__).parent / "prompts" / "deploy-second-opinion.md"
    system_prompt = ""
    if prompt_file.exists():
        system_prompt = prompt_file.read_text()

    user_prompt = (
        f"{system_prompt}\n\n---\n\nDecision bundle:\n{json.dumps(bundle, indent=2)}"
    )

    try:
        result = subprocess.run(
            [
                "claude", "-p",
                "--model", "claude-fable-5-1",
                "--output-format", "json",
                "--json-schema", _SECOND_OPINION_SCHEMA,
                user_prompt,
            ],
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        return "defer", "claude second-opinion timeout"
    except FileNotFoundError:
        return "defer", "claude not found on PATH"

    if result.returncode != 0:
        return "defer", f"claude exit {result.returncode}: {result.stderr.strip()[:100]}"

    try:
        data = json.loads(result.stdout)
        # Handle three possible shapes:
        # 1. Direct {"verdict": ..., "reason": ...}  — fake claude in tests
        # 2. Real claude wrapper with "structured_output" key (--json-schema)
        # 3. Real claude wrapper with "result" key (JSON-encoded string)
        if not isinstance(data, dict):
            return "defer", f"claude output not a dict: {result.stdout[:100]}"

        if "verdict" in data:
            # Shape 1: direct structured output (test fakes use this)
            claude_verdict = str(data["verdict"])
            reason = str(data.get("reason", ""))
        elif "structured_output" in data and isinstance(data.get("structured_output"), dict):
            # Shape 2: real claude -p with --json-schema
            so = data["structured_output"]
            claude_verdict = str(so.get("verdict", "defer"))
            reason = str(so.get("reason", ""))
        elif "result" in data:
            # Shape 3: real claude -p without --json-schema, result is a JSON string
            raw_result = data["result"]
            inner = json.loads(raw_result) if isinstance(raw_result, str) else raw_result
            if isinstance(inner, dict) and "verdict" in inner:
                claude_verdict = str(inner["verdict"])
                reason = str(inner.get("reason", ""))
            else:
                return "defer", f"claude result field not parseable: {str(raw_result)[:100]}"
        else:
            return "defer", f"claude unexpected output: {result.stdout[:150]}"
    except (json.JSONDecodeError, TypeError) as exc:
        return "defer", f"claude bad JSON ({exc}): {result.stdout[:100]}"

    if claude_verdict not in ("approve", "defer"):
        return "defer", f"claude unknown verdict: {claude_verdict!r}"

    return claude_verdict, reason


# ---------------------------------------------------------------------------
# Decision logic
# ---------------------------------------------------------------------------
def _classify_services(jobs: list[dict]) -> dict[str, str]:
    """Extract {service: class} from waiting Roll jobs."""
    classes: dict[str, str] = {}
    for job in jobs:
        name: str = job.get("name", "")
        status: str = job.get("status", "")
        if not name.startswith("Roll ") or status != "waiting":
            continue
        svc = name[len("Roll "):].strip()
        classes[svc] = SERVICE_CLASS.get(svc, "unknown")
    return classes


def _is_gated(pending: list[dict]) -> bool:
    """Return True if production-homelab env is still awaiting our approval."""
    for env in pending:
        if env.get("id") == _PRODUCTION_HOMELAB_ENV_ID:
            return env.get("current_user_can_approve", False)
    return False


def decide_run(
    run: dict,
    jobs: list[dict],
    live: "_live.LiveState | _live.LiveStateError | None",
    at: "datetime | None" = None,
) -> RunDecision:
    """Compute a RunDecision for one waiting run.

    `live` is None when no trading-path services are in the run (no ssh call made).
    `at`  is the reference time for market-hours checks (default: now).
    """
    run_id: int = run["id"]
    created_at: str = run.get("created_at", "")
    service_classes = _classify_services(jobs)
    services = list(service_classes.keys())
    reasons: list[str] = []
    market_open = _mh.is_market_open(at)

    for svc, cls in service_classes.items():
        if cls == "unknown":
            reasons.append(f"unknown service: {svc}")
        elif cls == "signal-watcher" and market_open:
            reasons.append(f"market open, signal-watcher service: {svc}")
        elif cls == "trading-path":
            assert live is not None, "live state must be provided for trading-path runs"
            unsafe, reason = is_trading_path_unsafe(live, market_open)
            if unsafe:
                reasons.append(reason)
                break  # one reason per run is enough; all services share the same live state

    verdict = "safe" if not reasons else "unsafe"
    return RunDecision(
        run_id=run_id,
        services=services,
        service_classes=service_classes,
        verdict=verdict,
        reasons=reasons,
        created_at=created_at,
        pending=True,
    )


def _needs_live_state(service_classes: dict[str, str]) -> bool:
    return any(cls == "trading-path" for cls in service_classes.values())


# ---------------------------------------------------------------------------
# Human override detection
# ---------------------------------------------------------------------------
def _check_human_overrides(
    repo: str,
    state: dict,
    repo_root: Path,
) -> None:
    """Check runs the agent deferred for a human approval.

    A human approval has state=='approved' but comment not starting with
    'pr-deploy-agent:'.  Posts one Discord message per run, then records the key.
    """
    deferred = state.get("deferred_run_ids", [])
    for run_id in deferred:
        key = f"run:{run_id}:human-override"
        if _sf.is_notified(state, key):
            continue
        try:
            approvals = _get_run_approvals(repo, run_id)
        except RuntimeError:
            continue  # can't read → skip silently
        for appr in approvals:
            if appr.get("state") == "approved":
                comment = appr.get("comment") or ""
                if not comment.startswith("pr-deploy-agent:"):
                    user = (appr.get("user") or {}).get("login", "?")
                    _notify.notify_once(
                        state, key, repo_root,
                        "[pr-deploy-agent] deploy approved by human (override)",
                        (
                            f"run [{run_id}](<https://github.com/{repo}/actions/runs/{run_id}>)"
                            f" approved by {user}; agent stepping aside"
                        ),
                        "green",
                    )
                    break


# ---------------------------------------------------------------------------
# Main decide loop
# ---------------------------------------------------------------------------
def decide_all(
    repo: str,
    homelab_ssh: str,
    state: dict,
    repo_root: Path,
    deploy_mode: str = "recommend",
    at: "datetime | None" = None,
) -> list[RunDecision]:
    """Evaluate all waiting deploy.yml runs and return decisions.

    In act mode, doubly-SAFE runs (script + Claude) are approved via GitHub API.
    Side-effect: posts Discord messages and updates state (caller must persist).
    """
    runs = _list_waiting_runs(repo)

    # Always check human overrides even when no runs are waiting.
    _check_human_overrides(repo, state, repo_root)

    if not runs:
        return []

    # Sort oldest-first so we evaluate the most-stale run first.
    runs.sort(key=lambda r: r.get("created_at", ""))

    decisions: list[RunDecision] = []
    live_cache: "_live.LiveState | _live.LiveStateError | None" = None
    live_fetched = False

    for run in runs:
        run_id = run["id"]

        try:
            jobs = _get_run_jobs(repo, run_id)
            pending = _get_pending_deployments(repo, run_id)
        except RuntimeError as exc:
            _notify.post(repo_root, f"[pr-deploy-agent] run {run_id} read error", str(exc), "red")
            continue

        if not _is_gated(pending):
            continue

        service_classes = _classify_services(jobs)

        # Fetch live state once per tick if any trading-path service is present.
        live: "_live.LiveState | _live.LiveStateError | None" = None
        if _needs_live_state(service_classes):
            if not live_fetched:
                live_cache = _live.read(homelab_ssh=homelab_ssh)
                live_fetched = True
            live = live_cache

        decision = decide_run(run, jobs, live, at=at)
        decisions.append(decision)

        run_key_prefix = f"run:{run_id}"

        if decision.verdict == "safe":
            if deploy_mode == "act":
                # Claude second opinion — only called in act mode (before approving).
                live_summary = _live_summary(live)
                market_open = _mh.is_market_open(at)
                bundle = {
                    "run_id": run_id,
                    "services": decision.services,
                    "service_classes": decision.service_classes,
                    "market_open": market_open,
                    "live_state_summary": live_summary,
                    "script_verdict": "safe",
                    "script_reasons": decision.reasons,
                }
                claude_verdict, claude_reason = _call_claude_second_opinion(bundle)

                if claude_verdict == "approve":
                    # Both script and Claude agree: safe to roll — approve.
                    comment = f"pr-deploy-agent: {claude_reason or 'safe'}"
                    try:
                        _approve_run(repo, run_id, comment)
                        # Record approved run in state for post-roll verification.
                        state.setdefault("approved_runs", {})[str(run_id)] = {
                            "services": decision.services,
                            "approved_at": datetime.now(tz=_UTC).isoformat(),
                            "comment": comment,
                        }
                        _notify.post(
                            repo_root,
                            "[pr-deploy-agent] deploy approved",
                            (
                                f"run [{run_id}](<https://github.com/{repo}/actions/runs/{run_id}>) "
                                f"approved: {', '.join(decision.services)}\n{comment}"
                            ),
                            "green",
                        )
                    except RuntimeError as exc:
                        _notify.post(
                            repo_root, "[pr-deploy-agent] approve failed",
                            f"run {run_id}: {exc}", "red",
                        )
                else:
                    # Claude deferred a script-safe run: treat as deferred.
                    decision.verdict = "unsafe"
                    decision.reasons = [f"claude deferred: {claude_reason}"]
                    reason_text = f"- claude deferred: {claude_reason}"
                    msg = (
                        f"run [{run_id}](<https://github.com/{repo}/actions/runs/{run_id}>) "
                        f"deferred by Claude\n"
                        f"services: {', '.join(decision.services)}\n"
                        f"{reason_text}"
                    )
                    _notify.notify_once(
                        state, f"{run_key_prefix}:first-deferral", repo_root,
                        "[pr-deploy-agent] deploy deferred (Claude)", msg, "yellow",
                    )
                    _record_deferred(state, run_id)
            else:
                # recommend mode: post would-approve message; no Claude call needed.
                msg = (
                    f"run [{run_id}](<https://github.com/{repo}/actions/runs/{run_id}>) "
                    f"services: {', '.join(decision.services)}"
                )
                _notify.notify_once(
                    state, f"{run_key_prefix}:would-approve", repo_root,
                    "[pr-deploy-agent] would approve deploy", msg, "green",
                )

        else:
            # Script says unsafe.
            reason_text = "\n".join(f"- {r}" for r in decision.reasons)
            msg = (
                f"run [{run_id}](<https://github.com/{repo}/actions/runs/{run_id}>) deferred\n"
                f"services: {', '.join(decision.services)}\n"
                f"{reason_text}"
            )
            _notify.notify_once(
                state, f"{run_key_prefix}:first-deferral", repo_root,
                "[pr-deploy-agent] deploy deferred", msg, "yellow",
            )
            _record_deferred(state, run_id)

            # > 2 h wait notification
            try:
                created = datetime.fromisoformat(decision.created_at.replace("Z", "+00:00"))
                now = at or datetime.now(tz=_UTC)
                if now.tzinfo is None:
                    now = now.replace(tzinfo=_UTC)
                wait_h = (now - created).total_seconds() / 3600
                if wait_h >= 2:
                    _notify.notify_once(
                        state, f"{run_key_prefix}:waited-2h", repo_root,
                        "[pr-deploy-agent] deploy waiting > 2h",
                        f"run [{run_id}](<https://github.com/{repo}/actions/runs/{run_id}>) "
                        f"has been waiting {wait_h:.1f} h\n{reason_text}",
                        "yellow",
                    )
            except (ValueError, TypeError):
                pass

    return decisions


def _live_summary(live: "_live.LiveState | _live.LiveStateError | None") -> str:
    """Return a short string summarising live state for the Claude bundle."""
    if live is None:
        return "not read (no trading-path service)"
    if isinstance(live, _live.LiveStateError):
        return f"read failed: {live.which}"
    return (
        f"position_workflows={live.position_workflows}, "
        f"copytrade_signal_workflows={live.copytrade_signal_workflows}, "
        f"nonterminal_paper={len(live.nonterminal_paper)}, "
        f"nonterminal_live={len(live.nonterminal_live)}"
    )


def _record_deferred(state: dict, run_id: int) -> None:
    """Add run_id to state's deferred_run_ids list (idempotent)."""
    deferred = state.setdefault("deferred_run_ids", [])
    if run_id not in deferred:
        deferred.append(run_id)


# ---------------------------------------------------------------------------
# Post-roll verification
# ---------------------------------------------------------------------------
def _ssh_cmd(homelab_ssh: str, remote_cmd: str, timeout: int = 30) -> str:
    """Run a command on the homelab; raise RuntimeError on failure."""
    result = subprocess.run(
        ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", homelab_ssh, remote_cmd],
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    if result.returncode != 0:
        raise RuntimeError(f"ssh exit {result.returncode}: {result.stderr.strip()[:200]}")
    return result.stdout


def _verify_service(
    svc: str,
    homelab_ssh: str,
    repo_root: Path,
    copytrade_ns: str = "copytrade",
) -> tuple[bool, str]:
    """Check one deployed service.  Returns (healthy, reason)."""
    # 1. Deployment readiness
    try:
        deploy_json = _ssh_cmd(
            homelab_ssh,
            f"kubectl -n {copytrade_ns} get deploy {svc} -o json",
        )
        deploy = json.loads(deploy_json)
    except (RuntimeError, json.JSONDecodeError) as exc:
        return False, f"get deploy {svc} failed: {exc}"

    status = deploy.get("status", {})
    ready = status.get("readyReplicas", 0) or 0
    desired = status.get("replicas", 1) or 1
    if ready < desired:
        return False, f"{svc}: readyReplicas {ready} < replicas {desired}"

    # 2. Pod restart counts and image ID
    try:
        pods_json = _ssh_cmd(
            homelab_ssh,
            f"kubectl -n {copytrade_ns} get pods -l app={svc} -o json",
        )
        pods_data = json.loads(pods_json)
    except (RuntimeError, json.JSONDecodeError) as exc:
        return False, f"get pods {svc} failed: {exc}"

    image_id: str = ""
    for pod in pods_data.get("items", []):
        for cs in pod.get("status", {}).get("containerStatuses", []):
            restarts = cs.get("restartCount", 0) or 0
            if restarts > 0:
                return False, f"{svc}: pod restart count {restarts}"
            if not image_id:
                image_id = cs.get("imageID", "")

    # 3. Image currency check
    if image_id:
        check_script = repo_root / "scripts" / "ops" / "check_image_current.py"
        try:
            proc = subprocess.run(
                ["python3", str(check_script), image_id],
                capture_output=True,
                text=True,
                timeout=60,
            )
            if proc.returncode != 0:
                return False, f"{svc}: image STALE (check_image_current exit {proc.returncode})"
        except Exception as exc:
            return False, f"{svc}: image check failed: {exc}"

    return True, ""


def verify_all(
    repo: str,
    homelab_ssh: str,
    state: dict,
    repo_root: Path,
) -> list[VerificationResult]:
    """Check post-roll health for runs the agent approved.

    Reads approved_runs from state; for each run that is now completed, checks
    pod readiness, restart counts, and image currency.  Posts one Discord message
    per run; marks the run as verified so it is not re-checked.
    """
    approved = state.get("approved_runs", {})
    verified = set(state.get("verified_run_ids", []))
    results: list[VerificationResult] = []

    for run_id_str, run_meta in list(approved.items()):
        run_id = int(run_id_str)
        if run_id in verified:
            continue

        # Check if the run has completed.
        try:
            run_info = _get_run_info(repo, run_id)
        except RuntimeError:
            continue  # can't read — check next tick

        status = run_info.get("status", "")
        if status != "completed":
            continue  # still running

        conclusion = run_info.get("conclusion", "")
        services = run_meta.get("services", [])

        if conclusion != "success":
            msg = f"run {run_id} concluded {conclusion!r}, NO rollback"
            _notify.post(repo_root, "[pr-deploy-agent] roll FAILED", msg, "red")
            vr = VerificationResult(run_id=run_id, result="unhealthy", message=msg)
        else:
            # Check each service's pods.
            all_healthy = True
            unhealthy_reasons: list[str] = []
            for svc in services:
                healthy, reason = _verify_service(svc, homelab_ssh, repo_root)
                if not healthy:
                    all_healthy = False
                    unhealthy_reasons.append(reason)

            if all_healthy:
                msg = (
                    f"run {run_id} verified: "
                    f"{', '.join(services)} pods ready, image CURRENT, 0 restarts"
                )
                _notify.post(repo_root, "[pr-deploy-agent] roll verified", msg, "green")
                vr = VerificationResult(run_id=run_id, result="healthy", message=msg)
            else:
                msg = (
                    f"run {run_id} pods not healthy after roll, NO rollback: "
                    + "; ".join(unhealthy_reasons)
                )
                _notify.post(repo_root, "[pr-deploy-agent] roll UNHEALTHY", msg, "red")
                vr = VerificationResult(run_id=run_id, result="unhealthy", message=msg)

        results.append(vr)
        verified.add(run_id)

    state["verified_run_ids"] = list(verified)
    return results


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------
def main() -> None:
    parser = argparse.ArgumentParser(description="Deploy gate — evaluate waiting runs")
    mode_group = parser.add_mutually_exclusive_group(required=True)
    mode_group.add_argument("--decide", action="store_true",
                            help="evaluate waiting runs")
    mode_group.add_argument("--verify", action="store_true",
                            help="check post-roll health for approved runs")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    repo = os.environ.get("REPO", _DEFAULT_REPO)
    homelab_ssh = os.environ.get("HOMELAB_SSH", _DEFAULT_HOMELAB_SSH)
    state_dir = Path(os.environ.get("STATE_DIR", str(_DEFAULT_STATE_DIR)))
    repo_root = Path(os.environ.get("REPO_ROOT", str(Path(__file__).resolve().parents[3])))
    deploy_mode = os.environ.get("DEPLOY_MODE", "recommend")

    state_path = state_dir / "state.json"
    state = _sf.load(state_path)
    _sf.prune(state)

    if args.decide:
        decisions = decide_all(repo, homelab_ssh, state, repo_root, deploy_mode=deploy_mode)
        _sf.save(state_path, state)

        if args.json:
            out = {"runs": [asdict(d) for d in decisions]}
            print(json.dumps(out))
        else:
            if not decisions:
                print("nothing waiting")
            for d in decisions:
                status = "SAFE" if d.verdict == "safe" else "UNSAFE"
                print(f"run {d.run_id}: {status} {d.services} {d.reasons}")

    elif args.verify:
        verifications = verify_all(repo, homelab_ssh, state, repo_root)
        _sf.save(state_path, state)

        if args.json:
            out = {
                "verifications": [
                    {"run_id": v.run_id, "result": v.result, "message": v.message}
                    for v in verifications
                ]
            }
            print(json.dumps(out))
        else:
            if not verifications:
                print("nothing to verify")
            for v in verifications:
                print(f"run {v.run_id}: {v.result} — {v.message}")


if __name__ == "__main__":
    main()
