#!/usr/bin/env python3
"""Daily open-interest snapshot for a ticker universe (research asset; OI is not in any history we have).

Once a day (crontab, pre-market), GET /v2/options/contracts per underlying for expiries within --expiry-days
and append one row per contract to <out>/<UNDERLYING>/<YYYY-MM-DD>.ndjson.gz:
{d, sym, oi, oi_date, strike, expiry, type, close, close_date}. With the trade tape from
option_quote_collector.py --tape-min-size, day-over-day OI change says whether large prints opened or
closed positions. Read-only; one paginated call per underlying (~60 calls/day).
A re-run on the same day REPLACES that day's file per underlying (written to a temp file, then renamed), so a
manual or duplicate cron run never doubles the rows. Exits 1 if any underlying failed, so cron sees the failure.

Usage: option_oi_collector.py --out /data/option-oi --universe-file names.txt [--expiry-days 60]
"""
import argparse, gzip, json, os, sys, urllib.parse
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from option_quote_collector import env_creds, get, read_universe  # noqa: E402

ET = ZoneInfo("America/New_York")

def oi_rows(contracts, today):
    """Shape /v2/options/contracts entries into the stored row (missing OI stays None, never 0)."""
    rows = []
    for c in contracts or []:
        oi = c.get("open_interest")
        rows.append({"d": today, "sym": c.get("symbol"), "oi": int(oi) if oi not in (None, "") else None,
                     "oi_date": c.get("open_interest_date"), "strike": float(c["strike_price"]) if c.get("strike_price") else None,
                     "expiry": c.get("expiration_date"), "type": c.get("type"),
                     "close": float(c["close_price"]) if c.get("close_price") else None, "close_date": c.get("close_price_date")})
    return rows

def contracts(u, headers, today, expiry_days):
    out, token = [], None
    while True:
        q = {"underlying_symbols": u, "status": "active", "expiration_date_gte": today.isoformat(),
             "expiration_date_lte": (today + timedelta(days=expiry_days)).isoformat(), "limit": 10000}
        if token:
            q["page_token"] = token
        d = get("https://api.alpaca.markets/v2/options/contracts?" + urllib.parse.urlencode(q), headers)
        out += [c for c in d.get("option_contracts") or [] if c.get("underlying_symbol") == u]
        token = d.get("next_page_token")
        if not token:
            return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--universe-file", required=True)
    ap.add_argument("--expiry-days", type=int, default=60)
    ap.add_argument("--env", default=os.path.join(os.path.dirname(__file__), "../../.env"))
    a = ap.parse_args()
    key, sec = env_creds(a.env)
    headers = {"APCA-API-KEY-ID": key, "APCA-API-SECRET-KEY": sec}
    today = datetime.now(ET).date()
    failed = 0
    for u in read_universe(a.universe_file):
        try:
            rows = oi_rows(contracts(u, headers, today, a.expiry_days), today.isoformat())
            day_dir = os.path.join(a.out, u)
            os.makedirs(day_dir, exist_ok=True)
            path = os.path.join(day_dir, f"{today}.ndjson.gz")
            with gzip.open(path + ".tmp", "wt") as f:
                for r in rows:
                    f.write(json.dumps(r) + "\n")
            os.replace(path + ".tmp", path)  # whole-day snapshot: a re-run replaces, never appends
            print(f"{today} {u}: {len(rows)} contracts", flush=True)
        except Exception as e:
            failed += 1
            print(f"{today} {u}: ERROR {e}", file=sys.stderr, flush=True)
    if failed:
        sys.exit(1)

if __name__ == "__main__":
    main()
