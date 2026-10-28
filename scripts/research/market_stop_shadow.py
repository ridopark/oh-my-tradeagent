#!/usr/bin/env python3
"""market-stop-shadow-v1 — forward, shadow-only test of a QQQ "market stop" on real copytrade positions.

Pre-registration: docs/plans/PREREG-2026-10-28-market-stop-shadow-v1.md. Frozen by the git tag market-stop-shadow-v1.
READ-ONLY: reads the exec journal (SELECT) and Alpaca market data; never places an order.

  market_stop_shadow.py --out ~/data/market-stop-shadow --env ~/scripts/option-collector.env capture --day 2026-10-28
  market_stop_shadow.py --out ... check [--today 2026-10-28] [--alert]
  market_stop_shadow.py --out ... analyze [--today 2027-04-23]

Raw folders <out>/raw/<day>/ are append-only (capture refuses to overwrite). `check` reports counts only.
`analyze` refuses to run before the single look date.
"""
import argparse, gzip, hashlib, json, math, os, random, shlex, subprocess, sys, time, urllib.parse, urllib.request
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

ET = ZoneInfo("America/New_York")
START = date(2026, 10, 28)            # population: fills at/after START 09:30 ET (PREREG "Population")
LOOK = date(2027, 4, 23)              # single analysis date
TENANT, STRATEGY, DB = "prod_real", "copytrade-v1", "exec_alpaca_live"
STOP = -0.005                          # direction × (close/reference − 1) ≤ −0.5%
HS_FLOOR = 0.01                        # Roll half-spread floor, fraction of fill price
MIN_ROLL_CLOSES = 4
SEED = 28
DEFAULT_PSQL = f"kubectl exec -i -n copytrade postgres-0 -- psql -U temporal -d {DB} -tA -F |"
DATA = "https://data.alpaca.markets"

# ---------- plumbing (same conventions as watchlist_forward.py) ----------

def creds(env_file):
    kv = {}
    for line in open(os.path.expanduser(env_file)):
        line = line.strip()
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1); kv[k] = v.strip().strip("\"'")
    return {"APCA-API-KEY-ID": kv["APCA_API_KEY_ID_DATA"], "APCA-API-SECRET-KEY": kv["APCA_API_SECRET_KEY_DATA"]}

def get(path, params, headers, tries=5):
    url = DATA + path + "?" + urllib.parse.urlencode(params)
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as r:
                return json.load(r)
        except Exception:
            if i == tries - 1: raise
            time.sleep(2 ** i)

def paged(path, params, headers, key):
    out, tok = {}, None
    while True:
        p = dict(params, **({"page_token": tok} if tok else {}))
        d = get(path, p, headers)
        for s, rows in (d.get(key) or {}).items(): out.setdefault(s, []).extend(rows)
        tok = d.get("next_page_token")
        if not tok: return out

def sql(psql_cmd, query):
    # READ-ONLY enforced at the session level, not just by convention: any write in this session is rejected by Postgres.
    r = subprocess.run(shlex.split(psql_cmd), input="SET default_transaction_read_only = on;\n" + query, capture_output=True, text=True, check=True)
    return [l.split("|") for l in r.stdout.splitlines() if l.strip() and l.strip() != "SET"]

def utc(d, hhmm):
    return datetime.combine(d, datetime.strptime(hhmm, "%H:%M").time(), ET).astimezone(ZoneInfo("UTC")).strftime("%Y-%m-%dT%H:%M:%SZ")

def write_gz(path, rows):
    with gzip.open(path, "wt") as f:
        for r in rows: f.write(json.dumps(r) + "\n")

def read_gz(path):
    with gzip.open(path, "rt") as f: return [json.loads(l) for l in f]

def manifest(folder):
    files = sorted(f for f in os.listdir(folder) if f != "manifest.json")
    m = {f: hashlib.sha256(open(os.path.join(folder, f), "rb").read()).hexdigest() for f in files}
    json.dump(m, open(os.path.join(folder, "manifest.json"), "w"), indent=1)

def parse_ts(s):
    s = s.replace("Z", "+00:00")
    if "+" not in s[10:] and "-" not in s[10:]: s += "+00:00"
    return datetime.fromisoformat(s).astimezone(ET)

def bar_rows(by_sym):
    rows = []
    for s, bars in by_sym.items():
        for b in bars:
            t = parse_ts(b["t"])
            rows.append({"s": s, "t": t.isoformat(), "o": b["o"], "h": b["h"], "l": b["l"], "c": b["c"], "v": b["v"]})
    return rows

