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
# Pre-unset hard-gate (single-account-loss-rule C6): run
#   scripts/prod_real_watchdog.sh --preunset-gate     (or PREUNSET_GATE=1)
# BEFORE the operator unsets copytrade-v1's daily_loss_threshold. It bypasses the
# market-hours gate (run it market-closed), skips the anomaly sweep, and instead
# verifies the account cap is a healthy SOLE loss breaker: cap armed in the DB the
# kill switch reads, no recent AccountKillSwitchCapInactive, the account-KS workflow
# Running + queryable + NOT tripped, and the prod_real page webhook resolvable. It
# prints per-check PASS/FAIL and exits 0 = GO / 1 = NO-GO. Strictly read-only; posts
# nothing to Discord.
#
# Env overrides:
#   KUBECTL             kubectl invocation (default: kubectl). Remote: KUBECTL="ssh user@host kubectl"
#   COPYTRADE_NS        copytrade namespace (default: copytrade)
#   TEMPORAL_NS         namespace of the temporal admintools pod (default: temporal)
#   WATCHDOG_FORCE=1    bypass the market-hours gate (manual run / testing)
#   DRY_RUN=1           run the sweep + classify but do NOT post to Discord (print instead)
#   WATCHDOG_STATE_DIR  dedup state dir (default: /var/tmp/prod_real_watchdog)
#   PREUNSET_GATE=1     run the pre-unset hard-gate (above) instead of the sweep
#
# Exit codes (sweep):     0 = clean · 1 = anomaly detected · 2 = degraded (a check could not run).
# Exit codes (--preunset-gate): 0 = GO (all checks pass) · 1 = NO-GO (any check failed/unreadable).
#
set -uo pipefail

KUBECTL="${KUBECTL:-kubectl}"
NS="${COPYTRADE_NS:-copytrade}"
TEMPORAL_NS="${TEMPORAL_NS:-temporal}"
STATE_DIR="${WATCHDOG_STATE_DIR:-/var/tmp/prod_real_watchdog}"
TENANT="prod_real"
LIVE_DB="exec_alpaca_live"
mkdir -p "$STATE_DIR"

# --preunset-gate (C6) is a distinct market-closed operator mode; map the flag onto
# the PREUNSET_GATE env so either invocation form works.
[ "${1:-}" = "--preunset-gate" ] && PREUNSET_GATE=1
PREUNSET_GATE="${PREUNSET_GATE:-0}"

log() { printf '%s %s\n' "$(TZ=America/New_York date +'%Y-%m-%d %H:%M:%S ET')" "$*"; }

