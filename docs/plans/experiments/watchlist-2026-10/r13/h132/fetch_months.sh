#!/usr/bin/env bash
# H13.2 step 2b: fetch SPY 0DTE 1m option bars month by month via wl/fetch.sh (symbol list scp'd to the homelab /tmp).
set -euo pipefail
H=/tmp/claude-1000/-home-ridopark-src-oh-my-tradeagent/de00d765-c1fe-46af-bdde-9acae03716fc/scratchpad/wl/r13/h132
mkdir -p $H/bars
for f in $H/occ_*.txt; do
  m=$(basename $f .txt | sed 's/occ_//'); out=$H/bars/opt_$m.jsonl.gz
  [ -s "$out" ] && continue
  start="${m}-01T00:00:00Z"; end=$(date -u -d "${m}-01 +1 month" +%Y-%m-01T00:00:00Z)
  scp -q $f ridopark@192.168.10.123:/tmp/h132_$m.txt
  echo "$(date +%T) $m $start $end $(tr ',' '\n' < $f | wc -l) syms"
  $H/../../fetch.sh option $start $end 1Min @/tmp/h132_$m.txt > $out.part && mv $out.part $out
  echo "  -> $(zcat $out | wc -l) bars"
done
echo DONE
