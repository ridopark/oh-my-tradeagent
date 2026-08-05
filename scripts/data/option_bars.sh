#!/usr/bin/env bash
# option_bars.sh — fetch Alpaca historical option bars for one OCC symbol on one day.
#
# Reusable homelab data primitive: uses the market-data pod's READ-ONLY data
# credentials (APCA_*_DATA), fetched and used entirely on the homelab host so the
# secrets never transit the caller. Only the resulting JSON comes back.
#
# Usage:   scripts/data/option_bars.sh <compact_occ> <yyyy-mm-dd> [timeframe] [start_hhmm_utc] [end_hhmm_utc]
#   e.g.   scripts/data/option_bars.sh SPY260731P00734000 2026-07-29 5Min
#
# Defaults: timeframe=5Min, window 13:00..20:01 UTC (regular session; end excludes
#           thin after-hours prints past the 16:00 ET close).
# Output:   raw Alpaca /v1beta1/options/bars JSON on stdout.
# Env:      HOMELAB_SSH (default ridopark@192.168.10.123), COPYTRADE_NS (default copytrade).
set -euo pipefail

HOST="${HOMELAB_SSH:-ridopark@192.168.10.123}"
NS="${COPYTRADE_NS:-copytrade}"

OCC="${1:?usage: option_bars.sh <compact_occ> <yyyy-mm-dd> [timeframe] [start_hhmm] [end_hhmm]}"
DAY="${2:?day (yyyy-mm-dd) required}"
TF="${3:-5Min}"
START="${4:-13:00}"
END="${5:-20:01}"

[[ "$OCC" =~ ^[A-Z0-9]+$ ]]              || { echo "option_bars: bad OCC (compact, no spaces): $OCC" >&2; exit 2; }
[[ "$DAY" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || { echo "option_bars: bad day: $DAY" >&2; exit 2; }
[[ "$TF"  =~ ^[0-9]+(Min|Hour|Day)$ ]]  || { echo "option_bars: bad timeframe: $TF" >&2; exit 2; }
# START/END and NS are interpolated into the ssh-evaluated remote command, so validate them the same
# way as every other parameter — otherwise e.g. END='x;id' would run on the homelab host.
[[ "$START" =~ ^[0-2][0-9]:[0-5][0-9]$ ]] || { echo "option_bars: bad start (hh:mm utc): $START" >&2; exit 2; }
[[ "$END"   =~ ^[0-2][0-9]:[0-5][0-9]$ ]] || { echo "option_bars: bad end (hh:mm utc): $END" >&2; exit 2; }
[[ "$NS"    =~ ^[a-z0-9-]+$ ]]            || { echo "option_bars: bad namespace: $NS" >&2; exit 2; }

# Credentials are read and consumed on the homelab host; only JSON returns to the caller.
exec ssh "$HOST" NS="$NS" bash -s -- "$OCC" "$DAY" "$TF" "$START" "$END" <<'REMOTE'
set -euo pipefail
OCC="$1"; DAY="$2"; TF="$3"; START="$4"; END="$5"
POD=$(kubectl -n "$NS" get pod -l app=market-data -o jsonpath='{.items[0].metadata.name}')
[ -n "$POD" ] || { echo "option_bars: market-data pod not found" >&2; exit 1; }
KID=$(kubectl -n "$NS" exec "$POD" -- printenv APCA_API_KEY_ID_DATA)
SEC=$(kubectl -n "$NS" exec "$POD" -- printenv APCA_API_SECRET_KEY_DATA)
curl -s -H "APCA-API-KEY-ID: $KID" -H "APCA-API-SECRET-KEY: $SEC" \
  "https://data.alpaca.markets/v1beta1/options/bars?symbols=${OCC}&timeframe=${TF}&start=${DAY}T${START}:00Z&end=${DAY}T${END}:00Z&limit=1000"
REMOTE