# ---------- contracts ----------

def occ(sym):
    """'SPY   261029C00770000' -> dict(root, expiry, kind, strike, compact)"""
    s = sym.replace(" ", "")
    root, rest = s[:-15], s[-15:]
    return {"root": root, "expiry": date(2000 + int(rest[:2]), int(rest[2:4]), int(rest[4:6])),
            "kind": rest[6], "strike": int(rest[7:]) / 1000.0, "compact": s}

# ---------- capture ----------

def fills_for_day(day, psql_cmd):
    lo = datetime.combine(day, datetime.min.time(), ET).isoformat(); hi = datetime.combine(day + timedelta(days=1), datetime.min.time(), ET).isoformat()   # DST-correct ET midnight bounds
    q = (f"SELECT intent_key, option_symbol, side, filled_qty, avg_fill_price, filled_at, detected_via, qty, limit_price "
         f"FROM order_intent_journal WHERE tenant_id='{TENANT}' AND strategy_id='{STRATEGY}' AND state='FILLED' "
         f"AND filled_at >= '{lo}'::timestamptz AND filled_at < '{hi}'::timestamptz "
         f"ORDER BY filled_at, intent_key;")
    rows = []
    for r in sql(psql_cmd, q):
        rows.append({"intent_key": r[0], "option_symbol": r[1], "side": r[2], "filled_qty": int(float(r[3] or 0)),
                     "avg_fill_price": float(r[4] or 0), "filled_at": parse_ts(r[5]).isoformat(), "detected_via": r[6],
                     "qty": int(float(r[7] or 0)), "limit_price": float(r[8] or 0)})
    return rows

def all_fills(out):
    rows = []
    raw = os.path.join(out, "raw")
    if not os.path.isdir(raw): return rows
    for d in sorted(os.listdir(raw)):
        p = os.path.join(raw, d, "fills.json")
        if os.path.exists(p): rows.extend(json.load(open(p)))
    return rows

def open_contracts(fills, day):
    """contracts with remaining long quantity at any time on `day` (FIFO per symbol, population fills only)."""
    qty = {}
    for f in sorted(fills, key=lambda x: x["filled_at"]):
        if parse_ts(f["filled_at"]).date() > day: break
        q = qty.get(f["option_symbol"], 0)
        qty[f["option_symbol"]] = q + f["filled_qty"] if f["side"] == "BUY" else max(0, q - f["filled_qty"])
    today = {f["option_symbol"] for f in fills if parse_ts(f["filled_at"]).date() == day}
    return sorted({s for s, q in qty.items() if q > 0} | today)

def capture(day, out, headers, psql_cmd):
    final = os.path.join(out, "raw", day.isoformat())
    if os.path.exists(final): raise SystemExit(f"refusing to overwrite {final} (append-only)")
    folder = final + ".tmp"                      # atomic: everything lands in .tmp, renamed to the final folder only after the manifest
    if os.path.exists(folder): import shutil; shutil.rmtree(folder)
    try:
        r = _capture_into(day, out, folder, headers, psql_cmd)
    except BaseException:
        import shutil; shutil.rmtree(folder, ignore_errors=True); raise
    os.rename(folder, final)
    return r

def _capture_into(day, out, folder, headers, psql_cmd):
    os.makedirs(folder)
    fills = fills_for_day(day, psql_cmd)
    json.dump(fills, open(os.path.join(folder, "fills.json"), "w"), indent=1)
    contracts = open_contracts(all_fills(out) + fills, day)
    qqq = paged("/v2/stocks/bars", {"symbols": "QQQ", "timeframe": "1Min", "start": utc(day, "09:30"), "end": utc(day, "16:00"),
                                     "limit": 10000, "feed": "sip"}, headers, "bars")
    write_gz(os.path.join(folder, "qqq_1m.ndjson.gz"), [r for r in bar_rows(qqq) if parse_ts(r["t"]).time() < datetime.strptime("16:00", "%H:%M").time()])
    opt = {}
    for i in range(0, len(contracts), 50):
        chunk = [occ(s)["compact"] for s in contracts[i:i + 50]]
        opt.update(paged("/v1beta1/options/bars", {"symbols": ",".join(chunk), "timeframe": "1Min", "start": utc(day, "09:30"),
                                                   "end": utc(day, "16:15"), "limit": 10000}, headers, "bars"))
    write_gz(os.path.join(folder, "opt_1m.ndjson.gz"), [r for r in bar_rows(opt) if parse_ts(r["t"]).date() == day])
    unds = sorted({occ(s)["root"] for s in contracts})
    und = paged("/v2/stocks/bars", {"symbols": ",".join(unds), "timeframe": "1Day", "start": utc(day, "00:00"), "end": utc(day + timedelta(days=1), "00:00"),
                                     "limit": 10000, "feed": "sip"}, headers, "bars") if unds else {}
    write_gz(os.path.join(folder, "und_1d.ndjson.gz"), [{"s": s, "d": day.isoformat(), "c": b["c"]} for s, bs in und.items() for b in bs])
    manifest(folder)
    return {"day": day.isoformat(), "fills": len(fills), "contracts": len(contracts), "qqq_bars": sum(len(v) for v in qqq.values()),
            "opt_bars": sum(len(v) for v in opt.values()), "underlyings": len(unds)}

