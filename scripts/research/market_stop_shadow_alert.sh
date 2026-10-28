#!/usr/bin/env bash
# market-stop-shadow-v1 dead-man alert: posts `check` problems (passed as $1) to the staging_paper Discord webhook,
# deduped per day. Same webhook resolution as watchlist_forward_alert.sh / prod_real_watchdog.sh; the URL is never printed.
# Env: NS (copytrade), STATE_DIR (~/scripts/.market-stop-shadow), DRY_RUN=1 (log only), TEST=1 (no dedupe).
set -uo pipefail
NS=${NS:-copytrade}; KUBECTL=$(command -v kubectl || echo /usr/local/bin/kubectl)
STATE_DIR=${STATE_DIR:-$HOME/scripts/.market-stop-shadow}; mkdir -p "$STATE_DIR"
today=$(TZ=America/New_York date +%F); msg="${1:-no problems passed}"
log() { echo "$(date -u +%FT%TZ) $*"; }
sig="$today|$(printf '%s' "$msg" | sha1sum | cut -d' ' -f1)"
if [ -z "${TEST:-}" ] && [ "$(cat "$STATE_DIR/last_sig" 2>/dev/null)" = "$sig" ]; then log "(identical alert already sent today)"; exit 0; fi
if [ "${DRY_RUN:-0}" = "1" ]; then log "(DRY_RUN) would post: $msg"; exit 0; fi
wh=$($KUBECTL get secret discord-alert-credentials -n "$NS" -o jsonpath='{.data.ALERT_DISCORD_WEBHOOK_URLS}' 2>/dev/null | base64 -d 2>/dev/null \
     | tr ';' '\n' | tr -d '\r' | sed -n 's/^[[:space:]]*staging_paper=//p' | head -1)
[ -n "$wh" ] || { log "ERROR: could not resolve the staging_paper webhook — alert NOT delivered"; exit 1; }
payload=$(python3 -c 'import json,sys;print(json.dumps({"embeds":[{"title":"🟡 market-stop shadow (forward test)","description":sys.argv[1][:4000],"color":15844367}]}))' "$msg")
code=$(curl -sS -o /dev/null -w '%{http_code}' -H "Content-Type: application/json" -d "$payload" "$wh")
if [ "$code" = "204" ]; then log "posted (HTTP 204)"; [ -n "${TEST:-}" ] || echo "$sig" > "$STATE_DIR/last_sig"; else log "ERROR: Discord HTTP $code"; exit 1; fi
