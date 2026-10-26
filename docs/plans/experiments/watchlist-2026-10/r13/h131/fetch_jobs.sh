#!/usr/bin/env bash
# usage: fetch_jobs.sh jobs.txt > out.jsonl.gz   (mirrors wl/fetch.sh; creds come from the market-data pod env, read-only)
set -euo pipefail
scp -q "$(dirname "$0")/fetch_remote_jobs.py" ridopark@192.168.10.123:/tmp/wl_fetch_remote_jobs.py
scp -q "$1" ridopark@192.168.10.123:/tmp/wl_h131_jobs.txt
ssh ridopark@192.168.10.123 bash -s <<'REMOTE'
POD=$(kubectl -n copytrade get pod -l app=market-data -o jsonpath='{.items[0].metadata.name}')
export KID=$(kubectl -n copytrade exec "$POD" -- printenv APCA_API_KEY_ID_DATA)
export SEC=$(kubectl -n copytrade exec "$POD" -- printenv APCA_API_SECRET_KEY_DATA)
python3 /tmp/wl_fetch_remote_jobs.py /tmp/wl_h131_jobs.txt
REMOTE
