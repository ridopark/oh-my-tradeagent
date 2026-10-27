#!/usr/bin/env bash
# Fixture tests for scripts/research/watchlist_forward_alert.sh (DRY_RUN, no Discord, no kubectl).
# Drives the ML-scorer checks through the WF_TODAY / WF_DOW overrides so they run on any day, with a
# stub watchlist_forward.py standing in for the frozen scorer's `check` (always passes).
#
# Run standalone:
#   bash scripts/research/tests/test_watchlist_forward_alert.sh
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ALERT="$HERE/../watchlist_forward_alert.sh"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
TODAY=2026-10-26   # a Monday
mkdir -p "$TMP/scripts"
printf 'import sys; print("check: ok"); sys.exit(0)\n' > "$TMP/scripts/watchlist_forward.py"

fails=0
# setup: fresh OUT with today's capture files;
#   $1 = legs.json body ("" = no legs file), $2 = ml_scores.csv body ("" = no file)
setup() {
  rm -rf "$TMP/out" "$TMP/state"; mkdir -p "$TMP/out/raw/$TODAY"
  : > "$TMP/out/raw/$TODAY/stock_1m.ndjson.gz"; : > "$TMP/out/raw/$TODAY/flow.ndjson.gz"
  [ -n "$1" ] && printf '%s' "$1" > "$TMP/out/raw/$TODAY/legs.json"
  [ -n "$2" ] && printf '%s' "$2" > "$TMP/out/ml_scores.csv"
}
# run <name> <expected rc> <substring the output must contain> [substring it must NOT contain]
run() {
  local name=$1 want=$2 need=$3 forbid=${4:-}
  out=$(WF_SCRIPTS="$TMP/scripts" WF_OUT="$TMP/out" WF_ENV=/dev/null WF_STATE_DIR="$TMP/state" \
        WF_TODAY=$TODAY WF_DOW=${DOW:-1} DRY_RUN=1 bash "$ALERT" 2>&1); rc=$?
  if [ "$rc" != "$want" ] || [[ "$out" != *"$need"* ]] || { [ -n "$forbid" ] && [[ "$out" == *"$forbid"* ]]; }; then
    echo "FAIL: $name (rc=$rc, want $want)"; echo "$out" | sed 's/^/    /'; fails=$((fails+1))
  else
    echo "ok: $name"
  fi
}

LEGS='{"legs":[{"s":"AAPL"},{"s":"MSFT"}]}'
HDR='d,s,dir,lvl,status,trig_m,r60,scored,p_tabpfn24,p_gbm14'

# the CSV exists but holds only another day: grep -c prints 0 and exits 1, the case that once produced "0\n0"
setup "$LEGS" "$HDR
2026-10-23,AAPL,ABOVE,1.0,fired,5,0.1,1,0.6,0.5"
run "legs captured, no ML rows today -> alert, no integer-expression noise" 1 \
    "wrote no rows for $TODAY (2 legs" "integer expression"

setup "$LEGS" ""
run "legs captured, no ml_scores.csv at all -> alert" 1 "wrote no rows for $TODAY (2 legs"

setup "$LEGS" "$HDR
$TODAY,AAPL,ABOVE,1.0,fired,5.0,0.1,1.0,,0.5
$TODAY,MSFT,BELOW,2.0,not_fired,,,,,"
run "scored=1.0 without TabPFN -> alert" 1 "1 scored leg(s) have no TabPFN"

setup "$LEGS" "$HDR
$TODAY,AAPL,ABOVE,1.0,fired,5,0.1,1,,0.5
$TODAY,MSFT,BELOW,2.0,fired,7,0.2,1,0.4,0.5"
run "scored=1 (int format, all-fired file) without TabPFN -> alert" 1 "1 scored leg(s) have no TabPFN"

setup "$LEGS" "$HDR
$TODAY,AAPL,ABOVE,1.0,fired,5,0.1,1,0.6,0.5
$TODAY,MSFT,BELOW,2.0,fired,7,0.2,1,0.4,0.5"
run "healthy -> OK" 0 "OK"

LEGS3='{"legs":[{"s":"AAPL"},{"s":"MSFT"},{"s":"NVDA"}]}'
# a partial scoring day: the scorer wrote fewer rows than legs (every leg, scored or not, gets one row)
setup "$LEGS3" "$HDR
$TODAY,AAPL,ABOVE,1.0,fired,5,0.1,1,0.6,0.5
$TODAY,MSFT,BELOW,2.0,not_fired,,,,,"
run "3 legs but only 2 ML rows -> alert names both counts" 1 "wrote 2 rows for $TODAY but 3 legs were captured"

setup "$LEGS3" "$HDR
$TODAY,AAPL,ABOVE,1.0,fired,5,0.1,1,0.6,0.5
$TODAY,MSFT,BELOW,2.0,not_fired,,,,,
$TODAY,NVDA,ABOVE,3.0,armed_after_open,,,,,"
run "3 legs / 3 rows (one unscored, one armed after open) -> OK" 0 "OK" "rows for $TODAY"

setup '{"legs":[]}' ""
run "holiday (no legs, no rows) -> OK" 0 "OK"

setup "$LEGS" ""
DOW=6 run "weekend -> OK" 0 "OK"

setup '{"legs": [' ""
run "corrupt legs.json -> alert" 1 "legs.json is unreadable"

setup "$LEGS" "day,s,scored,p_tabpfn24
$TODAY,AAPL,1,
$TODAY,MSFT,1,0.4"
run "ml_scores.csv missing the d column -> alert (check errored, fail closed)" 1 \
    "ML score check errored for $TODAY: KeyError: 'd'"

[ "$fails" -eq 0 ] && echo "all passed" || { echo "$fails failed"; exit 1; }
