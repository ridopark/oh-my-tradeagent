#!/usr/bin/env bash
# tick.sh — pr-deploy-agent tick (called by the systemd timer every 5 minutes)
#
# Checks the kill switch, acquires a non-blocking lock, loads config, and runs
# the deploy gate (Step 1).  Exits 0 in all non-error paths so systemd does
# not spam failure notifications.
#
# Env (loaded from ~/.config/pr-deploy-agent/env; also settable directly):
#   REPO          GitHub repo slug   (default ridopark/oh-my-tradeagent)
#   HOMELAB_SSH   ssh target         (default ridopark@192.168.10.123)
#   DEPLOY_MODE   recommend|act      (default recommend)
#   PR_MODE       recommend|act      (default recommend, reserved for Step 3)
#   STATE_DIR     runtime state path (default ~/.local/state/pr-deploy-agent)
#
# Kill switch:  touch ~/.config/pr-deploy-agent/DISABLED
# Lock file:    ~/.local/state/pr-deploy-agent/lock

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export REPO_ROOT
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
LOG_TAG="[pr-deploy-agent]"

# ── Kill switch ─────────────────────────────────────────────────────────────
CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/pr-deploy-agent"
DISABLED_FILE="$CONFIG_DIR/DISABLED"
if [[ -f "$DISABLED_FILE" ]]; then
  echo "$TS $LOG_TAG kill switch set" >&2
  exit 0
fi

# ── State directory + lock ──────────────────────────────────────────────────
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/pr-deploy-agent"
mkdir -p "$STATE_DIR"

LOCK_FILE="$STATE_DIR/lock"
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo "$TS $LOG_TAG another tick holds the lock" >&2
  exit 0
fi

# ── Load env file ───────────────────────────────────────────────────────────
ENV_FILE="$CONFIG_DIR/env"
if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  set -a
  source "$ENV_FILE"
  set +a
fi

# Apply defaults after env file so the file can override them.
REPO="${REPO:-ridopark/oh-my-tradeagent}"
HOMELAB_SSH="${HOMELAB_SSH:-ridopark@192.168.10.123}"
DEPLOY_MODE="${DEPLOY_MODE:-recommend}"
PR_MODE="${PR_MODE:-recommend}"
# STATE_DIR may have been overridden by the env file; re-apply.
STATE_DIR="${STATE_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/pr-deploy-agent}"

export REPO HOMELAB_SSH DEPLOY_MODE PR_MODE STATE_DIR

echo "$TS $LOG_TAG tick start (DEPLOY_MODE=$DEPLOY_MODE)" >&2

# ── Deploy gate ─────────────────────────────────────────────────────────────
cd "$REPO_ROOT"
RESULT="$(python3 "$SCRIPT_DIR/deploy_gate.py" --decide --json 2>&1)"
GATE_EXIT=$?

echo "$TS $LOG_TAG deploy_gate exit=$GATE_EXIT result=$RESULT" >&2

if [[ "$RESULT" == '{"runs": []}' ]] || [[ "$RESULT" == '{"runs":[]}' ]]; then
  echo "$TS $LOG_TAG nothing waiting" >&2
fi

echo "$TS $LOG_TAG tick done" >&2
exit 0
