# Remote half of fetch_news.sh (modeled on wl/fetch_remote.py). stdin: lines "SYM,D,start,end"; stdout: gz jsonl of news headlines.
import os, sys, json, gzip, time, requests
H = {"APCA-API-KEY-ID": os.environ["KID"], "APCA-API-SECRET-KEY": os.environ["SEC"]}
out = gzip.open(sys.stdout.buffer, "wt")
for line in sys.stdin.read().split():
    sym, D, start, end = line.split(",")
    tok = None
    while True:
        p = {"symbols": sym, "start": start, "end": end, "limit": 50, "sort": "asc", "include_content": "false"}
        if tok: p["page_token"] = tok
        r = requests.get("https://data.alpaca.markets/v1beta1/news", headers=H, params=p, timeout=60); r.raise_for_status(); j = r.json()
        for n in j.get("news") or []:
            out.write(json.dumps({"q": sym, "D": D, "id": n["id"], "created_at": n["created_at"], "headline": n["headline"], "symbols": n.get("symbols"), "source": n.get("source")}) + "\n")
        tok = j.get("next_page_token")
        if not tok: break
    time.sleep(0.4)
out.close()