# ---- STEP 0: market-hours gate (ET 09:30–16:05, Mon–Fri) --------------------
# Exit BEFORE any kubectl/ssh so a closed market costs nothing. The pre-unset gate
# is a deliberate market-CLOSED check, so it bypasses this gate (like WATCHDOG_FORCE).
if [ "${WATCHDOG_FORCE:-0}" != "1" ] && [ "$PREUNSET_GATE" != "1" ]; then
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
# count_q <db> — SQL on stdin; echoes an integer, or returns 1 (empty) when the
# query / exec failed or returned non-numeric. Lets callers distinguish a genuine
# "0" from an UNREADABLE result — an unreadable anomaly query must NOT read as clean.
count_q() {
  local out
  out=$(psql_q "$1") || true
  [[ "$out" =~ ^[0-9]+$ ]] && { printf '%s' "$out"; return 0; }
  return 1
}
ADMIN_POD=$($KUBECTL get pods -n "$TEMPORAL_NS" -o name 2>/dev/null | grep admintools | head -1 | cut -d/ -f2)
# tq <workflow-id> <query-type>
tq() {
  [ -n "$ADMIN_POD" ] || return 1
  $KUBECTL exec -n "$TEMPORAL_NS" "$ADMIN_POD" -- \
    temporal workflow query --namespace "$NS" --workflow-id "$1" --type "$2" 2>/dev/null
}
# check_ks <workflow-id> <query-type> — flag a tripped kill switch as an anomaly.
# Unreadable state is a loud WARN (never a silent pass) so drift/outage is visible.
check_ks() {
  local st reason
  st=$(tq "$1" "$2")
  if [ -z "$st" ]; then
    log "WARN: could not read kill-switch state for $1"
    degraded=1
  elif printf '%s' "$st" | grep -q '"tripped":true'; then
    reason=$(printf '%s' "$st" | grep -o '"reason":"[^"]*"' | head -1)
    # An operator Deactivate trips the breaker BY DESIGN, and it stays tripped for as
    # long as the strategy is meant to stay off. prod_real's watchlist-trigger-v1 has
    # been deliberately deactivated since 2026-08-16 and is NOT to be re-activated, so
    # this fired every trading morning with nothing for the operator to do. A page that
    # is always present is a page nobody reads — the same alert-fatigue that let the
    # fill listener sit broken for eleven weeks.
    #
    # Suppress the PAGE, never the VISIBILITY: the state still goes to the log every
    # run, so a strategy left off by accident is still discoverable. Only this exact
    # reason is exempt, because it literally means "an operator clicked Deactivate".
    # An automatic trip (auto:*, risk breach, loss cap) carries a different reason and
    # still pages.
    if printf '%s' "$reason" | grep -q 'live_deactivation:one_click'; then
      log "INFO: $1 tripped by an intentional operator Deactivate (${reason:-}) — not an anomaly"
    else
      anomalies+=("KILL SWITCH TRIPPED: $1 ${reason:-}")
    fi
  fi
}

# account_ks_liveness — durable liveness probe (C1) for the tenant's account
# kill-switch workflow, which after the single-account-loss-rule epic is the SOLE
# daily-loss breaker. Distinct from check_ks (which reads the trip STATE and fails
# SOFT to degraded when unreadable): a TERMINATED/CLOSED/absent account-KS workflow
# means every copytrade entry fail-closes with KILL_SWITCH_UNAVAILABLE while NO
# loss trip can fire — a silent outage of the last breaker. This distinguishes
# "probe could not run" (transient → caller degrades) from "workflow is not in the
# Running set" (hard → caller pages).
# Echoes: "running" | "not_running" | "unreadable".
account_ks_liveness() {
  [ -n "$ADMIN_POD" ] || { printf 'unreadable'; return; }
  local out rc wfid="t-$TENANT/account/killswitch"
  out=$($KUBECTL exec -n "$TEMPORAL_NS" "$ADMIN_POD" -- \
    temporal workflow list --namespace "$NS" \
    --query 'WorkflowType="AccountKillSwitchWorkflow" AND ExecutionStatus="Running"' 2>/dev/null)
  rc=$?
  if [ "$rc" -ne 0 ]; then printf 'unreadable'; return; fi
  if printf '%s' "$out" | grep -qF "$wfid"; then printf 'running'; else printf 'not_running'; fi
}

