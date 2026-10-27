#!/usr/bin/env python3
"""Forward NBBO collector for 0DTE option chains (research asset; quotes cannot be backfilled — #783).

Polls Alpaca /v1beta1/options/snapshots/{underlying} for today's-expiry contracts on SPY, QQQ, XSP
every --interval seconds during RTH (09:25-16:10 ET, trading weekdays), appending one gzipped
ndjson row per contract per cycle: {ts, sym, bp, bs, ap, as, lp, lt} plus one underlying row.
Read-only REST on the DATA key (opra feed; entitlement verified 2026-10-04). ~3 underlyings x
1-2 pages / cycle at 60s ≈ well under 1% of the 10k/min REST budget.

Usage: option_quote_collector.py --out /data/option-quotes [--underlyings SPY,QQQ,XSP]
       [--interval 60] [--once]
       option_quote_collector.py --out /data/option-quotes-watchlist --watchlist [--universe-file names.txt]
       --watchlist: instead of 0DTE chains, poll today's watchlist tickers (audit TriggerArmed,
       refreshed every 10 min and on a new ET date) — every expiry within 10 calendar days (0-10 DTE:
       covers next Friday's weekly and the author's ~8-DTE median), strikes within +-5% of spot.
       A ticker with no usable spot (no last trade and no ask) is skipped for that cycle.
       --universe-file: also poll these tickers every cycle (one per line; '#' comments), unioned with the
       day's watchlist, so fills can later be replayed on names before/whether they are listed. ~2.5 REST
       calls per ticker per cycle; 60 names ≈ 1.5% of the 10k/min budget. Quotes cannot be backfilled. (PREREG-2026-10-23-watchlist-forward-v1: listed contracts + neighbours at real NBBO).
       --tape-min-size N (watchlist mode): also pull every option TRADE since the previous cycle for the polled
       contracts (/v1beta1/options/trades, 100 symbols per call) and append prints of N+ contracts to
       <out>/<U>/<date>.trades.ndjson.gz, each stamped with the cycle-end NBBO of its contract so the side
       (at ask = bought, at bid = sold) can be inferred later. Volume itself is backfillable from bars; the
       quote at the print is not. ~1-2 extra calls per ticker per cycle. 0 (default) = off.
       CAVEATS (best-effort tape): the stamp is the quote at the END of the cycle, up to --interval seconds
       AFTER the print (qts - ts = the staleness; a large print can itself move the quote, so side inference
       is noisy for prints far from qts). Alpaca treats start and end as inclusive and the window edges are
       whole seconds, so a print timestamped exactly on a cycle boundary can appear in two cycles; prints
       the SIP reports after the window closed are lost. Per ticker the window restarts each ET day.
Deploy (operator): homelab crontab @reboot or k8s Deployment; session crons die with the session
(see reference_prod_real_monitoring). Files: <out>/<UNDERLYING>/<YYYY-MM-DD>.ndjson.gz
"""
import argparse, gzip, json, os, shlex, subprocess, sys, time, urllib.request, urllib.parse
from datetime import datetime, date, timedelta
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

DEFAULT_PSQL = "kubectl exec -i -n copytrade postgres-0 -- psql -U temporal -d orchestrator -tA"

def snapshot_params(today, spot=None, expiry_days=10, band=0.05):
    """0DTE chain by default; with a spot, the watchlist window (expiry range + strike band)."""
    if spot is None:
        return {"feed": "opra", "limit": 1000, "expiration_date": today.isoformat()}
    return {"feed": "opra", "limit": 1000, "expiration_date_gte": today.isoformat(),
            "expiration_date_lte": (today + timedelta(days=expiry_days)).isoformat(),
            "strike_price_gte": round(spot * (1 - band), 2), "strike_price_lte": round(spot * (1 + band), 2)}

def watchlist_spot(und):
    """Spot for the strike band, or None when the snapshot has neither a last trade nor an ask —
    the caller must then SKIP the ticker (snapshot_params(today, None) is the 0DTE default query)."""
    spot = und.get("lp") or und.get("ap")
    return spot if spot and spot > 0 else None

def read_universe(path):
    """Fixed ticker universe: one per line, blank lines and '#' comments ignored, upper-cased, deduped."""
    if not path:
        return []
    with open(path) as f:
        return sorted({l.split("#", 1)[0].strip().upper() for l in f} - {""})

def poll_set(watchlist, universe):
    """Tickers to poll this cycle: today's watchlist unioned with the fixed universe, sorted."""
    return sorted(set(watchlist) | set(universe))

def watchlist_tickers(psql_cmd, today):
    q = ("SELECT DISTINCT subject->>'ticker' FROM audit_log WHERE kind='TriggerArmed' AND "
         f"(occurred_at AT TIME ZONE 'America/New_York')::date = '{today.isoformat()}';")
    r = subprocess.run(shlex.split(psql_cmd), input=q, capture_output=True, text=True, check=True)
    return sorted(t.strip() for t in r.stdout.splitlines() if t.strip())

