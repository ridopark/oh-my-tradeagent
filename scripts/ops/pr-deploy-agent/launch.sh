#!/usr/bin/env bash
# launch.sh — pr-deploy-agent launcher
#
# Installed outside the clone at ~/.local/share/pr-deploy-agent/launch.sh
# by the operator after merge (see README "Migrating from the operator checkout").
# Tracked in the repo at scripts/ops/pr-deploy-agent/launch.sh.
#
# Every tick: holds a non-blocking lock (fd 8, inherited across exec so the
# lock covers the update + the whole tick), bootstraps the clone if missing,
# checks the kill switch, fast-forwards a clean main clone, and exec-s the
# clone's tick.sh so the tick that performed the update runs the new code.
#
# Body is wrapped in main() so bash parses the whole file before running it;
# reinstalling the launcher mid-tick cannot change the running instance.
#
# Env (all optional):
#   PR_DEPLOY_AGENT_HOME      default: ${XDG_DATA_HOME:-$HOME/.local/share}/pr-deploy-agent
#                             clone → $AGENT_HOME/repo
#                             lock  → $AGENT_HOME/launcher.lock
#   PR_DEPLOY_AGENT_CLONE_URL default: https://github.com/ridopark/oh-my-tradeagent.git
#   XDG_CONFIG_HOME           kill-switch + env-file location (same as tick.sh)
#
# Kill switch: touch ${XDG_CONFIG_HOME:-~/.config}/pr-deploy-agent/DISABLED

set -uo pipefail

# ── Alert helpers (send via the CLONE's notify.py) ───────────────────────────

_alert_once() {
  local clone="$1" key="$2" title="$3" body="$4"
  local state_dir="${STATE_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/pr-deploy-agent}"
  # Do not suppress stderr: a failure must appear in the journal as a warning.
  python3 "$clone/scripts/ops/pr-deploy-agent/notify.py" \
    --once "$key" --title "$title" --body "$body" \
    --state-dir "$state_dir" || \
    echo "$TS $LOG_TAG notify.py exited non-zero for key=$key" >&2
}

# ── Refuse and alert (four branches collapsed into one helper) ────────────────

_refuse() {
  # _refuse REASON MSG
  # Logs MSG to stderr, sends a once-only alert keyed by REASON+HEAD, exits 0.
  local reason="$1" msg="$2"
  local head
  head="$(git -C "$CLONE" rev-parse HEAD 2>/dev/null)" || head="unknown"
  echo "$TS $LOG_TAG $msg" >&2
  _alert_once "$CLONE" "clone-refused:$reason:$head" \
    "pr-deploy-agent: clone refused ($reason)" "$msg"
  exit 0
}

# ── Main body ────────────────────────────────────────────────────────────────

