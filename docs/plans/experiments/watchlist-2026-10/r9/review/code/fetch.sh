#!/bin/bash
# usage: fetch.sh < requests.jsonl > out.jsonl
ssh -o BatchMode=yes ridopark@192.168.10.123 'POD=$(kubectl -n copytrade get pod -l app=market-data -o jsonpath="{.items[0].metadata.name}"); K=$(kubectl -n copytrade exec $POD -- printenv APCA_API_KEY_ID_DATA) S=$(kubectl -n copytrade exec $POD -- printenv APCA_API_SECRET_KEY_DATA) W='${W:-6}' python3 /tmp/rev9_rfetch.py'
