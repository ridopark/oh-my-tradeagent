import os, sys, json, gzip, requests
kid, sec = os.environ["KID"], os.environ["SEC"]
kind, start, end, tf = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
syms = (open(sys.argv[5][1:]).read() if sys.argv[5].startswith("@") else sys.argv[5]).strip().split(",")
H = {"APCA-API-KEY-ID": kid, "APCA-API-SECRET-KEY": sec}
url = {"stock":"https://data.alpaca.markets/v2/stocks/bars","option":"https://data.alpaca.markets/v1beta1/options/bars","otrades":"https://data.alpaca.markets/v1beta1/options/trades"}[kind]
key = "trades" if kind == "otrades" else "bars"
out = gzip.open(sys.stdout.buffer, "wt")
for i in range(0, len(syms), 20 if kind=="stock" else 100):
    chunk = syms[i:i+(20 if kind=="stock" else 100)]
    tok = None
    while True:
        p = {"symbols": ",".join(chunk), **({} if kind=="otrades" else {"timeframe": tf}), "start": start, "end": end, "limit": 10000}
        if kind == "stock": p.update(feed="sip", adjustment="all")
        if tok: p["page_token"] = tok
        r = requests.get(url, headers=H, params=p, timeout=60); r.raise_for_status(); j = r.json()
        for s, bars in (j.get(key) or {}).items():
            for b in bars: out.write(json.dumps({"sym": s, **b} if kind == "otrades" else {"s": s, **b}) + "\n")
        tok = j.get("next_page_token")
        if not tok: break
out.close()
