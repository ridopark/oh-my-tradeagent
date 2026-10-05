#!/usr/bin/env python3
"""Forward NBBO collector for 0DTE option chains (research asset; quotes cannot be backfilled — #783).

Polls Alpaca /v1beta1/options/snapshots/{underlying} for today's-expiry contracts on SPY, QQQ, XSP
every --interval seconds during RTH (09:25-16:10 ET, trading weekdays), appending one gzipped
ndjson row per contract per cycle: {ts, sym, bp, bs, ap, as, lp, lt} plus one underlying row.
Read-only REST on the DATA key (opra feed; entitlement verified 2026-10-04). ~3 underlyings x
1-2 pages / cycle at 60s ≈ well under 1% of the 10k/min REST budget.

Usage: option_quote_collector.py --out /data/option-quotes [--underlyings SPY,QQQ,XSP]
       [--interval 60] [--once]
Deploy (operator): homelab crontab @reboot or k8s Deployment; session crons die with the session
(see reference_prod_real_monitoring). Files: <out>/<UNDERLYING>/<YYYY-MM-DD>.ndjson.gz
"""
import argparse, gzip, json, os, sys, time, urllib.request, urllib.parse
from datetime import datetime, date
from zoneinfo import ZoneInfo

ET = ZoneInfo("America/New_York")

def env_creds(env_file):
    kv = {}
    for line in open(env_file):
        line = line.strip()
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            kv[k] = v.strip().strip("\"'")  # last wins, matching shell source semantics
    return kv["APCA_API_KEY_ID_DATA"], kv["APCA_API_SECRET_KEY_DATA"]

def get(url, headers, tries=5):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as r:
                return json.load(r)
        except Exception:
            if i == tries - 1:
                raise
            time.sleep(2 ** i)

def snapshot_rows(u, headers, now_iso):
    rows, token = [], None
    exp = datetime.now(ET).strftime("%Y-%m-%d")
    while True:
        q = {"feed": "opra", "limit": 1000, "expiration_date": exp}
        if token:
            q["page_token"] = token
        d = get(f"https://data.alpaca.markets/v1beta1/options/snapshots/{u}?"
                + urllib.parse.urlencode(q), headers)
        for sym, s in (d.get("snapshots") or {}).items():
            qt, tr = s.get("latestQuote") or {}, s.get("latestTrade") or {}
            rows.append({"ts": now_iso, "sym": sym, "bp": qt.get("bp"), "bs": qt.get("bs"),
                         "ap": qt.get("ap"), "as": qt.get("as"), "lp": tr.get("p"),
                         "lt": tr.get("t")})
        token = d.get("next_page_token")
        if not token:
            return rows

def underlying_row(u, headers, now_iso):
    sym = "SPY" if u == "XSP" else u  # XSP itself does not trade; SPY is the spot proxy
    d = get(f"https://data.alpaca.markets/v2/stocks/{sym}/snapshot?feed=sip", headers)
    qt = d.get("latestQuote") or {}
    return {"ts": now_iso, "sym": f"_UND_{sym}", "bp": qt.get("bp"), "bs": qt.get("bs"),
            "ap": qt.get("ap"), "as": qt.get("as"),
            "lp": (d.get("latestTrade") or {}).get("p"), "lt": None}

def in_session(now):
    return now.weekday() < 5 and "09:25" <= now.strftime("%H:%M") <= "16:10"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--underlyings", default="SPY,QQQ,XSP")
    ap.add_argument("--interval", type=int, default=60)
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--env", default=os.path.join(os.path.dirname(__file__), "../../.env"))
    a = ap.parse_args()
    key, sec = env_creds(a.env)
    headers = {"APCA-API-KEY-ID": key, "APCA-API-SECRET-KEY": sec}
    while True:
        now = datetime.now(ET)
        if a.once or in_session(now):
            now_iso = now.isoformat(timespec="seconds")
            for u in a.underlyings.split(","):
                try:
                    rows = snapshot_rows(u, headers, now_iso) + [underlying_row(u, headers, now_iso)]
                    day_dir = os.path.join(a.out, u)
                    os.makedirs(day_dir, exist_ok=True)
                    with gzip.open(os.path.join(day_dir, f"{now.date()}.ndjson.gz"), "at") as f:
                        for r in rows:
                            f.write(json.dumps(r) + "\n")
                    print(f"{now_iso} {u}: {len(rows)} rows", flush=True)
                except Exception as e:
                    print(f"{now_iso} {u}: ERROR {e}", file=sys.stderr, flush=True)
        if a.once:
            return
        time.sleep(a.interval)

if __name__ == "__main__":
    main()