# ---------- the rule (pure functions, unit-tested) ----------

def floor_minute(t):
    return t.replace(second=0, microsecond=0)

def reference_close(qqq, entry_t):
    """close of the last QQQ RTH bar that STARTS before the entry minute (any earlier session)."""
    m = floor_minute(entry_t); prev = [b for b in qqq if b["t"] < m]
    return prev[-1]["c"] if prev else None

def roll_hs(closes, fill_px):
    """Roll (1984) effective half-spread in dollars from same-day closes strictly before the exit bar, / fill px, floor 1%."""
    if len(closes) < MIN_ROLL_CLOSES or fill_px <= 0: return HS_FLOOR
    d = [b - a for a, b in zip(closes[:-1], closes[1:])]
    if len(d) < 2: return HS_FLOOR
    x, y = d[:-1], d[1:]; mx, my = sum(x) / len(x), sum(y) / len(y)
    cov = sum((a - mx) * (b - my) for a, b in zip(x, y)) / len(x)
    hs = math.sqrt(-cov) if cov < 0 else 0.0      # Roll spread = 2*sqrt(-cov); half-spread = sqrt(-cov)
    return max(HS_FLOOR, hs / fill_px)

def build_lots(fills):
    """FIFO lots per option symbol from population fills (filled_at >= START 09:30 ET)."""
    start_t = datetime.combine(START, datetime.strptime("09:30", "%H:%M").time(), ET)
    lots, open_by_sym = [], {}
    for f in sorted(fills, key=lambda x: (x["filled_at"], x["intent_key"])):
        t = parse_ts(f["filled_at"])
        if t < start_t: continue
        s = f["option_symbol"]
        if f["side"] == "BUY":
            lot = {"id": f["intent_key"], "sym": s, "entry_t": t, "entry_px": f["avg_fill_price"], "qty": f["filled_qty"], "sells": []}
            lots.append(lot); open_by_sym.setdefault(s, []).append(lot)
        else:
            q = f["filled_qty"]
            for lot in open_by_sym.get(s, []):
                rem = lot["qty"] - sum(x["qty"] for x in lot["sells"])
                if rem <= 0 or q <= 0: continue
                take = min(rem, q); lot["sells"].append({"t": t, "qty": take, "px": f["avg_fill_price"]}); q -= take
    return lots

def intrinsic(c, und_close):
    if und_close is None: return None
    return max(0.0, und_close - c["strike"]) if c["kind"] == "C" else max(0.0, c["strike"] - und_close)