# ---- pre-unset hard-gate (C6) ----------------------------------------------
# Verifies the account cap is a healthy SOLE loss breaker before the operator
# unsets copytrade-v1's daily_loss_threshold. Prints per-check PASS/FAIL; returns
# 0 (GO) only when EVERY check passes, else 1 (NO-GO). Read-only, posts nothing.
preunset_gate() {
  local fails=0
  echo "== prod_real pre-unset hard-gate (single-account-loss-rule C6) =="
  echo "   Purpose: confirm the account cap can safely be the SOLE daily-loss breaker"
  echo "   before unsetting copytrade-v1 daily_loss_threshold. GO only if all PASS."
  echo

  # (1) account cap armed in the DB the kill switch reads (tenant_config, orchestrator DB).
  local armed
  armed=$(psql_q orchestrator <<SQL
SELECT CASE WHEN COALESCE(account_daily_loss_pct,0) > 0
             OR COALESCE(account_daily_loss_threshold,0) > 0
            THEN 'armed' ELSE 'unarmed' END
FROM tenant_config WHERE tenant_id = '$TENANT';
SQL
) || armed=""
  if [ "$armed" = "armed" ]; then
    local pctline
    pctline=$(psql_q orchestrator <<SQL
SELECT 'pct=' || COALESCE(account_daily_loss_pct::text,'null')
     || ' abs=' || COALESCE(account_daily_loss_threshold::text,'null')
FROM tenant_config WHERE tenant_id = '$TENANT';
SQL
) || pctline="?"
    echo "[PASS] (1) account cap ARMED in tenant_config ($pctline)"
  elif [ "$armed" = "unarmed" ]; then
    echo "[FAIL] (1) account cap UNARMED in tenant_config — the sole breaker would be OFF; DO NOT unset"
    fails=$((fails+1))
  else
    echo "[FAIL] (1) could not read tenant_config.account_daily_loss_* (orchestrator DB unreadable)"
    fails=$((fails+1))
  fi

  # (2) no recent AccountKillSwitchCapInactive — the cap check itself has been running.
  local capinactive
  capinactive=$(count_q orchestrator <<SQL
SELECT count(*) FROM audit_log
WHERE tenant_id = '$TENANT'
  AND kind = 'AccountKillSwitchCapInactive'
  AND occurred_at > now() - interval '1 day';
SQL
)
  if [ $? -ne 0 ] || [ -z "$capinactive" ]; then
    echo "[FAIL] (2) could not read AccountKillSwitchCapInactive events (orchestrator audit_log unreadable)"
    fails=$((fails+1))
  elif [ "$capinactive" -eq 0 ]; then
    echo "[PASS] (2) no AccountKillSwitchCapInactive in the last 24h (cap check is live)"
  else
    echo "[FAIL] (2) $capinactive AccountKillSwitchCapInactive event(s) in 24h — the cap check has been INERT; do not rely on it yet"
    fails=$((fails+1))
  fi

  # (3) account-KS workflow Running + queryable + NOT tripped.
  local liveness state
  liveness=$(account_ks_liveness)
  if [ "$liveness" = "running" ]; then
    state=$(tq "t-$TENANT/account/killswitch" account_killswitch_state)
    if [ -z "$state" ]; then
      echo "[FAIL] (3) account-KS workflow Running but state query returned nothing (not queryable)"
      fails=$((fails+1))
    elif printf '%s' "$state" | grep -q '"tripped":true'; then
      echo "[FAIL] (3) account-KS workflow is TRIPPED — resolve/reset before unsetting the per-strategy breaker"
      fails=$((fails+1))
    else
      echo "[PASS] (3) account-KS workflow Running, queryable, not tripped"
    fi
  elif [ "$liveness" = "not_running" ]; then
    echo "[FAIL] (3) account-KS workflow NOT RUNNING (t-$TENANT/account/killswitch) — sole breaker is DOWN"
    fails=$((fails+1))
  else
    echo "[FAIL] (3) could not probe account-KS liveness (temporal admintools unreadable)"
    fails=$((fails+1))
  fi

  # (4) prod_real page webhook resolvable — a trip CAN page (delivery path wired).
  local wh
  wh=$($KUBECTL get secret discord-alert-credentials -n "$NS" \
         -o jsonpath='{.data.ALERT_DISCORD_WEBHOOK_URLS}' 2>/dev/null | base64 -d 2>/dev/null \
       | tr ';' '\n' | tr -d '\r' | sed -n 's/^[[:space:]]*prod_real=//p' | head -1)
  if [ -n "$wh" ]; then
    echo "[PASS] (4) prod_real Discord webhook resolves (page path wired; secret NOT printed)"
  else
    echo "[FAIL] (4) prod_real Discord webhook did NOT resolve — a cap trip could not page"
    fails=$((fails+1))
  fi

  echo
  if [ "$fails" -eq 0 ]; then
    echo "== GO: all pre-unset checks PASS. Recommend a live test-trip (operator) to confirm end-to-end paging before unsetting. =="
    return 0
  fi
  echo "== NO-GO: $fails check(s) failed. Do NOT unset copytrade-v1 daily_loss_threshold. =="
  return 1
}

