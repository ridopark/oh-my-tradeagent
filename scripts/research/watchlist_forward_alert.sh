#!/usr/bin/env bash
# watchlist_forward_alert.sh — dead-man alert for the watchlist forward test
# (docs/plans/PREREG-2026-10-23-watchlist-forward-v1.md). Read-only.
#
# Runs the frozen scorer's `check` (every past trading day must have a raw folder) and verifies
# today's capture files exist. On any problem, posts ONE Discord alert per distinct problem set per
# ET day to the staging_paper per-tenant webhook (research, not the real-money pager).
# A market holiday still produces (empty) capture files, so it does not alert.
#
# Cron (homelab, CT): 0 16 * * 1-5 ~/scripts/watchlist_forward_alert.sh >> ~/scripts/watchlist-forward.log 2>&1
# Env: WF_SCRIPTS (~/scripts), WF_OUT (~/data/watchlist-forward), WF_ENV (~/scripts/option-collector.env),
#      WF_STATE_DIR (/var/tmp/watchlist_forward_alert), DRY_RUN=1 (log only), TEST=1 (prefix the post
#      with [TEST] to verify delivery), KUBECTL, NS (copytrade).
set -uo pipefail

SCRIPTS="${WF_SCRIPTS:-$HOME/scripts}"
OUT="${WF_OUT:-$HOME/data/watchlist-forward}"
ENV_FILE="${WF_ENV:-$HOME/scripts/option-collector.env}"
STATE_DIR="${WF_STATE_DIR:-/var/tmp/watchlist_forward_alert}"
KUBECTL="${KUBECTL:-kubectl}"
NS="${NS:-copytrade}"
mkdir -p "$STATE_DIR"
log() { echo "$(date -Is) $*"; }

today=$(TZ=America/New_York date +%F)
dow=$(TZ=America/New_York date +%u)
problems=()

check_out=$(cd "$SCRIPTS" && python3 -B watchlist_forward.py --out "$OUT" --env "$ENV_FILE" check 2>&1)
rc=$?
log "check: $(printf '%s' "$check_out" | tail -1)"
if [ "$rc" -ne 0 ]; then
  problems+=("completeness check failed: $(printf '%s' "$check_out" | tail -1)")
fi
# capture-flow ALSO writes manifest.json, so a manifest alone does not prove the end-of-day capture
# ran (the frozen `check` counts a day by its manifest). Require the EOD files themselves.
eod_ok() { [ -f "$1/legs.json" ] && [ -f "$1/stock_1m.ndjson.gz" ]; }
if [ "$dow" -le 5 ]; then
  eod_ok "$OUT/raw/$today" || problems+=("today's end-of-day capture is missing ($today)")
  [ -f "$OUT/raw/$today/flow.ndjson.gz" ] || problems+=("today's 10:35 ET option-flow capture is missing ($today)")
fi
for d in "$OUT"/raw/*/; do
  day=$(basename "$d")
  [ "$day" = "$today" ] && continue
  [ -f "$d/manifest.json" ] && ! eod_ok "$d" && problems+=("end-of-day files missing for $day (flow-only folder)")
done

if [ "${#problems[@]}" -eq 0 ]; then
  log "OK"
  exit 0
fi

msg="${TEST:+[TEST] }watchlist-forward-v1 dead-man ($today ET) — ${#problems[@]} problem(s):"
for p in "${problems[@]}"; do msg+=$'\n• '"$p"; done
msg+=$'\n'"A missed trading day must be explained in $OUT/explained_gaps.json or it counts against the >5% completeness rule."
log "ALERT: $msg"

sig="$today|$(printf '%s\n' "${problems[@]}" | sha1sum | cut -d' ' -f1)"
if [ "$(cat "$STATE_DIR/last_sig" 2>/dev/null)" = "$sig" ]; then
  log "(identical alert already sent today — not re-posting)"
  exit 1
fi
if [ "${DRY_RUN:-0}" = "1" ]; then
  log "(DRY_RUN — not posting to Discord)"
  exit 1
fi

# Same resolution as prod_real_watchdog.sh: ";"-separated "tenant=url" entries; URL never printed.
wh=$($KUBECTL get secret discord-alert-credentials -n "$NS" \
       -o jsonpath='{.data.ALERT_DISCORD_WEBHOOK_URLS}' 2>/dev/null | base64 -d 2>/dev/null \
     | tr ';' '\n' | tr -d '\r' | sed -n 's/^[[:space:]]*staging_paper=//p' | head -1)
if [ -z "$wh" ]; then
  log "ERROR: could not resolve the staging_paper Discord webhook — alert NOT delivered"
  exit 1
fi
payload=$(python3 -c 'import json,sys;print(json.dumps({"embeds":[{"title":"🟡 watchlist forward test","description":sys.argv[1][:4000],"color":15844367}]}))' "$msg")
code=$(curl -sS -o /dev/null -w '%{http_code}' -H "Content-Type: application/json" -d "$payload" "$wh")
if [ "$code" = "204" ]; then
  log "posted alert to staging_paper Discord (HTTP 204)"
  echo "$sig" > "$STATE_DIR/last_sig"
else
  log "WARN: Discord post returned HTTP $code — alert may not have delivered"
fi
exit 1