def arm_pnl(lot, bars, und, qqq, today, shadow):
    """Return list of (close_day, dollars) events for one lot in one arm, plus trigger info."""
    c = occ(lot["sym"]); ob = bars.get(c["compact"], []); events = []; info = {"trigger": None}
    rem = lot["qty"]; sells = sorted(lot["sells"], key=lambda x: x["t"]); cutoff = None
    if shadow:
        ref = reference_close(qqq, lot["entry_t"]); direction = 1 if c["kind"] == "C" else -1
        m = floor_minute(lot["entry_t"]); exp_end = datetime.combine(c["expiry"], datetime.strptime("16:00", "%H:%M").time(), ET)
        if ref:
            q_at = lot["qty"]; sell_i = 0
            for b in qqq:
                if b["t"] <= m or b["t"] >= exp_end: continue
                while sell_i < len(sells) and sells[sell_i]["t"] <= b["t"]: q_at -= sells[sell_i]["qty"]; sell_i += 1
                if q_at <= 0: break
                if direction * (b["c"] / ref - 1) <= STOP:
                    cutoff = b["t"]; info["trigger"] = {"t": b["t"].isoformat(), "ref": ref, "close": b["c"]}; break
    for s in sells:
        if cutoff is not None and s["t"] > cutoff: continue
        events.append((s["t"].date(), (s["px"] - lot["entry_px"]) * s["qty"] * 100)); rem -= s["qty"]
    if rem > 0 and cutoff is not None:
        exp_end = datetime.combine(c["expiry"], datetime.strptime("16:00", "%H:%M").time(), ET)
        nxt = [b for b in ob if cutoff < b["t"] < exp_end]          # first option bar after the trigger bar, before expiry close
        if nxt:
            xb = nxt[0]; same = [b["c"] for b in ob if b["t"].date() == xb["t"].date() and b["t"] < xb["t"]]
            hs = roll_hs(same, xb["o"]); px = xb["o"] * (1 - hs)
            events.append((xb["t"].date(), (px - lot["entry_px"]) * rem * 100)); info["shadow_exit"] = {"t": xb["t"].isoformat(), "open": xb["o"], "hs": hs}; rem = 0
        else:
            info["no_exit_bar"] = True                              # triggered but no option print before expiry: falls through to intrinsic/mark (= hold), flagged
    if rem > 0:
        if c["expiry"] <= today:
            uc = und.get((c["root"], c["expiry"].isoformat())); iv = intrinsic(c, uc)
            if iv is not None: events.append((c["expiry"], (iv - lot["entry_px"]) * rem * 100)); rem = 0
        if rem > 0:   # still open at the analysis date (or no expiry close captured): mark at last captured option close
            last = ob[-1] if ob else None
            if last: events.append((last["t"].date(), (last["c"] - lot["entry_px"]) * rem * 100)); info["marked_open"] = True
            else: info["unpriced"] = rem
    return events, info

def max_drawdown(series):
    peak, dd, cum = 0.0, 0.0, 0.0
    for v in series:
        cum += v; peak = max(peak, cum); dd = min(dd, cum - peak)
    return -dd

def decide(actual_total, shadow_total, actual_dd, shadow_dd):
    dd_smaller = shadow_dd < actual_dd
    keep = shadow_total >= (0.8 * actual_total if actual_total > 0 else actual_total)
    if dd_smaller and keep: return "PASS"
    if shadow_total < actual_total and not dd_smaller: return "KILL"
    return "NOT_SHOWN"

def load_raw(out):
    raw = os.path.join(out, "raw"); fills, qqq, bars, und = [], [], {}, {}
    for d in sorted(os.listdir(raw)) if os.path.isdir(raw) else []:
        p = os.path.join(raw, d)
        if os.path.exists(os.path.join(p, "fills.json")): fills += json.load(open(os.path.join(p, "fills.json")))
        for r in read_gz(os.path.join(p, "qqq_1m.ndjson.gz")) if os.path.exists(os.path.join(p, "qqq_1m.ndjson.gz")) else []:
            qqq.append({"t": parse_ts(r["t"]), "c": r["c"]})
        for r in read_gz(os.path.join(p, "opt_1m.ndjson.gz")) if os.path.exists(os.path.join(p, "opt_1m.ndjson.gz")) else []:
            bars.setdefault(r["s"], []).append({"t": parse_ts(r["t"]), "o": r["o"], "c": r["c"]})
        for r in read_gz(os.path.join(p, "und_1d.ndjson.gz")) if os.path.exists(os.path.join(p, "und_1d.ndjson.gz")) else []:
            und[(r["s"], r["d"])] = r["c"]
    qqq.sort(key=lambda b: b["t"])
    for s in bars: bars[s].sort(key=lambda b: b["t"])
    return fills, qqq, bars, und

