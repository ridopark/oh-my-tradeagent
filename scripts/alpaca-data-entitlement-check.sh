#!/usr/bin/env bash
# Read-only Alpaca data-entitlement probe. REST ONLY — opens no WebSocket, so it
# cannot disturb the live stocks stream. No orders, no writes.
#
# Asks one question: is the DATA key entitled to the paid feeds (opra options /
# sip stocks), i.e. Algo Trader Plus, or only the free Basic feeds?
set -uo pipefail

ENV_FILE="${1:-/home/ridopark/src/oh-my-tradeagent/.env}"

# LAST-wins, matching shell `source` / Spring relaxed binding — same rule
# scripts/alpaca-ws-conn-check.py uses.
KEY=$(grep -E '^APCA_API_KEY_ID_DATA=' "$ENV_FILE" | tail -1 | cut -d= -f2- | tr -d "\"' ")
SEC=$(grep -E '^APCA_API_SECRET_KEY_DATA=' "$ENV_FILE" | tail -1 | cut -d= -f2- | tr -d "\"' ")

if [ -z "$KEY" ] || [ -z "$SEC" ]; then
  echo "ERROR: no APCA_API_KEY_ID_DATA / APCA_API_SECRET_KEY_DATA in $ENV_FILE" >&2
  exit 2
fi

echo "data key : ${KEY:0:6}…  (prefix ${KEY:0:2} = $([ "${KEY:0:2}" = AK ] && echo live || echo other))"
echo "(read-only REST; no websocket, no orders)"
echo

probe() {
  local label="$1" url="$2"
  local out code body
  out=$(curl -sS -m 20 -w '\n%{http_code}' \
    -H "APCA-API-KEY-ID: $KEY" -H "APCA-API-SECRET-KEY: $SEC" \
    "$url" 2>&1)
  code=$(printf '%s' "$out" | tail -1)
  body=$(printf '%s' "$out" | head -c 220 | tr '\n' ' ')
  printf '%-34s -> HTTP %s\n' "$label" "$code"
  # Show the body on anything non-200 (that is where the entitlement error lives),
  # and a short prefix on 200 to prove real data came back.
  if [ "$code" != "200" ]; then
    printf '    %s\n' "$body"
  else
    printf '    %s…\n' "$(printf '%s' "$body" | head -c 120)"
  fi
}

echo "--- OPTIONS feed ---"
probe "options snapshots feed=indicative" \
  "https://data.alpaca.markets/v1beta1/options/snapshots/SPY?feed=indicative&limit=1"
probe "options snapshots feed=opra" \
  "https://data.alpaca.markets/v1beta1/options/snapshots/SPY?feed=opra&limit=1"

echo
echo "--- STOCKS feed (same plan tier) ---"
probe "stocks snapshot feed=iex" \
  "https://data.alpaca.markets/v2/stocks/SPY/snapshot?feed=iex"
probe "stocks snapshot feed=sip" \
  "https://data.alpaca.markets/v2/stocks/SPY/snapshot?feed=sip"

echo
echo "READ: opra 200 + sip 200  => Algo Trader Plus (paid) is active."
echo "      opra/sip 403 or 'subscription does not permit' => still on Basic (free)."
