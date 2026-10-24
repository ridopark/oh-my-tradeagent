#!/usr/bin/env bash
# usage: fetch_news.sh < queries.txt > news.jsonl.gz   (same remote credential pattern as wl/fetch.sh)
set -euo pipefail
scp -q "$(dirname "$0")/fetch_news_remote.py" ridopark@192.168.10.123:/tmp/wl_fetch_news_remote.py
Q=$(cat)
ssh ridopark@192.168.10.123 bash -s <<REMOTE
POD=\$(kubectl -n copytrade get pod -l app=market-data -o jsonpath='{.items[0].metadata.name}')
export KID=\$(kubectl -n copytrade exec "\$POD" -- printenv APCA_API_KEY_ID_DATA)
export SEC=\$(kubectl -n copytrade exec "\$POD" -- printenv APCA_API_SECRET_KEY_DATA)
echo "$Q" | python3 /tmp/wl_fetch_news_remote.py
REMOTE