def analyze(out, today):
    if today < LOOK: raise SystemExit(f"analyze refuses to run before {LOOK} (today {today}); daily reporting is counts only")
    fills, qqq, bars, und = load_raw(out); lots = build_lots(fills)
    per_lot, act_events, sh_events = [], [], []
    for lot in lots:
        a, _ = arm_pnl(lot, bars, und, qqq, today, shadow=False); s, info = arm_pnl(lot, bars, und, qqq, today, shadow=True)
        act_events += a; sh_events += s
        cost = lot["entry_px"] * lot["qty"] * 100
        per_lot.append({"id": lot["id"], "sym": lot["sym"], "cost": cost, "actual": sum(v for _, v in a), "shadow": sum(v for _, v in s), "trigger": info.get("trigger") is not None,
                        "marked_open": info.get("marked_open", False), "unpriced": info.get("unpriced", 0), "no_exit_bar": info.get("no_exit_bar", False)})
    def daily(ev):
        by = {}
        for d, v in ev: by[d] = by.get(d, 0) + v
        return [by[d] for d in sorted(by)]
    at, st_ = sum(v for _, v in act_events), sum(v for _, v in sh_events)
    add, sdd = max_drawdown(daily(act_events)), max_drawdown(daily(sh_events))
    verdict = decide(at, st_, add, sdd)
    diffs = [(l["shadow"] - l["actual"]) / l["cost"] for l in per_lot if l["cost"] > 0]
    random.seed(SEED); boots = []
    for _ in range(10000 if diffs else 0):          # no lots -> no bootstrap, CI reported as None
        smp = [random.choice(diffs) for _ in diffs]; boots.append(sum(smp) / len(smp))
    boots.sort(); ci = (boots[int(0.025 * len(boots))], boots[int(0.975 * len(boots))]) if boots else (None, None)
    trig = [l for l in per_lot if l["trigger"]]
    res = {"look": today.isoformat(), "lots": len(lots), "triggers": len(trig), "actual_total": at, "shadow_total": st_, "actual_max_dd": add, "shadow_max_dd": sdd,
           "verdict": verdict, "mean_per_lot_diff_over_cost": (sum(diffs) / len(diffs)) if diffs else None, "ci95": ci,
           "winners_cut": sum(1 for l in trig if l["actual"] > 0 and l["shadow"] < l["actual"]), "losers_saved": sum(1 for l in trig if l["actual"] < 0 and l["shadow"] > l["actual"]),
           "marked_open": sum(1 for l in per_lot if l["marked_open"]), "unpriced_lots": sum(1 for l in per_lot if l["unpriced"]),
           "triggered_without_exit_bar": sum(1 for l in per_lot if l["no_exit_bar"])}
    json.dump({"result": res, "lots": per_lot}, open(os.path.join(out, f"analysis_{today.isoformat()}.json"), "w"), indent=1, default=str)
    return res

# ---------- check (counts only) ----------

def check(out, today):
    raw = os.path.join(out, "raw"); problems, days = [], sorted(os.listdir(raw)) if os.path.isdir(raw) else []
    for d in days:
        p = os.path.join(raw, d)
        for f in ("fills.json", "qqq_1m.ndjson.gz", "opt_1m.ndjson.gz", "und_1d.ndjson.gz", "manifest.json"):
            if not os.path.exists(os.path.join(p, f)): problems.append(f"{d}: missing {f}")
        if os.path.exists(os.path.join(p, "manifest.json")):
            m = json.load(open(os.path.join(p, "manifest.json")))
            for f, h in m.items():
                fp = os.path.join(p, f)
                if os.path.exists(fp) and hashlib.sha256(open(fp, "rb").read()).hexdigest() != h: problems.append(f"{d}: {f} hash mismatch (raw folders are append-only)")
        if os.path.exists(os.path.join(p, "qqq_1m.ndjson.gz")) and len(read_gz(os.path.join(p, "qqq_1m.ndjson.gz"))) < 300: problems.append(f"{d}: QQQ has fewer than 300 RTH bars")
    if today.weekday() < 5 and today >= START and today.isoformat() not in days: problems.append(f"{today}: no capture folder")
    fills = all_fills(out); lots = build_lots(fills)
    return {"days": len(days), "fills": len(fills), "lots": len(lots), "open_contracts": len(open_contracts(fills, today)), "problems": problems}

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--out", required=True); ap.add_argument("--env"); ap.add_argument("--psql", default=DEFAULT_PSQL)
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("capture"); c.add_argument("--day", required=True)
    k = sub.add_parser("check"); k.add_argument("--today"); k.add_argument("--alert", action="store_true")
    z = sub.add_parser("analyze"); z.add_argument("--today")
    a = ap.parse_args(); out = os.path.expanduser(a.out); os.makedirs(out, exist_ok=True)
    today = date.fromisoformat(a.today) if getattr(a, "today", None) else datetime.now(ET).date()
    if a.cmd == "capture": print(json.dumps(capture(date.fromisoformat(a.day), out, creds(a.env), a.psql)))
    elif a.cmd == "check":
        r = check(out, today); print(json.dumps(r))
        if r["problems"]:
            if a.alert: subprocess.run([os.path.join(os.path.dirname(os.path.abspath(__file__)), "market_stop_shadow_alert.sh"), "\n".join(r["problems"])], check=False)
            sys.exit(1)
    elif a.cmd == "analyze": print(json.dumps(analyze(out, today), default=str))

if __name__ == "__main__":
    main()