if [ "$PREUNSET_GATE" = "1" ]; then
  preunset_gate
  exit $?
fi

anomalies=()
degraded=0   # set when a check could not run (unreadable) — suppresses a false ALL CLEAR

# ---- (d) kill switches (per-strategy + account) -----------------------------
# workflow-id shapes mirror the Java WorkflowIds (killswitch / accountKillswitch);
# no generated bash artifact exists, so the shape is duplicated here (single-site).
if [ -z "$ADMIN_POD" ]; then
  log "WARN: temporal admintools pod not found in ns=$TEMPORAL_NS — kill-switch + open-position checks skipped"
  degraded=1
else
  # MAINTENANCE: add every ENABLED prod_real strategy here. A strategy omitted from
  # this list has its kill switch UNMONITORED → a tripped switch would show as a
  # false ALL CLEAR (the most dangerous miss for a real-money watchdog).
  for strat in copytrade-v1 watchlist-trigger-v1; do
    check_ks "t-$TENANT/s-$strat/killswitch" killswitch_state
  done
  check_ks "t-$TENANT/account/killswitch" account_killswitch_state

  # (d.1) account-KS LIVENESS (C1) — the account cap is the SOLE daily-loss breaker
  # (single-account-loss-rule epic). check_ks above only reads the trip STATE and
  # fails SOFT to degraded when unreadable; a TERMINATED/CLOSED/absent account-KS
  # workflow is a silent outage of the last breaker (copytrade entries fail-closed,
  # no trip can fire), so treat "not Running" as a HARD anomaly that pages. A probe
  # that could not RUN only degrades (no false page on a transient temporal blip).
  case "$(account_ks_liveness)" in
    not_running) anomalies+=("ACCOUNT KILL SWITCH WORKFLOW NOT RUNNING (t-$TENANT/account/killswitch) — the sole daily-loss breaker is DOWN; copytrade entries fail-closed, no loss trip can fire") ;;
    unreadable)  log "WARN: account-KS liveness probe could not run — cannot confirm the sole loss breaker is alive"; degraded=1 ;;
  esac
fi

# ---- (e) blocked / stuck live orders (403 40310000 = account re-block) -------
if blocked=$(count_q "$LIVE_DB" <<'SQL'
SELECT count(*) FROM order_intent_journal
WHERE recorded_at > now() - interval '2 days'
  AND state = 'RECORDED'
  AND (last_error LIKE '%40310000%' OR last_error LIKE '%403%');
SQL
); then
  [ "$blocked" -gt 0 ] && anomalies+=("$blocked live order(s) stuck RECORDED with 403/40310000 — account re-blocked")
else
  log "WARN: 403-block query failed (exec_alpaca_live unreadable) — cannot confirm account-block state"
  degraded=1
fi

# ---- (c) failure / ongoing-orphan lifecycle events today --------------------
# Deliberately excludes the benign PositionOrphanSuppressedSiblingOwner and the
# one-shot PositionOrphan (often a recon false-positive); only the *ongoing* /
# retry-exhausted / flatten-exit-failure kinds are hard anomalies.
if fails=$(count_q orchestrator <<SQL
SELECT count(*) FROM audit_log
WHERE tenant_id = '$TENANT'
  AND occurred_at::date = (now() AT TIME ZONE 'America/New_York')::date
  AND kind IN ('EodForceFlattenFailed','FlattenRetryScheduled','FlattenRetryExhausted',
               'PartialExitPlaceFailed','PartialExitRetryExhausted',
               'PositionOrphanOngoing','JournalOrphanOngoing');
SQL
); then
  [ "$fails" -gt 0 ] && anomalies+=("$fails prod_real flatten/exit-failure or ongoing-orphan event(s) today")
