# Remote: per ticker, list Alpaca option contracts, then for each session aggregate call/put 1m volume 09:30-10:29 ET
# for the nearest 2 expiries >= day and strikes within +-5% of the day's official open. Output jsonl rows.
import os, sys, json, requests, datetime as dt
from concurrent.futures import ThreadPoolExecutor
from zoneinfo import ZoneInfo
ET = ZoneInfo("America/New_York")
H = {"APCA-API-KEY-ID": os.environ["KID"], "APCA-API-SECRET-KEY": os.environ["SEC"]}
S = requests.Session(); S.headers.update(H)
tickers = sys.argv[1].split(","); start, end = sys.argv[2], sys.argv[3]  # YYYY-MM-DD

def get(url, p):
    for k in range(5):
        r = S.get(url, params=p, timeout=60)
        if r.status_code == 429: import time; time.sleep(2*(k+1)); continue
        r.raise_for_status(); return r.json()
    r.raise_for_status()

def contracts(tk):
    out = []
    exp_hi = (dt.date.fromisoformat(end) + dt.timedelta(days=21)).isoformat()
    for st in ("inactive", "active"):
        tok = None
        while True:
            p = {"underlying_symbols": tk, "status": st, "expiration_date_gte": start, "expiration_date_lte": exp_hi, "limit": 10000}
            if tok: p["page_token"] = tok
            j = get("https://api.alpaca.markets/v2/options/contracts", p)
            out += [(c["symbol"], c["expiration_date"], c["type"], float(c["strike_price"])) for c in j["option_contracts"] if c["underlying_symbol"] == tk]
            tok = j.get("next_page_token")
            if not tok: break
    return sorted(set(out))

def daily(tk):
    j = get("https://data.alpaca.markets/v2/stocks/bars", {"symbols": tk, "timeframe": "1Day", "start": start, "end": end, "feed": "sip", "adjustment": "raw", "limit": 10000})
    return {b["t"][:10]: b["o"] for b in j["bars"].get(tk, [])}

def day_job(tk, d, spot, cons):
    exps = sorted({e for _, e, _, _ in cons if e >= d})[:2]
    syms = [(s, ty) for s, e, ty, k in cons if e in exps and abs(k/spot - 1) <= 0.05]
    t0 = dt.datetime.fromisoformat(d + "T09:30").replace(tzinfo=ET); t1 = t0 + dt.timedelta(minutes=60)
    agg = {}; nbar = set(); right = dict(syms)
    for i in range(0, len(syms), 100):
        chunk = [s for s, _ in syms[i:i+100]]; tok = None
        while True:
            p = {"symbols": ",".join(chunk), "timeframe": "1Min", "start": t0.isoformat(), "end": t1.isoformat(), "limit": 10000}
            if tok: p["page_token"] = tok
            j = get("https://data.alpaca.markets/v1beta1/options/bars", p)
            for s, bars in (j.get("bars") or {}).items():
                for b in bars:
                    t = dt.datetime.fromisoformat(b["t"].replace("Z", "+00:00")).astimezone(ET)
                    if t.date().isoformat() != d or not (t0 <= t < t1): continue
                    m = (t - t0).seconds // 60; key = (m, right[s])
                    agg[key] = agg.get(key, 0) + b["v"]; nbar.add(s)
            tok = j.get("next_page_token")
            if not tok: break
    return dict(s=tk, d=d, spot=spot, exps=exps, n_listed=len(syms), n_traded=len(nbar),
                m=[[m, r, v] for (m, r), v in sorted(agg.items())])

for tk in tickers:
    cons = contracts(tk); opens = daily(tk)
    with ThreadPoolExecutor(6) as ex:
        res = list(ex.map(lambda kv: day_job(tk, kv[0], kv[1], cons), sorted(opens.items())))
    for r in res: print(json.dumps(r), flush=True)
    print(f"{tk} contracts={len(cons)} days={len(res)}", file=sys.stderr, flush=True)
