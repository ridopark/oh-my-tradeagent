#!/usr/bin/env bash
# tick.sh — pr-deploy-agent tick (called by the systemd timer every 5 minutes)
#
# Checks the kill switch, acquires a non-blocking lock, loads config, runs
# the deploy gate (--decide), checks post-roll verification (--verify), and
# runs the PR duty (--plan).
#
# Exits 0 in all non-error paths so systemd does not spam failure notifications.
# A failing python duty does NOT abort the tick — all three duties always run.
#
# Env (loaded from ~/.config/pr-deploy-agent/env; also settable directly):
#   REPO          GitHub repo slug   (default ridopark/oh-my-tradeagent)
#   HOMELAB_SSH   ssh target         (default ridopark@192.168.10.123)
#   DEPLOY_MODE   recommend|act      (default recommend)
#   PR_MODE       recommend|act      (default recommend, reserved for Step 3)
#   STATE_DIR     runtime state path (default ~/.local/state/pr-deploy-agent)
#
# Kill switch:  touch ~/.config/pr-deploy-agent/DISABLED
# Lock file:    $STATE_DIR/lock

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

# ── Load env file (BEFORE acquiring the lock so STATE_DIR can be overridden) ─
ENV_FILE="$CONFIG_DIR/env"
if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  set -a
  source "$ENV_FILE"
  set +a
fi

# Apply defaults after env file so the file (and environment) can override them.
# Don't overwrite STATE_DIR if it was already set before we started.
REPO="${REPO:-ridopark/oh-my-tradeagent}"
HOMELAB_SSH="${HOMELAB_SSH:-ridopark@192.168.10.123}"
DEPLOY_MODE="${DEPLOY_MODE:-recommend}"
PR_MODE="${PR_MODE:-recommend}"
STATE_DIR="${STATE_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/pr-deploy-agent}"

export REPO HOMELAB_SSH DEPLOY_MODE PR_MODE STATE_DIR

# ── State directory + lock ──────────────────────────────────────────────────
mkdir -p "$STATE_DIR"
LOCK_FILE="$STATE_DIR/lock"
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo "$TS $LOG_TAG another tick holds the lock" >&2
  exit 0
fi

echo "$TS $LOG_TAG tick start (DEPLOY_MODE=$DEPLOY_MODE)" >&2

# ── Deploy gate — decide ────────────────────────────────────────────────────
# Run each duty in a subshell that captures stdout/stderr separately.
# Use || to prevent set -e from aborting the tick on python failure.
cd "$REPO_ROOT"
_DECIDE_STDERR_FILE="$(mktemp)"
DECIDE_STDOUT="" DECIDE_EXIT=0
DECIDE_STDOUT="$(python3 "$SCRIPT_DIR/deploy_gate.py" --decide --json \
  2>"$_DECIDE_STDERR_FILE" 9>&-)" || DECIDE_EXIT=$?
DECIDE_STDERR="$(cat "$_DECIDE_STDERR_FILE")"
rm -f "$_DECIDE_STDERR_FILE"

echo "$TS $LOG_TAG deploy_gate --decide exit=$DECIDE_EXIT" >&2
[[ -n "$DECIDE_STDERR" ]] && echo "$TS $LOG_TAG decide stderr: $DECIDE_STDERR" >&2
echo "$TS $LOG_TAG decide stdout: $DECIDE_STDOUT" >&2

if [[ "$DECIDE_STDOUT" == '{"runs": []}' ]] || [[ "$DECIDE_STDOUT" == '{"runs":[]}' ]]; then
  echo "$TS $LOG_TAG nothing waiting" >&2
fi

# ── Deploy gate — verify ─────────────────────────────────────────────────────
_VERIFY_STDERR_FILE="$(mktemp)"
VERIFY_STDOUT="" VERIFY_EXIT=0
VERIFY_STDOUT="$(python3 "$SCRIPT_DIR/deploy_gate.py" --verify --json \
  2>"$_VERIFY_STDERR_FILE" 9>&-)" || VERIFY_EXIT=$?
VERIFY_STDERR="$(cat "$_VERIFY_STDERR_FILE")"
rm -f "$_VERIFY_STDERR_FILE"

echo "$TS $LOG_TAG deploy_gate --verify exit=$VERIFY_EXIT" >&2
[[ -n "$VERIFY_STDERR" ]] && echo "$TS $LOG_TAG verify stderr: $VERIFY_STDERR" >&2

if [[ "$VERIFY_STDOUT" == '{"verifications": []}' ]] || \
   [[ "$VERIFY_STDOUT" == '{"verifications":[]}' ]]; then
  echo "$TS $LOG_TAG nothing to verify" >&2
fi

# ── PR duty ─────────────────────────────────────────────────────────────────
_PR_STDERR_FILE="$(mktemp)"
PR_STDOUT="" PR_EXIT=0
PR_STDOUT="$(python3 "$SCRIPT_DIR/pr_duty.py" --plan --json \
  2>"$_PR_STDERR_FILE" 9>&-)" || PR_EXIT=$?
PR_STDERR="$(cat "$_PR_STDERR_FILE")"
rm -f "$_PR_STDERR_FILE"

echo "$TS $LOG_TAG pr_duty --plan exit=$PR_EXIT" >&2
[[ -n "$PR_STDERR" ]] && echo "$TS $LOG_TAG pr stderr: $PR_STDERR" >&2

if [[ "$PR_STDOUT" == '{"prs": []}' ]] || [[ "$PR_STDOUT" == '{"prs":[]}' ]]; then
  echo "$TS $LOG_TAG no PRs to act on" >&2
fi

echo "$TS $LOG_TAG tick done" >&2
exit 0
