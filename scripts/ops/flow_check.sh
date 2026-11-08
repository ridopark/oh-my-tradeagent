#!/usr/bin/env bash
# flow-collector dead-man check
# (PLAN-2026-11-08-durable-collector-records, Step 4).
#
# READ-ONLY on production: uses only kubectl get/exec read verbs (tail/stat/cat inside exec).
# Never writes to the cluster or any database.
#
# Checks:
#   1. flow-collector Deployment ready 1/1; pod restarts <= 3.
#   2. Inside window (weekdays 09:25-16:20 ET): newest 'flow metrics:' line in
#      /app/archive/run.log has ts <= 6 minutes old.
#   3. Weekday >= 10:00 ET: today's .trades.ndjson.gz has size > 0.
#   4. Weekday >= 16:25 ET: today's manifest.json exists, trades >= FLOW_MIN_TRADES,
#      and 'flow session summary:' appears in run.log.
#
# Posts to staging_paper Discord webhook only on failure (deduped per day, one alert/day).
# Exit 0 = all checks OK.
#
# Env vars:
#   DRY_RUN=1   — never post to Discord (also implies exit 1 on any problem)
#   TEST=1      — post even when all checks hold (for channel verification)
#   FAKE_NOW    — override ET clock; a "YYYY-MM-DD HH:MM:SS" string interpreted as ET
#                 local time by GNU date -d (used in tests only)
#   FLOW_MIN_TRADES — minimum trades in the manifest (default 100000)
#
# Install recipe (run from the repo root, then SSH to the homelab):
#   scp scripts/ops/flow_check.sh ridopark@192.168.10.123:~/scripts/
#   ssh ridopark@192.168.10.123
#   crontab -l > ~/scripts/crontab.bak-$(date +%Y%m%d-%H%M)
#   Then append to crontab (host clock is Central; ET = CT + 1h through both DST changes):
#     40 8 * * 1-5 /home/ridopark/scripts/flow_check.sh >> /home/ridopark/scripts/flow-check.log 2>&1
#     35 15 * * 1-5 /home/ridopark/scripts/flow_check.sh >> /home/ridopark/scripts/flow-check.log 2>&1
set -uo pipefail
NS=${NS:-copytrade}
KUBECTL=$(command -v kubectl || echo /usr/local/bin/kubectl)
STATE_DIR=${STATE_DIR:-$HOME/scripts/.flow-check}
FLOW_MIN_TRADES=${FLOW_MIN_TRADES:-100000}
mkdir -p "$STATE_DIR"

# ET clock — FAKE_NOW overrides for tests (passed to GNU date -d, interpreted as ET local time).
if [ -n "${FAKE_NOW:-}" ]; then
  today=$(TZ=America/New_York date -d "$FAKE_NOW" +%F)
  et_hm=$(TZ=America/New_York date -d "$FAKE_NOW" +%H%M | sed 's/^0*//'); et_hm=${et_hm:-0}
  dow=$(TZ=America/New_York date -d "$FAKE_NOW" +%u)
else
  today=$(TZ=America/New_York date +%F)
  et_hm=$(TZ=America/New_York date +%H%M | sed 's/^0*//'); et_hm=${et_hm:-0}
  dow=$(TZ=America/New_York date +%u)
fi
log() { echo "$(date -u +%FT%TZ) $*"; }
problems=()

# 1. Deployment ready + restarts
ready=$($KUBECTL -n "$NS" get deploy flow-collector \
  -o jsonpath='{.status.readyReplicas}/{.spec.replicas}' 2>/dev/null)
case "$ready" in
  1/1) ;;
  *) problems+=("flow-collector Deployment not ready: '${ready:-unreachable}'") ;;
esac
restarts=0
for r in $($KUBECTL -n "$NS" get pods -l app=flow-collector \
  -o jsonpath='{.items[*].status.containerStatuses[*].restartCount}' 2>/dev/null); do
  restarts=$((restarts + r))
done
[ "${restarts:-0}" -gt 3 ] && problems+=("flow-collector pod restarted ${restarts}x")

# 2/3/4. Time-based checks
in_window=0
[ "$dow" -le 5 ] && [ "$et_hm" -ge 925 ] && [ "$et_hm" -lt 1620 ] && in_window=1

archive_bytes="n/a"
manifest_trades="n/a"

# Find the newest 'flow metrics:' line via server-side grep (immune to noise lines after it).
# Try run.log first; fall back to run.log.1 when run.log has no match (e.g. right after rotation).
last=$($KUBECTL -n "$NS" exec deploy/flow-collector -- \
  sh -c 'grep -F "flow metrics:" /app/archive/run.log 2>/dev/null | tail -n 1' 2>/dev/null) || true
if [ -z "$last" ]; then
  last=$($KUBECTL -n "$NS" exec deploy/flow-collector -- \
    sh -c 'grep -F "flow metrics:" /app/archive/run.log.1 2>/dev/null | tail -n 1' 2>/dev/null) || true
fi

