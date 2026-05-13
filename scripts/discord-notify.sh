#!/usr/bin/env bash
# discord-notify.sh — Send a message to Discord via webhook
# Usage: ./scripts/discord-notify.sh "title" "message" [red|yellow|green]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT_DIR/.env" ] && source "$ROOT_DIR/.env"

URL="${DISCORD_WEBHOOK_URL:-}"
[ -z "$URL" ] && { echo "DISCORD_WEBHOOK_URL not set" >&2; exit 1; }

TITLE="${1:-Alert}"
MSG="${2:-No details}"
COLOR_NAME="${3:-yellow}"

case "$COLOR_NAME" in
  red)    COLOR=16711680 ;;
  green)  COLOR=65280 ;;
  *)      COLOR=16776960 ;;  # yellow
esac

curl -sf -H "Content-Type: application/json" -d "{
  \"embeds\": [{
    \"title\": $(printf '%s' "$TITLE" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'),
    \"description\": $(printf '%s' "$MSG" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'),
    \"color\": $COLOR
  }]
}" "$URL" > /dev/null
