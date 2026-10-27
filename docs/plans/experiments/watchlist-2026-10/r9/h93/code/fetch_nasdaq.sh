#!/usr/bin/env bash
# Fetch Nasdaq earnings calendar (public, no credentials) for every weekday in [start,end] into raw/nasdaq_YYYY-MM-DD.json
set -euo pipefail
OUT=$(dirname "$0")/../raw; mkdir -p "$OUT"
d=$1
while [[ "$d" < "$2" || "$d" == "$2" ]]; do
  if [[ $(date -d "$d" +%u) -le 5 && ! -s "$OUT/nasdaq_$d.json" ]]; then
    curl -s -m 30 -A "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36" \
      -H "Accept: application/json" "https://api.nasdaq.com/api/calendar/earnings?date=$d" -o "$OUT/nasdaq_$d.json"
    sleep 1.5
  fi
  d=$(date -d "$d +1 day" +%F)
done