# 2. In-window: metrics recency
if [ "$in_window" = 1 ]; then
  if [ -z "$last" ]; then
    problems+=("no 'flow metrics:' line found in the last 200 lines of run.log inside the window")
  else
    age_secs=$(echo "$last" | python3 -c '
import sys, json, datetime
line = sys.stdin.read().strip()
try:
    d = json.loads(line)
    ts = d.get("ts", "")
    if ts:
        dt = datetime.datetime.fromisoformat(ts.replace("Z", "+00:00"))
        now = datetime.datetime.now(datetime.timezone.utc)
        print(int((now - dt).total_seconds()))
    else:
        print(9999)
except Exception:
    print(9999)
')
    age_secs=${age_secs:-9999}
    if [ "$age_secs" -gt 360 ]; then
      problems+=("no 'flow metrics:' line within the last 6 minutes (last ts was ${age_secs}s ago)")
    fi
  fi
fi

# 3. After 10:00 ET on a weekday: archive bytes > 0
if [ "$dow" -le 5 ] && [ "$et_hm" -ge 1000 ]; then
  archive_bytes=$($KUBECTL -n "$NS" exec deploy/flow-collector -- \
    stat -c %s "/app/archive/${today}.trades.ndjson.gz" 2>/dev/null \
    | tr -d '[:space:]') || true
  archive_bytes=${archive_bytes:-0}
  if ! [[ "$archive_bytes" =~ ^[0-9]+$ ]] || [ "$archive_bytes" -eq 0 ]; then
    problems+=("archive bytes = 0 or missing for ${today}.trades.ndjson.gz")
  fi
fi

# 4. After 16:25 ET on a weekday: manifest exists, trades >= FLOW_MIN_TRADES, session summary
if [ "$dow" -le 5 ] && [ "$et_hm" -ge 1625 ]; then
  manifest=$($KUBECTL -n "$NS" exec deploy/flow-collector -- \
    cat "/app/archive/${today}.manifest.json" 2>/dev/null) || true
  if [ -z "$manifest" ]; then
    problems+=("manifest ${today}.manifest.json missing or unreadable")
    manifest_trades=0
  else
    manifest_trades=$(echo "$manifest" \
      | python3 -c 'import sys, json; print(json.load(sys.stdin).get("trades", 0))' \
      2>/dev/null || echo 0)
    manifest_trades=${manifest_trades:-0}
    if ! [[ "$manifest_trades" =~ ^[0-9]+$ ]] || [ "$manifest_trades" -lt "$FLOW_MIN_TRADES" ]; then
      problems+=("manifest trades=${manifest_trades} < FLOW_MIN_TRADES=${FLOW_MIN_TRADES} for $today")
    fi
    has_summary_raw=$($KUBECTL -n "$NS" exec deploy/flow-collector -- \
      sh -c 'grep -F "flow session summary:" /app/archive/run.log 2>/dev/null | tail -n 1' 2>/dev/null) || true
    has_summary=$(printf '%s\n' "$has_summary_raw" | python3 -c "
import sys, json
found = 'no'
today = '$today'
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        d = json.loads(line)
        if str(d.get('msg', '')).startswith('flow session summary:'):
            if d.get('ts', '')[:10] == today:
                found = 'yes'
    except Exception:
        pass
print(found)
")
    if [ "$has_summary" != "yes" ]; then
      problems+=("no 'flow session summary:' line in run.log for $today")
    fi
  fi
fi

summary="ready=${ready:-?} restarts=${restarts:-?} in_window=$in_window archive_bytes=${archive_bytes} manifest_trades=${manifest_trades} last=${last:-none}"
if [ ${#problems[@]} -eq 0 ]; then
  log "OK $today: $summary"
  [ -n "${TEST:-}" ] || exit 0
  msg="TEST post — all checks hold. $summary"
  title="flow-collector (TEST)"; color=3066993
else
  msg="$(printf -- '- %s\n' "${problems[@]}")"$'\n'"$summary"
  log "PROBLEMS $today:"$'\n'"$msg"
  title="flow-collector dead-man check"; color=15158332
fi

sig="$today|$(printf '%s\n' "${problems[@]}" | sha1sum | cut -d' ' -f1)"
if [ -z "${TEST:-}" ] && [ "$(cat "$STATE_DIR/last_sig" 2>/dev/null)" = "$sig" ]; then
  log "(identical alert already sent today — not re-posting)"; exit 1
fi
if [ "${DRY_RUN:-0}" = "1" ]; then log "(DRY_RUN — not posting to Discord)"; exit 1; fi

# Webhook resolution — same mechanism as darkpool_check.sh; URL is never printed.
wh=$($KUBECTL get secret discord-alert-credentials -n "$NS" \
       -o jsonpath='{.data.ALERT_DISCORD_WEBHOOK_URLS}' 2>/dev/null | base64 -d 2>/dev/null \
     | tr ';' '\n' | tr -d '\r' | sed -n 's/^[[:space:]]*staging_paper=//p' | head -1)
if [ -z "$wh" ]; then
  log "ERROR: could not resolve the staging_paper Discord webhook — alert NOT delivered"; exit 1
fi
payload=$(python3 -c 'import json,sys;print(json.dumps({"embeds":[{"title":sys.argv[1],"description":sys.argv[2][:4000],"color":int(sys.argv[3])}]}))' "$title" "$msg" "$color")
code=$(curl -sS -o /dev/null -w '%{http_code}' -H "Content-Type: application/json" -d "$payload" "$wh")
if [ "$code" = "204" ]; then
  log "posted to staging_paper Discord (HTTP 204)"
  [ -n "${TEST:-}" ] || echo "$sig" > "$STATE_DIR/last_sig"
else
  log "ERROR: Discord returned HTTP $code — alert NOT delivered"; exit 1
fi
[ ${#problems[@]} -eq 0 ]
