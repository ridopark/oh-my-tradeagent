import os, sys, json, gzip, requests
H = {"APCA-API-KEY-ID": os.environ["KID"], "APCA-API-SECRET-KEY": os.environ["SEC"]}
out = gzip.open(sys.stdout.buffer, "wt")
for w in json.load(open(sys.argv[1])):
    tok = None
    while True:
        p = {"start": w["start"], "end": w["end"], "limit": 10000, "feed": "sip"}
        if tok: p["page_token"] = tok
        j = requests.get(f"https://data.alpaca.markets/v2/stocks/{w['sym']}/trades", headers=H, params=p, timeout=60).json()
        for t in j.get("trades") or []:
            out.write(json.dumps({"sym": w["sym"], "d": w["d"], "dir": w["dir"], "lvl": w["lvl"], "t": t["t"], "p": t["p"], "s": t["s"], "c": t.get("c")}) + "\n")
        tok = j.get("next_page_token")
        if not tok: break
out.close()