main() {
  local AGENT_HOME URL CLONE LOCK CONFIG_DIR TS LOG_TAG

  AGENT_HOME="${PR_DEPLOY_AGENT_HOME:-${XDG_DATA_HOME:-$HOME/.local/share}/pr-deploy-agent}"
  URL="${PR_DEPLOY_AGENT_CLONE_URL:-https://github.com/ridopark/oh-my-tradeagent.git}"
  CLONE="$AGENT_HOME/repo"
  LOCK="$AGENT_HOME/launcher.lock"
  CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/pr-deploy-agent"
  TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  LOG_TAG="[pr-deploy-agent]"

  # ── 1. Non-blocking lock (fd 8; inherited by exec'd tick.sh) ─────────────
  mkdir -p "$AGENT_HOME"
  exec 8>"$LOCK"
  if ! flock -n 8; then
    echo "$TS $LOG_TAG another tick holds the lock" >&2
    exit 0
  fi

  # ── 2. Bootstrap the clone if it is absent or not a git repo ─────────────
  if [[ ! -d "$CLONE/.git" ]]; then
    echo "$TS $LOG_TAG bootstrapping clone from $URL" >&2
    if ! timeout 120 git clone --branch main "$URL" "$CLONE" 2>&1; then
      echo "$TS $LOG_TAG clone failed; exiting" >&2
      exit 1
    fi
    local SHA7
    SHA7="$(git -C "$CLONE" rev-parse --short=7 HEAD)"
    echo "$TS $LOG_TAG cloned $URL at $SHA7" >&2
    if ! make -C "$CLONE" hooks 2>&1; then
      echo "$TS $LOG_TAG make hooks failed; continuing" >&2
    fi
  fi

  # ── 3. Kill switch (after bootstrap so the migration can create the clone
  #       while the agent is disabled; cloning has no external effect) ────────
  if [[ -f "$CONFIG_DIR/DISABLED" ]]; then
    echo "$TS $LOG_TAG kill switch set" >&2
    exit 0
  fi

  # ── 4. Update the clone ───────────────────────────────────────────────────
  if ! git -C "$CLONE" rev-parse --verify HEAD >/dev/null 2>&1; then
    echo "$TS $LOG_TAG clone at $CLONE is not a valid git repo; refusing" >&2
    exit 1
  fi

  # Capture gating git commands; fail closed on any error (finding 1).
  local BRANCH STATUS OLD_SHA7
  if ! BRANCH="$(git -C "$CLONE" rev-parse --abbrev-ref HEAD 2>&1)"; then
    _refuse "git-error" "git rev-parse --abbrev-ref HEAD failed; refusing"
  fi
  if ! STATUS="$(git -C "$CLONE" status --porcelain 2>&1)"; then
    _refuse "git-error" "git status --porcelain failed (corrupted index?); refusing"
  fi
  OLD_SHA7="$(git -C "$CLONE" rev-parse --short=7 HEAD 2>/dev/null)" || \
    _refuse "git-error" "git rev-parse --short=7 HEAD failed; refusing"

  if [[ "$BRANCH" != "main" ]]; then
    _refuse "not-on-main" \
      "clone is not on main (branch=$BRANCH); fix manually — the agent never resets"
  fi

  if [[ -n "$STATUS" ]]; then
    _refuse "dirty" \
      "clone is dirty; fix manually — the agent never resets"
  fi

  # Fetch origin/main; failure is non-fatal — run on current HEAD
  if ! timeout 60 git -C "$CLONE" fetch --quiet origin main 2>/dev/null; then
    echo "$TS $LOG_TAG fetch failed; running $OLD_SHA7" >&2
  else
    local HEAD_SHA ORIGIN_SHA
    HEAD_SHA="$(git -C "$CLONE" rev-parse HEAD)"
    ORIGIN_SHA="$(git -C "$CLONE" rev-parse origin/main)"

    if [[ "$HEAD_SHA" == "$ORIGIN_SHA" ]]; then
      echo "$TS $LOG_TAG clone at $OLD_SHA7 (up to date)" >&2
    elif git -C "$CLONE" merge-base --is-ancestor HEAD origin/main 2>/dev/null; then
      if git -C "$CLONE" merge --ff-only origin/main >/dev/null 2>&1; then
        local NEW_SHA7
        NEW_SHA7="$(git -C "$CLONE" rev-parse --short=7 HEAD)"
        echo "$TS $LOG_TAG clone updated $OLD_SHA7→$NEW_SHA7" >&2
      else
        _refuse "merge-failed" \
          "merge --ff-only failed; fix manually — the agent never resets"
      fi
    else
      _refuse "diverged" \
        "clone has diverged from origin/main; fix manually — the agent never resets"
    fi
  fi

  # ── 5. Webhook presence warning ───────────────────────────────────────────
  local HAVE_WEBHOOK=0
  if [[ -n "${DISCORD_WEBHOOK_URL:-}" ]]; then
    HAVE_WEBHOOK=1
  elif grep -q '^DISCORD_WEBHOOK_URL=' "$CLONE/.env" 2>/dev/null; then
    HAVE_WEBHOOK=1
  fi
  if [[ "$HAVE_WEBHOOK" -eq 0 ]]; then
    echo "$TS $LOG_TAG no Discord webhook configured; alerts are dropped" >&2
  fi

  # ── 6. Launcher drift check ────────────────────────────────────────────────
  local CLONE_LAUNCHER="$CLONE/scripts/ops/pr-deploy-agent/launch.sh"
  if [[ -f "$CLONE_LAUNCHER" ]] && ! cmp -s "$0" "$CLONE_LAUNCHER" 2>/dev/null; then
    local DRIFT_KEY
    DRIFT_KEY="launcher-drift:$(sha256sum "$CLONE_LAUNCHER" 2>/dev/null | cut -c1-64)"
    echo "$TS $LOG_TAG installed launcher differs from main; reinstall" >&2
    _alert_once "$CLONE" "$DRIFT_KEY" \
      "pr-deploy-agent: launcher drift" \
      "The installed launch.sh differs from the one in the clone. Re-run the install step."
  fi

  # ── 7. Exec the clone's tick.sh (same process; new code if just updated) ──
  #
  # Close fd 8 (launcher lock) before handing off to tick.sh so that any
  # process tick.sh backgrounds does not inherit it and block the next launcher
  # invocation.  tick.sh acquires its own tick lock on fd 9 ($STATE_DIR/lock)
  # which serialises concurrent ticks for the full duration of the tick.
  exec 8>&-
  exec "$CLONE/scripts/ops/pr-deploy-agent/tick.sh"
}

main "$@"
exit $?
