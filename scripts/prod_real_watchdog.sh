#!/usr/bin/env bash
#
# prod_real_watchdog.sh — read-only real-money watchdog for the prod_real tenant
# (Alpaca LIVE account 847309116). Market-hours-gated sweep of live positions /
# orders / lifecycle events / kill switches; posts a red Discord embed to the
# prod_real channel on any anomaly. Extracted from the agent-driven cron watchdog
# so a plain cron / k8s CronJob runs it with no agent (token) cost.
#
# SAFETY — strictly READ-ONLY. Only `kubectl get`, `kubectl exec … psql <SELECT>`,
# and `temporal workflow list|query`. It NEVER mutates broker, DB, or workflows,
# and never echoes secrets.
#
# Designed to run ON the homelab host (native kubectl) via crontab. The script
# self-gates to 09:30–16:05 ET, so the cron window only has to bracket it:
#   # every 5 min inside the trading day, weekdays (times in the HOST's TZ)
#   2-59/5 13-21 * * 1-5  /opt/oh-my-tradeagent/scripts/prod_real_watchdog.sh >> /var/log/prod_real_watchdog.log 2>&1
#   #                ^^^^^ 13-21 UTC ≈ 09-17 ET; adjust to your host TZ (the ET gate below is the real boundary)
#
# Env overrides:
#   KUBECTL             kubectl invocation (default: kubectl). Remote: KUBECTL="ssh user@host kubectl"
#   COPYTRADE_NS        copytrade namespace (default: copytrade)
#   TEMPORAL_NS         namespace of the temporal admintools pod (default: temporal)
#   WATCHDOG_FORCE=1    bypass the market-hours gate (manual run / testing)
#   DRY_RUN=1           run the sweep + classify but do NOT post to Discord (print instead)
#   WATCHDOG_STATE_DIR  dedup state dir (default: /var/tmp/prod_real_watchdog)
#
# Exit codes: 0 = clean (or market closed) · 1 = anomaly detected.
#
set -uo pipefail

KUBECTL="${KUBECTL:-kubectl}"
NS="${COPYTRADE_NS:-copytrade}"
TEMPORAL_NS="${TEMPORAL_NS:-temporal}"
STATE_DIR="${WATCHDOG_STATE_DIR:-/var/tmp/prod_real_watchdog}"
TENANT="prod_real"
LIVE_DB="exec_alpaca_live"
mkdir -p "$STATE_DIR"

log() { printf '%s %s\n' "$(TZ=America/New_York date +'%Y-%m-%d %H:%M:%S ET')" "$*"; }