def snapshot_rows(u, headers, now_iso, params=None):
    rows, token = [], None
    base = params or snapshot_params(datetime.now(ET).date())
    while True:
        q = dict(base)
        if token:
            q["page_token"] = token
        d = get(f"https://data.alpaca.markets/v1beta1/options/snapshots/{urllib.parse.quote(u)}?"
                + urllib.parse.urlencode(q), headers)
        for sym, s in (d.get("snapshots") or {}).items():
            qt, tr = s.get("latestQuote") or {}, s.get("latestTrade") or {}
            rows.append({"ts": now_iso, "sym": sym, "bp": qt.get("bp"), "bs": qt.get("bs"),
                         "ap": qt.get("ap"), "as": qt.get("as"), "lp": tr.get("p"),
                         "lt": tr.get("t")})
        token = d.get("next_page_token")
        if not token:
            return rows

def tape_rows(trades_by_sym, quotes, min_size):
    """Prints of min_size+ contracts with the cycle-end NBBO of their contract stamped on (trades carry no side).
    trades_by_sym: the 'trades' map of /v1beta1/options/trades; quotes: {sym: this cycle's snapshot row}.
    The stamp postdates the print by up to one interval (row['qts'] - row['ts']); it is not the quote AT the print."""
    rows = []
    for sym, trades in (trades_by_sym or {}).items():
        q = quotes.get(sym) or {}
        for t in trades:
            if (t.get("s") or 0) >= min_size:
                rows.append({"ts": t.get("t"), "sym": sym, "p": t.get("p"), "s": t.get("s"), "x": t.get("x"),
                             "c": t.get("c"), "bp": q.get("bp"), "ap": q.get("ap"), "qts": q.get("ts")})
    return rows

def trade_rows(syms, headers, start_iso, end_iso, quotes, min_size):
    rows = []
    for i in range(0, len(syms), 100):
        token = None
        while True:
            q = {"symbols": ",".join(syms[i:i + 100]), "start": start_iso, "end": end_iso, "limit": 10000}
            if token:
                q["page_token"] = token
            d = get("https://data.alpaca.markets/v1beta1/options/trades?" + urllib.parse.urlencode(q), headers)
            rows += tape_rows(d.get("trades"), quotes, min_size)
            token = d.get("next_page_token")
            if not token:
                break
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
    ap.add_argument("--watchlist", action="store_true")
    ap.add_argument("--psql", default=DEFAULT_PSQL)
    ap.add_argument("--universe-file", default=None)
    ap.add_argument("--tape-min-size", type=int, default=0,
                    help="watchlist mode: also record option prints of this many contracts or more (0 = off)")
    a = ap.parse_args()
    key, sec = env_creds(a.env)
    headers = {"APCA-API-KEY-ID": key, "APCA-API-SECRET-KEY": sec}
    tickers, refreshed, refreshed_day = [], 0.0, None
    tape_since, tape_day = {}, None  # ticker -> end of the last trade window pulled; reset on a new ET day
    universe = read_universe(a.universe_file)
    while True:
        now = datetime.now(ET)
        if a.once or in_session(now):
            now_iso = now.isoformat(timespec="seconds"); cycle_t0 = time.time()
            if tape_day != now.date():
                tape_since, tape_day = {}, now.date()
            # refresh every 10 min, on a new ET date, and every cycle while the list is still empty
            if a.watchlist and (not tickers or refreshed_day != now.date() or time.time() - refreshed > 600):
                try:
                    tickers, refreshed, refreshed_day = watchlist_tickers(a.psql, now.date()), time.time(), now.date()
                except Exception as e:
                    print(f"{now_iso} watchlist lookup ERROR {e}", file=sys.stderr, flush=True)
            for u in (poll_set(tickers, universe) if a.watchlist else a.underlyings.split(",")):
                try:
                    if a.watchlist:
                        und = underlying_row(u, headers, now_iso)
                        spot = watchlist_spot(und)
                        if spot is None:
                            print(f"{now_iso} {u}: WARN no spot (no last trade/ask) — skipped this cycle",
                                  file=sys.stderr, flush=True)
                            continue
                        rows = snapshot_rows(u, headers, now_iso, snapshot_params(now.date(), spot)) + [und]
                    else:
                        rows = snapshot_rows(u, headers, now_iso) + [underlying_row(u, headers, now_iso)]
                    day_dir = os.path.join(a.out, u)
                    os.makedirs(day_dir, exist_ok=True)
                    with gzip.open(os.path.join(day_dir, f"{now.date()}.ndjson.gz"), "at") as f:
                        for r in rows:
                            f.write(json.dumps(r) + "\n")
                    tape = []
                    if a.watchlist and a.tape_min_size:
                        quotes = {r["sym"]: r for r in rows if not r["sym"].startswith("_UND_")}
                        since = tape_since.get(u) or (now - timedelta(seconds=a.interval)).isoformat(timespec="seconds")
                        tape = trade_rows(sorted(quotes), headers, since, now_iso, quotes, a.tape_min_size)
                        tape_since[u] = now_iso
                        with gzip.open(os.path.join(day_dir, f"{now.date()}.trades.ndjson.gz"), "at") as f:
                            for r in tape:
                                f.write(json.dumps(r) + "\n")
                    print(f"{now_iso} {u}: {len(rows)} rows" + (f", {len(tape)} prints" if a.watchlist and a.tape_min_size else ""), flush=True)
                except Exception as e:
                    print(f"{now_iso} {u}: ERROR {e}", file=sys.stderr, flush=True)
            if a.watchlist and time.time() - cycle_t0 > a.interval:
                print(f"{now_iso} WARN cycle took {time.time() - cycle_t0:.0f}s > interval {a.interval}s", file=sys.stderr, flush=True)
        if a.once:
            return
        time.sleep(a.interval)

if __name__ == "__main__":
    main()
