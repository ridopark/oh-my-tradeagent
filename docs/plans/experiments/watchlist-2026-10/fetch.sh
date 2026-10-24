#!/usr/bin/env bash
# usage: fetch.sh stock|option start end tf syms_csv > out.jsonl.gz
set -euo pipefail
scp -q "$(dirname "$0")/fetch_remote.py" ridopark@192.168.10.123:/tmp/wl_fetch_remote.py
ssh ridopark@192.168.10.123 bash -s -- "$@" <<'REMOTE'
POD=$(kubectl -n copytrade get pod -l app=market-data -o jsonpath='{.items[0].metadata.name}')
export KID=$(kubectl -n copytrade exec "$POD" -- printenv APCA_API_KEY_ID_DATA)
export SEC=$(kubectl -n copytrade exec "$POD" -- printenv APCA_API_SECRET_KEY_DATA)
python3 /tmp/wl_fetch_remote.py "$@"
REMOTE
