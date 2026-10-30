#!/usr/bin/env python3
"""Deploy-gate duty for pr-deploy-agent (Step 1 — no Claude call yet).

Discovers waiting deploy.yml runs, classifies their services, checks market
hours and live state, and decides SAFE or UNSAFE with reasons.

In DEPLOY_MODE=recommend (default) nothing is written to GitHub; Discord receives
one message per event (first deferral / would-approve / > 2 h wait).
In DEPLOY_MODE=act (Step 2) the script approves doubly-SAFE runs.

CLI:  python3 deploy_gate.py --decide [--json]

      --decide   evaluate all waiting deploy.yml runs and emit decisions
      --json     emit decisions as a JSON object {"runs": [...]}

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


# ---------------------------------------------------------------------------
# GitHub API helpers (all reads — no writes in Step 1)
# ---------------------------------------------------------------------------
def _gh(*args: str, timeout: int = 30) -> str:
    """Run `gh <args>` and return stdout; raise RuntimeError on failure."""
    result = subprocess.run(
        ["gh"] + list(args),
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
# Main decide loop
# ---------------------------------------------------------------------------
def decide_all(
    repo: str,
    homelab_ssh: str,
    state: dict,
    repo_root: Path,
    at: "datetime | None" = None,
) -> list[RunDecision]:
    """Evaluate all waiting deploy.yml runs and return decisions.

    Side-effect: posts Discord messages for new events (first deferral / would-approve / > 2 h)
    using the provided `state` dict for dedupe.  Caller must persist state after this returns.
    """
    runs = _list_waiting_runs(repo)
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
            # Run is no longer gated (already approved by someone, or closed).
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

        # Discord notifications (one per key, deduplicated via state).
        run_key_prefix = f"run:{run_id}"
        if decision.verdict == "safe":
            msg = (
                f"run [{run_id}](<https://github.com/{repo}/actions/runs/{run_id}>) "
                f"services: {', '.join(decision.services)}"
            )
            _notify.notify_once(state, f"{run_key_prefix}:would-approve", repo_root,
                                "[pr-deploy-agent] would approve deploy", msg, "green")
        else:
            reason_text = "\n".join(f"- {r}" for r in decision.reasons)
            msg = (
                f"run [{run_id}](<https://github.com/{repo}/actions/runs/{run_id}>) deferred\n"
                f"services: {', '.join(decision.services)}\n"
                f"{reason_text}"
            )
            _notify.notify_once(state, f"{run_key_prefix}:first-deferral", repo_root,
                                "[pr-deploy-agent] deploy deferred", msg, "yellow")

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


def main() -> None:
    parser = argparse.ArgumentParser(description="Deploy gate — evaluate waiting runs")
    parser.add_argument("--decide", action="store_true", required=True)
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

    decisions = decide_all(repo, homelab_ssh, state, repo_root)

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

    # Step 2 will add the approve call here; for now deploy_mode is informational only.
    _ = deploy_mode


if __name__ == "__main__":
    main()
