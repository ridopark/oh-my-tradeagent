#!/usr/bin/env bash
# equity_bars.sh — fetch Alpaca historical equity (stock) bars for one ticker on one day.
#
# Reusable homelab data primitive, sibling to option_bars.sh: uses the market-data pod's
# READ-ONLY data credentials (APCA_*_DATA), fetched and used entirely on the homelab host
# so the secrets never transit the caller. Only the resulting JSON comes back.
#
# Usage:   scripts/data/equity_bars.sh <ticker> <yyyy-mm-dd> [timeframe] [start_hhmm_utc] [end_hhmm_utc] [feed]
#   e.g.   scripts/data/equity_bars.sh SPY 2026-07-29 5Min
#
# Defaults: timeframe=5Min, window 13:00..20:01 UTC (regular session), feed=iex (basic
#           entitlement; pass sip if the data creds are entitled). Response shape matches
#           option_bars.sh: {"bars": {"<TICKER>": [{t,o,h,l,c,v}, ...]}}.
# Output:   raw Alpaca /v2/stocks/bars JSON on stdout.
# Env:      HOMELAB_SSH (default ridopark@192.168.10.123), COPYTRADE_NS (default copytrade).
set -euo pipefail

HOST="${HOMELAB_SSH:-ridopark@192.168.10.123}"
NS="${COPYTRADE_NS:-copytrade}"

TICKER="${1:?usage: equity_bars.sh <ticker> <yyyy-mm-dd> [timeframe] [start_hhmm] [end_hhmm] [feed]}"
DAY="${2:?day (yyyy-mm-dd) required}"
TF="${3:-5Min}"
START="${4:-13:00}"
END="${5:-20:01}"
FEED="${6:-iex}"

[[ "$TICKER" =~ ^[A-Z.]+$ ]]                 || { echo "equity_bars: bad ticker: $TICKER" >&2; exit 2; }
[[ "$DAY" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || { echo "equity_bars: bad day: $DAY" >&2; exit 2; }
[[ "$TF"  =~ ^[0-9]+(Min|Hour|Day)$ ]]       || { echo "equity_bars: bad timeframe: $TF" >&2; exit 2; }
[[ "$FEED" =~ ^(iex|sip|otc)$ ]]             || { echo "equity_bars: bad feed: $FEED" >&2; exit 2; }

# Credentials are read and consumed on the homelab host; only JSON returns to the caller.
exec ssh "$HOST" NS="$NS" bash -s -- "$TICKER" "$DAY" "$TF" "$START" "$END" "$FEED" <<'REMOTE'
set -euo pipefail
TICKER="$1"; DAY="$2"; TF="$3"; START="$4"; END="$5"; FEED="$6"
POD=$(kubectl -n "$NS" get pod -l app=market-data -o jsonpath='{.items[0].metadata.name}')
[ -n "$POD" ] || { echo "equity_bars: market-data pod not found" >&2; exit 1; }
KID=$(kubectl -n "$NS" exec "$POD" -- printenv APCA_API_KEY_ID_DATA)
SEC=$(kubectl -n "$NS" exec "$POD" -- printenv APCA_API_SECRET_KEY_DATA)
curl -s -H "APCA-API-KEY-ID: $KID" -H "APCA-API-SECRET-KEY: $SEC" \
  "https://data.alpaca.markets/v2/stocks/bars?symbols=${TICKER}&timeframe=${TF}&start=${DAY}T${START}:00Z&end=${DAY}T${END}:00Z&feed=${FEED}&limit=1000"
REMOTE