else
  log "WARN: lifecycle-failure query failed (orchestrator audit_log unreadable) — cannot confirm flatten/orphan state"
  degraded=1
fi

# ---- (d) entry-workflow non-retryable failures today (silent black-hole guard) --
# Post-2026-07-06 (PLAN-2026-07-06-pretrade-check-orchestrator-wiring): a
# CopytradeSignalWorkflow that fails non-retryably BEFORE any lifecycle audit —
# the canonical case being PreTradeCheckMisconfigured when the pre-trade routing
# property (ORCHESTRATOR_PRE_TRADE_CHECK_ROUTING_ENABLED) is unset/dropped by a
# redeploy — now emits EntryWorkflowFailed (PR #564). ANY such event on the
# real-money tenant means a live signal was black-holed (received, no order placed),
# so it is a hard anomaly. This is the deterministic guard for the exact outage that
# lost 3 prod_real signals on 2026-07-06.
if entryfails=$(count_q orchestrator <<SQL
SELECT count(*) FROM audit_log
WHERE tenant_id = '$TENANT'
  AND occurred_at::date = (now() AT TIME ZONE 'America/New_York')::date
  AND kind = 'EntryWorkflowFailed';
SQL
); then
  [ "$entryfails" -gt 0 ] && anomalies+=("$entryfails prod_real entry-workflow failure(s) today (EntryWorkflowFailed — e.g. PreTradeCheckMisconfigured; live signal black-holed, no order placed)")
else
  log "WARN: entry-workflow-failure query failed (orchestrator audit_log unreadable) — cannot confirm entry-failure state"
  degraded=1
fi

# ---- scorecard context: open positions + today's fills ----------------------
# open_pos is CONTEXT ONLY, never an anomaly trigger: overnight exposure is
# by-design here (eod_force_flatten=false), and per-position staleness/red-lot
# judgment is deliberately left to the agent watchdog this script was extracted
# from. The deterministic anomalies above (kill switch / 403 / flatten-exit-fail)
# are what page.
open_pos=0
if [ -n "$ADMIN_POD" ]; then
  open_pos=$($KUBECTL exec -n "$TEMPORAL_NS" "$ADMIN_POD" -- \
    temporal workflow list --namespace "$NS" \
    --query 'WorkflowType="PositionWorkflow" AND ExecutionStatus="Running"' 2>/dev/null \
    | grep -c "$TENANT")
fi
fills=$(count_q "$LIVE_DB" <<'SQL'
SELECT count(*) FROM order_intent_journal
WHERE state = 'FILLED'
  AND recorded_at::date = (now() AT TIME ZONE 'America/New_York')::date;
SQL
) || fills="?"   # context only — show "?" (not a misleading 0) when unreadable

# ---- classify + alert -------------------------------------------------------
if [ "${#anomalies[@]}" -eq 0 ]; then
  if [ "$degraded" -eq 1 ]; then
    # A check could not run — do NOT claim ALL CLEAR. Distinct exit 2 so a wrapper
    # can page on persistent inability to monitor; no Discord (avoid transient-blip spam).
    log "prod_real DEGRADED — a check could not run (see WARN above); NOT confirmed clear. open=$open_pos fills_today=$fills"
    exit 2
  fi
  log "prod_real ALL CLEAR — open=$open_pos fills_today=$fills; kill switches OK; no 403/flatten/orphan."
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
# Format matches the Java TenantWebhookResolver: ";"-separated "tenant=url" entries
# (NOT JSON, NOT comma-separated). sed strips only the "prod_real=" prefix, so a URL
# containing "=" is preserved (mirrors the resolver's split-on-first-"=").
wh=$($KUBECTL get secret discord-alert-credentials -n "$NS" \
       -o jsonpath='{.data.ALERT_DISCORD_WEBHOOK_URLS}' 2>/dev/null | base64 -d 2>/dev/null \
     | tr ';' '\n' | tr -d '\r' | sed -n 's/^[[:space:]]*prod_real=//p' | head -1)
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
