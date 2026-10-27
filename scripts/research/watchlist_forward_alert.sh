#!/usr/bin/env bash
# watchlist_forward_alert.sh — dead-man alert for the watchlist forward test
# (docs/plans/PREREG-2026-10-23-watchlist-forward-v1.md). Read-only.
#
# Runs the frozen scorer's `check` (every past trading day must have a raw folder), verifies today's
# capture files exist, and that the ML forward scorer (15:50 CT) wrote today's rows with TabPFN
# probabilities. On any problem, posts ONE Discord alert per distinct problem set per ET day to the
# staging_paper per-tenant webhook (research, not the real-money pager).
# A market holiday still produces (empty) capture files, so it does not alert.
# explained_gaps.json silences past-day folder gaps only; the ML checks re-alert every run until the
# scorer is fixed (deliberate: a known ML outage is still a missed forward day).
#
# Cron (homelab, CT): 0 16 * * 1-5 ~/scripts/watchlist_forward_alert.sh >> ~/scripts/watchlist-forward.log 2>&1
# Env: WF_SCRIPTS (~/scripts), WF_OUT (~/data/watchlist-forward), WF_ENV (~/scripts/option-collector.env),
#      WF_STATE_DIR (/var/tmp/watchlist_forward_alert), DRY_RUN=1 (log only), TEST=1 (prefix the post
#      with [TEST] to verify delivery), KUBECTL, NS (copytrade), WF_TODAY / WF_DOW (test-only overrides
#      of the ET date / weekday; tests/test_watchlist_forward_alert.sh).
set -uo pipefail

SCRIPTS="${WF_SCRIPTS:-$HOME/scripts}"
OUT="${WF_OUT:-$HOME/data/watchlist-forward}"
ENV_FILE="${WF_ENV:-$HOME/scripts/option-collector.env}"
STATE_DIR="${WF_STATE_DIR:-/var/tmp/watchlist_forward_alert}"
KUBECTL="${KUBECTL:-kubectl}"
NS="${NS:-copytrade}"
mkdir -p "$STATE_DIR"
log() { echo "$(date -Is) $*"; }

today="${WF_TODAY:-$(TZ=America/New_York date +%F)}"   # WF_TODAY/WF_DOW: test overrides only
dow="${WF_DOW:-$(TZ=America/New_York date +%u)}"
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
# Days explained in explained_gaps.json (a list of dates or a {date: reason} map) are not re-alerted.
explained=$(python3 -c 'import json,sys
try: print(" ".join(json.load(open(sys.argv[1]))))
except FileNotFoundError: pass' "$OUT/explained_gaps.json" 2>/dev/null)
# ML forward test (PREREG-2026-10-24-watchlist-forward-ml-v1): on a weekday where v1 captured >= 1 leg,
# the 15:50 CT scorer must have written today's rows; a scored leg without a TabPFN probability means
# the model fell back. Both python probes FAIL CLOSED: a non-integer result (traceback) is a problem,
# never "healthy" — a dead-man alert that swallows its own errors is no alert at all.
is_int() { [ "$1" -ge 0 ] 2>/dev/null; }
if [ "$dow" -le 5 ] && [ -f "$OUT/raw/$today/legs.json" ]; then
  nlegs=$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["legs"]))' \
            "$OUT/raw/$today/legs.json" 2>&1)
  if ! is_int "$nlegs"; then
    problems+=("today's legs.json is unreadable ($today): ${nlegs##*$'\n'}")
  elif [ "$nlegs" -gt 0 ]; then
    # grep -c prints 0 AND exits 1 on no match, so no `|| echo 0`; a missing file prints nothing.
    mlrows=$(grep -c "^$today," "$OUT/ml_scores.csv" 2>/dev/null); mlrows=${mlrows:-0}
    if [ "$mlrows" -eq 0 ]; then
      problems+=("ML forward scorer wrote no rows for $today ($nlegs legs captured)"\
" — see ~/scripts/watchlist-forward-ml.log")
    else
      # score_day appends exactly one row per leg in legs.json — fired-and-scored, fired-before-minute-5
      # (scored=0), not_fired, armed_after_open and no_bars alike — so a day's row count must equal its leg
      # count; fewer means the scorer died part-way. (Only known over-count: two scored legs sharing
      # ticker+direction fan out in the scorer's merge; 0 of the 219 frozen context legs did.)
      if [ "$mlrows" -ne "$nlegs" ]; then
        problems+=("ML forward scorer wrote $mlrows rows for $today but $nlegs legs were captured"\
" — see ~/scripts/watchlist-forward-ml.log")
      fi
      # pandas writes `scored` as 1 or 1.0 depending on whether any row in the file is NaN — compare numerically.
      unscored=$(python3 -c 'import csv,sys
rows=[x for x in csv.DictReader(open(sys.argv[1])) if x["d"]==sys.argv[2]]
print(sum(1 for x in rows if float(x.get("scored") or 0)==1.0 and not x.get("p_tabpfn24")))' \
            "$OUT/ml_scores.csv" "$today" 2>&1)
      if ! is_int "$unscored"; then
        problems+=("ML score check errored for $today: ${unscored##*$'\n'}")
      elif [ "$unscored" -gt 0 ]; then
        problems+=("ML scorer: $unscored scored leg(s) have no TabPFN probability today (model fell back)"\
" — check the token/venv")
      fi
    fi
  fi
fi
for d in "$OUT"/raw/*/; do
  day=$(basename "$d")
  [ "$day" = "$today" ] && continue
  case " $explained " in *" $day "*) continue ;; esac
  [ -f "$d/manifest.json" ] && ! eod_ok "$d" && problems+=("end-of-day files missing for $day (flow-only folder)")
done

if [ "${#problems[@]}" -eq 0 ]; then
  log "OK"
  exit 0
fi

msg="${TEST:+[TEST] }watchlist-forward (v1 + ml-v1) dead-man ($today ET) — ${#problems[@]} problem(s):"
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
  # a TEST post must not dedupe away a real alert with the same problem set later that day
  [ -n "${TEST:-}" ] || echo "$sig" > "$STATE_DIR/last_sig"
else
  log "WARN: Discord post returned HTTP $code — alert may not have delivered"
fi
exit 1
