# Variant of wl/fetch_remote.py: one Alpaca option-bars request per job line "startISO,endISO,sym1;sym2;..." (read-only data API).
import os, sys, json, gzip, time, requests
kid, sec = os.environ["KID"], os.environ["SEC"]
jobs = [l.strip().split(",") for l in open(sys.argv[1]) if l.strip()]
H = {"APCA-API-KEY-ID": kid, "APCA-API-SECRET-KEY": sec}
url = "https://data.alpaca.markets/v1beta1/options/bars"
out = gzip.open(sys.stdout.buffer, "wt"); nreq = 0
for start, end, syms in jobs:
    tok = None
    while True:
        p = {"symbols": syms.replace(";", ","), "timeframe": "1Min", "start": start, "end": end, "limit": 10000}
        if tok: p["page_token"] = tok
        for attempt in range(6):
            r = requests.get(url, headers=H, params=p, timeout=60); nreq += 1
            if r.status_code == 429: time.sleep(2 + 2 * attempt); continue
            r.raise_for_status(); break
        else: raise SystemExit(f"429 storm at job {start}")
        j = r.json()
        for s, bars in (j.get("bars") or {}).items():
            for b in bars: out.write(json.dumps({"s": s, **b}) + "\n")
        tok = j.get("next_page_token")
        if not tok: break
out.close(); print(f"{len(jobs)} jobs, {nreq} requests", file=sys.stderr)
