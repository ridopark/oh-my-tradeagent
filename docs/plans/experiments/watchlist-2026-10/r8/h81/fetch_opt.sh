#!/usr/bin/env bash
# Same credential path as wl/fetch.sh (read-only market-data API keys); runs the multi-date driver remotely.
set -euo pipefail
D="$(dirname "$0")"
scp -q "$D/fetch_opt_remote.py" "$D/occ_jobs.json" ridopark@192.168.10.123:/tmp/
ssh ridopark@192.168.10.123 bash -s <<'REMOTE'
POD=$(kubectl -n copytrade get pod -l app=market-data -o jsonpath='{.items[0].metadata.name}')
export KID=$(kubectl -n copytrade exec "$POD" -- printenv APCA_API_KEY_ID_DATA)
export SEC=$(kubectl -n copytrade exec "$POD" -- printenv APCA_API_SECRET_KEY_DATA)
python3 /tmp/fetch_opt_remote.py /tmp/occ_jobs.json
REMOTE