# ---- STEP 0: market-hours gate (ET 09:30–16:05, Mon–Fri) --------------------
# Exit BEFORE any kubectl/ssh so a closed market costs nothing.
if [ "${WATCHDOG_FORCE:-0}" != "1" ]; then
  dow=$(TZ=America/New_York date +%u)              # 1=Mon … 7=Sun
  hm=$((10#$(TZ=America/New_York date +%H%M)))     # 10# = force base-10 (avoid octal on leading 0)
  if [ "$dow" -gt 5 ] || [ "$hm" -lt 930 ] || [ "$hm" -gt 1605 ]; then
    exit 0
  fi
fi

# ---- READ-ONLY helpers ------------------------------------------------------
# psql_q <db>  — SQL is read from stdin; returns tuples-only, unaligned output.
psql_q() {
  $KUBECTL exec -i -n "$NS" postgres-0 -- \
    bash -lc 'psql -U "$POSTGRES_USER" -d "'"$1"'" -tAqX'
}
ADMIN_POD=$($KUBECTL get pods -n "$TEMPORAL_NS" -o name 2>/dev/null | grep admintools | head -1 | cut -d/ -f2)
# tq <workflow-id> <query-type>
tq() {
  [ -n "$ADMIN_POD" ] || return 1
  $KUBECTL exec -n "$TEMPORAL_NS" "$ADMIN_POD" -- \
    temporal workflow query --namespace "$NS" --workflow-id "$1" --type "$2" 2>/dev/null
}

anomalies=()

# ---- (d) kill switches (per-strategy + account) -----------------------------
if [ -z "$ADMIN_POD" ]; then
  log "WARN: temporal admintools pod not found in ns=$TEMPORAL_NS — kill-switch + open-position checks skipped"
else
  for spec in "t-$TENANT/s-copytrade-v1/killswitch:killswitch_state" \
              "t-$TENANT/s-watchlist-trigger-v1/killswitch:killswitch_state" \
              "t-$TENANT/account/killswitch:account_killswitch_state"; do
    wf=${spec%:*}; qt=${spec##*:}
    st=$(tq "$wf" "$qt")
    if [ -z "$st" ]; then
      log "WARN: could not read kill-switch state for $wf"
    elif printf '%s' "$st" | grep -q '"tripped":true'; then
      reason=$(printf '%s' "$st" | grep -o '"reason":"[^"]*"' | head -1)
      anomalies+=("KILL SWITCH TRIPPED: $wf ${reason:-}")
    fi
  done
fi

# ---- (e) blocked / stuck live orders (403 40310000 = account re-block) -------
blocked=$(psql_q "$LIVE_DB" <<'SQL'
SELECT count(*) FROM order_intent_journal
WHERE recorded_at > now() - interval '2 days'
  AND state = 'RECORDED'
  AND (last_error LIKE '%40310000%' OR last_error LIKE '%403%');
SQL
)
if [ "${blocked:-0}" -gt 0 ] 2>/dev/null; then
  anomalies+=("$blocked live order(s) stuck RECORDED with 403/40310000 — account re-blocked")
fi

# ---- (c) failure / ongoing-orphan lifecycle events today --------------------
# Deliberately excludes the benign PositionOrphanSuppressedSiblingOwner and the
# one-shot PositionOrphan (often a recon false-positive); only the *ongoing* /
# retry-exhausted / flatten-exit-failure kinds are hard anomalies.
fails=$(psql_q orchestrator <<SQL
SELECT count(*) FROM audit_log
WHERE tenant_id = '$TENANT'
  AND occurred_at::date = (now() AT TIME ZONE 'America/New_York')::date
  AND kind IN ('EodForceFlattenFailed','FlattenRetryScheduled','FlattenRetryExhausted',
               'PartialExitPlaceFailed','PartialExitRetryExhausted',
               'PositionOrphanOngoing','JournalOrphanOngoing');
SQL
)
if [ "${fails:-0}" -gt 0 ] 2>/dev/null; then
  anomalies+=("$fails prod_real flatten/exit-failure or ongoing-orphan event(s) today")
fi

# ---- scorecard context: open positions + today's fills ----------------------
open_pos=0
if [ -n "$ADMIN_POD" ]; then
  open_pos=$($KUBECTL exec -n "$TEMPORAL_NS" "$ADMIN_POD" -- \
    temporal workflow list --namespace "$NS" \
    --query 'WorkflowType="PositionWorkflow" AND ExecutionStatus="Running"' 2>/dev/null \
    | grep -c "$TENANT")
fi
fills=$(psql_q "$LIVE_DB" <<'SQL'
SELECT count(*) FROM order_intent_journal
WHERE state = 'FILLED'
  AND recorded_at::date = (now() AT TIME ZONE 'America/New_York')::date;
SQL
)

# ---- classify + alert -------------------------------------------------------
if [ "${#anomalies[@]}" -eq 0 ]; then
  log "prod_real ALL CLEAR — open=$open_pos fills_today=${fills:-0}; kill switches OK; no 403/flatten/orphan."
  exit 0
fi

today=$(TZ=America/New_York date +%F)
msg="prod_real WATCHDOG ALERT ($today ET) — ${#anomalies[@]} issue(s):"
for a in "${anomalies[@]}"; do msg+=$'\n• '"$a"; done
msg+=$'\n'"context: open=$open_pos fills_today=${fills:-0}"
log "ANOMALY: $msg"

# dedup: don't re-post the identical anomaly set (5-min cron would otherwise spam)
sig="$today|$(printf '%s\n' "${anomalies[@]}" | sha1sum | cut -d' ' -f1)"
if [ "$(cat "$STATE_DIR/last_sig" 2>/dev/null)" = "$sig" ]; then
  log "(identical anomaly already alerted today — not re-posting)"
  exit 1
fi

if [ "${DRY_RUN:-0}" = "1" ]; then
  log "(DRY_RUN — not posting to Discord)"
  echo "$sig" > "$STATE_DIR/last_sig"
  exit 1
fi

# resolve the prod_real per-tenant webhook from the secret (NEVER printed).
# Format is the TenantWebhookResolver map "tenant=url,tenant=url" (NOT JSON).
wh=$($KUBECTL get secret discord-alert-credentials -n "$NS" \
       -o jsonpath='{.data.ALERT_DISCORD_WEBHOOK_URLS}' 2>/dev/null | base64 -d 2>/dev/null \
     | tr ',' '\n' | tr -d '\r' | sed -n 's/^[[:space:]]*prod_real=//p' | head -1)
if [ -z "$wh" ]; then
  log "ERROR: could not resolve prod_real Discord webhook — alert NOT delivered (anomalies logged above)."
  exit 1
fi

payload=$(python3 -c 'import json,sys;print(json.dumps({"embeds":[{"title":"🔴 prod_real watchdog","description":sys.argv[1][:4000],"color":15158332}]}))' "$msg")
code=$(curl -sS -o /dev/null -w '%{http_code}' -H "Content-Type: application/json" -d "$payload" "$wh")
if [ "$code" = "204" ]; then
  log "posted alert to prod_real Discord (HTTP 204)"
  echo "$sig" > "$STATE_DIR/last_sig"
else
  log "WARN: Discord post returned HTTP $code — alert may not have delivered"
fi
exit 1
