#!/usr/bin/env bash
# usage: run_remote.sh TICKERS_CSV START END > out.jsonl
set -euo pipefail
scp -q "$(dirname "$0")/flow_remote.py" ridopark@192.168.10.123:/tmp/h92_flow_remote.py
ssh ridopark@192.168.10.123 bash -s -- "$@" <<'REMOTE'
POD=$(kubectl -n copytrade get pod -l app=market-data -o jsonpath='{.items[0].metadata.name}')
export KID=$(kubectl -n copytrade exec "$POD" -- printenv APCA_API_KEY_ID_DATA)
export SEC=$(kubectl -n copytrade exec "$POD" -- printenv APCA_API_SECRET_KEY_DATA)
python3 /tmp/h92_flow_remote.py "$@"
REMOTE
