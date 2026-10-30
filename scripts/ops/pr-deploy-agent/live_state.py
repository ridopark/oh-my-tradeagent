#!/usr/bin/env python3
"""Read live trading state from the homelab for pr-deploy-agent.

Reads (in order):
  1. Running PositionWorkflow count via temporal-admintools pod
  2. Running CopytradeSignalWorkflow count via temporal-admintools pod
  3. Running PositionWorkflow IDs (only when count > 0, for the deferral message)
  4. Non-terminal order_intent_journal rows in exec_alpaca_paper (RECORDED/SUBMITTED, last 48 h)
  5. Non-terminal order_intent_journal rows in exec_alpaca_live  (RECORDED/SUBMITTED, last 48 h)

Fail-closed: any non-zero ssh exit, timeout, or unparsable output → LiveStateError.
The deploy gate maps LiveStateError to UNSAFE.

Called only when a run contains a trading-path service; never called for any-time-only runs.

CLI:  python3 live_state.py [--json]
      Exit 0: prints JSON (see LiveState fields).
      Exit 1: live-state read failed; JSON contains {"error": "<which>"}.

Env:  HOMELAB_SSH   (default ridopark@192.168.10.123)
      COPYTRADE_NS  (default copytrade)
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path

_DEFAULT_HOMELAB_SSH = "ridopark@192.168.10.123"
_DEFAULT_NS = "copytrade"
_TEMPORAL_NS = "copytrade"
_TEMPORAL_POD_NS = "temporal"  # k8s namespace that hosts temporal-admintools

# 48-hour window for non-terminal order rows (operator decision 2026-10-29, open question 3).
_NON_TERMINAL_WINDOW_H = 48

_NON_TERMINAL_QUERY = """\
SELECT tenant_id, option_symbol, side, state, last_state_at
FROM order_intent_journal
WHERE state IN ('RECORDED','SUBMITTED')
  AND last_state_at > now() - interval '{window} hours';
