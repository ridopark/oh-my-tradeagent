# Remote driver: for each trade date, fetch 1Min option bars for that date's candidate OCC list (date-bounded window).
import os, sys, json, gzip, requests, time
H = {"APCA-API-KEY-ID": os.environ["KID"], "APCA-API-SECRET-KEY": os.environ["SEC"]}
jobs = json.load(open(sys.argv[1])); out = gzip.open(sys.stdout.buffer, "wt")
for d, syms in sorted(jobs.items()):
    for i in range(0, len(syms), 100):
        tok = None
        while True:
            p = {"symbols": ",".join(syms[i:i+100]), "timeframe": "1Min", "start": f"{d}T13:00:00Z", "end": f"{d}T21:00:00Z", "limit": 10000}
            if tok: p["page_token"] = tok
            r = requests.get("https://data.alpaca.markets/v1beta1/options/bars", headers=H, params=p, timeout=60); r.raise_for_status(); j = r.json()
            for s, bars in (j.get("bars") or {}).items():
                for b in bars: out.write(json.dumps({"s": s, "d": d, **b}) + "\n")
            tok = j.get("next_page_token")
            if not tok: break
out.close()
