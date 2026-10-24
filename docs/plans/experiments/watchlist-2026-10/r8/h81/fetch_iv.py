# Fetch prior-day ATM-candidate rows from DoltHub option_chain, one cache file per chain date.
import json, gzip, time, sys, os, urllib.parse, urllib.request, datetime as dt
import pandas as pd
O = os.path.dirname(os.path.abspath(__file__))
syms = open(f"{O}/syms.txt").read().strip().split(",")
D = pd.read_json(f"{O}/daily.jsonl.gz", lines=True)
D["d"] = pd.to_datetime(D.t).dt.tz_convert("America/New_York").dt.strftime("%Y-%m-%d")
days = sorted(D[D.s == "SPY"].d)
chain_days = [d for i, d in enumerate(days) if i + 1 < len(days) and days[i + 1] >= "2026-06-22"]
URL = "https://www.dolthub.com/api/v1alpha1/post-no-preference/options/master?q="
def q(sql, tries=4):
    for k in range(tries):
        try:
            j = json.load(urllib.request.urlopen(URL + urllib.parse.quote(sql), timeout=90))
            if j["query_execution_status"] == "Success": return j["rows"]
            if j["query_execution_status"] == "RowLimit": raise OverflowError(sql[:80])
            print("ERR", j["query_execution_message"], file=sys.stderr)
        except OverflowError: raise
        except Exception as e: print("EXC", e, file=sys.stderr)
        time.sleep(5 * (k + 1))
    raise RuntimeError(sql[:200])
inl = lambda xs: ",".join(f"'{x}'" for x in xs)
for d in chain_days:
    f = f"{O}/chain/{d}.json"
    if os.path.exists(f): continue
    e7 = (dt.date.fromisoformat(d) + dt.timedelta(days=7)).isoformat()
    e37 = (dt.date.fromisoformat(d) + dt.timedelta(days=37)).isoformat()
    rows = []
    def grab(ss):
        try:
            rows.extend(q(f"select act_symbol, expiration, strike, call_put, bid, ask, vol, delta from option_chain where date='{d}' and act_symbol in ({inl(ss)}) and expiration between '{e7}' and '{e37}' and abs(delta) between 0.3 and 0.7"))
        except OverflowError:
            h = len(ss) // 2; grab(ss[:h]); grab(ss[h:])
        time.sleep(0.5)
    for i in range(0, len(syms), 25): grab(syms[i:i + 25])
    mins = {}
    for r in rows: mins[r["act_symbol"]] = min(mins.get(r["act_symbol"], "9999"), r["expiration"])
    mins = [{"act_symbol": k, "e": v} for k, v in sorted(mins.items())]
    json.dump({"date": d, "mins": mins, "rows": rows}, open(f, "w"))
    print(d, len(mins), len(rows), flush=True)