""".format(
    window=_NON_TERMINAL_WINDOW_H
)


@dataclass
class LiveState:
    position_workflows: int = 0
    copytrade_signal_workflows: int = 0
    position_workflow_ids: list[str] = field(default_factory=list)
    nonterminal_paper: list[dict] = field(default_factory=list)
    nonterminal_live: list[dict] = field(default_factory=list)


@dataclass
class LiveStateError:
    which: str  # short description of what failed


def _run_ssh(host: str, remote_cmd: str, stdin: str = "", timeout: int = 30) -> str:
    """Run a command on the homelab via ssh; raise RuntimeError on failure."""
    result = subprocess.run(
        ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", host, remote_cmd],
        input=stdin,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"ssh exit {result.returncode}: {result.stderr.strip()[:200]}"
        )
    return result.stdout


def _temporal_count(host: str, workflow_type: str, timeout: int) -> int:
    """Return running workflow count; raise on error."""
    query = f'WorkflowType="{workflow_type}" AND ExecutionStatus="Running"'
    remote_cmd = (
        f"kubectl -n {_TEMPORAL_POD_NS} exec deployment/temporal-admintools -- "
        f"temporal workflow count --namespace {_TEMPORAL_NS} --query '{query}'"
    )
    out = _run_ssh(host, remote_cmd, timeout=timeout)
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("Total:"):
            return int(line.split(":", 1)[1].strip())
    raise RuntimeError(f"could not parse temporal count output: {out!r}")


def _temporal_list_ids(host: str, workflow_type: str, timeout: int) -> list[str]:
    """Return list of workflow IDs for running workflows of the given type."""
    query = f'WorkflowType="{workflow_type}" AND ExecutionStatus="Running"'
    remote_cmd = (
        f"kubectl -n {_TEMPORAL_POD_NS} exec deployment/temporal-admintools -- "
        f"temporal workflow list --namespace {_TEMPORAL_NS} --query '{query}' -o json"
    )
    try:
        out = _run_ssh(host, remote_cmd, timeout=timeout)
        rows = json.loads(out)
        if isinstance(rows, list):
            ids = []
            for r in rows:
                if not isinstance(r, dict):
                    continue
                # Temporal CLI 1.7+ nests the id under execution.workflowId
                wid = (
                    r.get("workflowId")
                    or (r.get("execution") or {}).get("workflowId")
                    or r.get("id")
                    or ""
                )
                if wid:
                    ids.append(wid)
            return ids
    except Exception:
        pass
    return []


def _psql_nonterminal(host: str, db: str, ns: str, timeout: int) -> list[dict]:
    """Return non-terminal order rows for db; raise on error."""
    remote_cmd = (
        f"kubectl -n {ns} exec -i postgres-0 -- "
        f"psql -U temporal -d {db} -tAF'|' -v ON_ERROR_STOP=1 -f -"
    )
    result = subprocess.run(
        ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", host, remote_cmd],
        input=_NON_TERMINAL_QUERY,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"psql {db} exit {result.returncode}: {result.stderr.strip()[:200]}"
        )
    rows = []
    for line in result.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split("|")
        if len(parts) >= 5:
            rows.append(
                {
                    "tenant_id": parts[0],
                    "option_symbol": parts[1],
                    "side": parts[2],
                    "state": parts[3],
                    "last_state_at": parts[4],
                }
            )
    return rows


def read(homelab_ssh: str = _DEFAULT_HOMELAB_SSH, timeout: int = 30) -> LiveState | LiveStateError:
    """Read full live state; return LiveStateError on any failure."""
    host = homelab_ssh
    ns = _DEFAULT_NS

    # 1. PositionWorkflow count
    try:
        pos_count = _temporal_count(host, "PositionWorkflow", timeout)
    except Exception as exc:
        return LiveStateError(which=f"temporal PositionWorkflow count: {exc}")

    # 2. CopytradeSignalWorkflow count
    try:
        sig_count = _temporal_count(host, "CopytradeSignalWorkflow", timeout)
    except Exception as exc:
        return LiveStateError(which=f"temporal CopytradeSignalWorkflow count: {exc}")

    # 3. PositionWorkflow IDs (only when count > 0)
    ids: list[str] = []
    if pos_count > 0:
        try:
            ids = _temporal_list_ids(host, "PositionWorkflow", timeout)
        except Exception:
            ids = []  # non-fatal; count already known

    # 4 & 5. Non-terminal orders in both exec DBs
    try:
        paper_rows = _psql_nonterminal(host, "exec_alpaca_paper", ns, timeout)
    except Exception as exc:
        return LiveStateError(which=f"psql exec_alpaca_paper: {exc}")

    try:
        live_rows = _psql_nonterminal(host, "exec_alpaca_live", ns, timeout)
    except Exception as exc:
        return LiveStateError(which=f"psql exec_alpaca_live: {exc}")

    return LiveState(
        position_workflows=pos_count,
        copytrade_signal_workflows=sig_count,
        position_workflow_ids=ids,
        nonterminal_paper=paper_rows,
        nonterminal_live=live_rows,
    )


def main() -> None:
    import os
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    host = os.environ.get("HOMELAB_SSH", _DEFAULT_HOMELAB_SSH)
    result = read(homelab_ssh=host)

    if isinstance(result, LiveStateError):
        payload = {"error": result.which}
        if args.json:
            print(json.dumps(payload))
        else:
            print(f"ERROR: {result.which}", file=sys.stderr)
        sys.exit(1)

    payload = asdict(result)
    if args.json:
        print(json.dumps(payload))
    else:
        print(f"position_workflows:          {result.position_workflows}")
        print(f"copytrade_signal_workflows:  {result.copytrade_signal_workflows}")
        print(f"nonterminal_paper rows:      {len(result.nonterminal_paper)}")
        print(f"nonterminal_live rows:       {len(result.nonterminal_live)}")


if __name__ == "__main__":
    main()
