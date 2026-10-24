import os, sys, json, gzip, time, requests
H = {"APCA-API-KEY-ID": os.environ["KID"], "APCA-API-SECRET-KEY": os.environ["SEC"]}
out = gzip.open(sys.stdout.buffer, "wt"); n_win = 0
for w in json.load(open(sys.argv[1])):
    tok = None; n_win += 1
    while True:
        p = {"start": w["start"], "end": w["end"], "limit": 10000, "feed": "sip"}
        if tok: p["page_token"] = tok
        for attempt in range(6):
            try:
                r = requests.get(f"https://data.alpaca.markets/v2/stocks/{w['sym']}/trades", headers=H, params=p, timeout=60)
                if r.status_code == 429: time.sleep(2 ** attempt); continue
                r.raise_for_status(); j = r.json(); break
            except Exception as e:
                print(f"retry {attempt} {w['sym']} {w['d']}: {e}", file=sys.stderr); time.sleep(2 ** attempt)
        else:
            print(f"GAVE UP {w['sym']} {w['d']}", file=sys.stderr); break
        for t in j.get("trades") or []:
            out.write(json.dumps({"sym": w["sym"], "d": w["d"], "dir": w["dir"], "lvl": w["lvl"], "t": t["t"], "p": t["p"], "s": t["s"], "c": t.get("c")}) + "\n")
        tok = j.get("next_page_token")
        if not tok: break
out.close(); print(f"done {n_win} windows", file=sys.stderr)
