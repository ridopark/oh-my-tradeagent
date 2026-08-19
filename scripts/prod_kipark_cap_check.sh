#!/usr/bin/env bash
# One-shot (2026-07-22): confirm prod-kipark's account daily-loss cap ARMED after Wed open,
# post PR #604/#605 (DB-backed enumeration) + the live tree-add. READ-ONLY. Posts to Discord,
# then removes its own crontab line so it runs exactly once.
#
# STATUS: SPENT. This already ran (2026-07-22) and deleted its own crontab line, which is why it
# was found on the homelab in no cron and in no repo. Committed 2026-08-18 for the record: it is
# the verification that PR #604/#605 actually armed prod-kipark's cap on real money, and it is a
# usable template for "prove a control armed after a change" — but it is not a live control and
# nothing runs it.
#
# ⚠ RE-RUNNING IS DESTRUCTIVE TO CRON. The last line rewrites the crontab via
# `crontab -l | grep -v ... | crontab -`. There is no `set -e` and no guard on an empty read, so if
# `crontab -l` fails or returns nothing (wrong user, no crontab), this INSTALLS AN EMPTY CRONTAB and
# silently removes prod_real_watchdog.sh's schedule with it. Kept verbatim so the committed artifact
# is exactly what ran; guard that line before ever executing this again.
#
set -uo pipefail
NS=copytrade
KC=$(command -v kubectl || echo /usr/local/bin/kubectl)
TODAY_ET=$(TZ=America/New_York date +%F)
ADMIN=$($KC -n temporal get pods -o jsonpath='{.items[*].metadata.name}' 2>/dev/null | tr ' ' '\n' | grep admintools | head -1)

STATE=$($KC -n temporal exec "$ADMIN" -- temporal workflow query \
  --workflow-id t-prod-kipark/account/killswitch --type account_killswitch_state \
  --namespace copytrade 2>/dev/null | grep -oE '\{.*\}' | head -1)

# 0 CapInactive events today => cap armed; >0 => still not armed (reason from #604 typed defer).
RES=$($KC -n "$NS" exec postgres-0 -- psql -U temporal -d orchestrator -tAc \
  "SELECT count(*)||'|'||COALESCE(max(subject->>'reason'),'') FROM audit_log \
   WHERE tenant_id='prod-kipark' AND kind='AccountKillSwitchCapInactive' \
   AND (occurred_at AT TIME ZONE 'America/New_York')::date='${TODAY_ET}';" 2>/dev/null | tr -d '[:space:]')
CNT=${RES%%|*}; REASON=${RES#*|}

if [ "${CNT:-x}" = "0" ]; then
  TITLE=":white_check_mark: prod-kipark cap ARMED (${TODAY_ET})"
  DESC="Zero AccountKillSwitchCapInactive since open — the DB-backed enumeration (PR #604/#605) + tree-add worked; prod-kipark's daily-loss cap is protecting real money. state=${STATE}"
  COLOR=3066993
else
  TITLE=":warning: prod-kipark cap STILL NOT ARMED (${TODAY_ET})"
  DESC="${CNT} CapInactive events today, reason='${REASON}'. The cap is NOT protecting prod-kipark — investigate. state=${STATE}"
  COLOR=15158332
fi

WH=$($KC -n "$NS" get secret discord-alert-credentials -o jsonpath='{.data.ALERT_DISCORD_WEBHOOK_URL}' 2>/dev/null | base64 -d 2>/dev/null)
[ -z "$WH" ] && WH=$($KC -n "$NS" get secret discord-alert-credentials -o jsonpath='{.data.ALERT_DISCORD_WEBHOOK_URLS}' 2>/dev/null | base64 -d 2>/dev/null | grep -oE 'https://[^"]+' | head -1)
PAYLOAD=$(python3 -c "import json,sys;print(json.dumps({'embeds':[{'title':sys.argv[1],'description':sys.argv[2][:1900],'color':int(sys.argv[3])}]}))" "$TITLE" "$DESC" "$COLOR")
[ -n "$WH" ] && curl -sS -o /dev/null -w 'discord=%{http_code}\n' -H 'Content-Type: application/json' -d "$PAYLOAD" "$WH"
echo "$(date) prod-kipark cap check: CNT=${CNT} REASON=${REASON}"

# one-shot: strip own crontab line
crontab -l 2>/dev/null | grep -v 'prod_kipark_cap_check.sh' | crontab -
