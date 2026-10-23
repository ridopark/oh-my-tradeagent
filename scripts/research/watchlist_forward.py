#!/usr/bin/env python3
"""Forward evidence pipeline for the TradingTheTrend watchlist (read-only research asset).

Implements docs/plans/PREREG-2026-10-23-watchlist-forward-v1.md. Frozen by the git tag
`watchlist-forward-v1`: do not change scoring here; ship a -v2 with a new start date instead.

Subcommands (all read-only on production; stdlib only, so it runs on the homelab):
  capture-flow --day D   10:35 ET: early option flow for the F3 universe (contracts still listed)
  capture-eod  --day D   after the close: legs, watchlist post, 1-min stock bars
  score        --day D   derive F1/F2/F3 rows from the raw folder into <out>/scores.csv
  check                  completeness vs trading days (SPY bars); exit 1 if a past day is missing
  analyze                the single pre-registered look; refuses before 2027-04-23

Usage:
  watchlist_forward.py --out ~/data/watchlist-forward --env ~/scripts/option-collector.env capture-eod --day 2026-10-23
  watchlist_forward.py --out ... --psql "scripts/data/homelab_psql.sh orchestrator" score --day 2026-10-23
"""
import argparse, gzip, hashlib, json, math, os, random, shlex, subprocess, sys, time
import urllib.parse, urllib.request
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

ET = ZoneInfo("America/New_York")
START = date(2026, 10, 23)
ANALYSIS_DATE = date(2027, 4, 23)
SEED = 20261023
ETFS = ("SPY", "QQQ")
PLACEBO_OFFSETS = (-0.010, -0.005, 0.005, 0.010)
DEFAULT_PSQL = "kubectl exec -i -n copytrade postgres-0 -- psql -U temporal -d orchestrator -tA -F |"
DATA = "https://data.alpaca.markets"

# ---------- pure helpers (unit-tested) ----------

def snowflake_time(message_id):
    """Discord snowflake -> aware UTC datetime."""
    ms = (int(message_id) >> 22) + 1420070400000
    return datetime.fromtimestamp(ms / 1000, ZoneInfo("UTC"))

def rth(bars):
    """Bars of one ET date -> RTH list sorted by m (minutes after 09:30), m in [0, 390)."""
    out = [b for b in bars if 0 <= b["m"] < 390]
    return sorted(out, key=lambda b: b["m"])

def break_outcome(rbars, level, sign):
    """F1 rule. Returns (status, trig_m, r60, rclose); status in gap|no_trigger|fired."""
    if not rbars:
        return "no_bars", None, None, None
    if (rbars[0]["o"] - level) * sign > 0:
        return "gap", None, None, None
    i = next((k for k, b in enumerate(rbars) if (b["c"] - level) * sign > 0), None)
    if i is None:
        return "no_trigger", None, None, None
    p0, m0 = rbars[i]["c"], rbars[i]["m"]
    j = next((b for b in rbars if b["m"] >= m0 + 60), None)
    r60 = sign * (j["c"] - p0) / p0 if j else None
    upto = [b for b in rbars if b["m"] <= 385]
    rclose = sign * (upto[-1]["c"] - p0) / p0
    return "fired", m0, r60, rclose

def round_step(ticker, px):
    if ticker in ETFS or 100 <= px < 500:
        return 5.0
    return 1.0 if px < 100 else 10.0

def f2_trade(ticker, rbars, level, sign, levels):
    """Author's written rules (round-3 m5|skip30=False). levels: prior/premarket/5-session H/L.
    Returns net return or None when no entry."""
    if not rbars or (rbars[0]["o"] - level) * sign > 0:
        return None
    m = [b["m"] for b in rbars]
    ends = [k for k in range(len(m)) if k == len(m) - 1 or m[k + 1] // 5 != m[k] // 5]
    endset, entry = set(ends), None
    for a, b in zip(ends, ends[1:]):
        if m[b] < 4:
            continue
        if (rbars[a]["c"] - level) * sign <= 0 and (rbars[b]["c"] - level) * sign > 0:
            entry = b
            break
    if entry is None:
        return None
    p0 = rbars[entry]["c"]
    st, base = round_step(ticker, p0), p0 * (1 + sign * 0.0015)
    cands = [x for x in levels if x is not None]
    cands.append(math.ceil(base / st) * st if sign == 1 else math.floor(base / st) * st)
    tgt = min((x for x in cands if (x - base) * sign >= 0), key=lambda x: abs(x - p0))
    cost = 1e-4 if ticker in ETFS else 3e-4
    for j in range(entry + 1, len(rbars)):
        b = rbars[j]
        if b["m"] > 385:
            return sign * (rbars[j - 1]["c"] - p0) / p0 - cost
        if j in endset and (b["c"] - level) * sign <= 0:
            return sign * (b["c"] - p0) / p0 - cost
        if (b["h"] if sign == 1 else -b["l"]) >= tgt * sign:
            return sign * (tgt - p0) / p0 - cost
    return sign * (rbars[-1]["c"] - p0) / p0 - cost

def flow_z(today_window, history_windows):
    """F3 z-score of today's call-put volume vs >=15 non-zero trailing sessions (of the last 20)."""
    hist = history_windows[-20:]
    if sum(1 for h in hist if h != 0) < 15:
        return None
    mu = sum(hist) / len(hist)
    sd = (sum((h - mu) ** 2 for h in hist) / (len(hist) - 1)) ** 0.5
    return None if sd == 0 else (today_window - mu) / sd

def tag(zs):
    if zs is None:
        return None
    return "AGREE" if zs >= 1 else ("DISAGREE" if zs <= -1 else "SILENT")

def cluster_boot(rows, key, value, reps=10000, seed=SEED):
    """Day-clustered percentile bootstrap of the mean of value(row). rows: list of dicts."""
    days = {}
    for r in rows:
        v = value(r)
        if v is not None:
            days.setdefault(r[key], []).append(v)
    ks = sorted(days)
    if len(ks) < 3:
        return None, None, None
    est = sum(sum(days[k]) for k in ks) / sum(len(days[k]) for k in ks)
    rng, means = random.Random(seed), []
    for _ in range(reps):
        pick = [ks[rng.randrange(len(ks))] for _ in ks]
        n = sum(len(days[k]) for k in pick)
        means.append(sum(sum(days[k]) for k in pick) / n)
    means.sort()
    return est, means[int(0.025 * reps)], means[int(0.975 * reps) - 1]

# ---------- IO ----------

def creds(env_file):
    kv = {}
    for line in open(os.path.expanduser(env_file)):
        line = line.strip()
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            kv[k] = v.strip().strip("\"'")
    return {"APCA-API-KEY-ID": kv["APCA_API_KEY_ID_DATA"], "APCA-API-SECRET-KEY": kv["APCA_API_SECRET_KEY_DATA"]}

def get(path, params, headers, tries=5):
    url = DATA + path + "?" + urllib.parse.urlencode(params)
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as r:
                return json.load(r)
        except Exception:
            if i == tries - 1:
                raise
            time.sleep(2 ** i)

def paged(path, params, headers, key):
    out, tok = {}, None
    while True:
        p = dict(params, **({"page_token": tok} if tok else {}))
        d = get(path, p, headers)
        for s, rows in (d.get(key) or {}).items():
            out.setdefault(s, []).extend(rows)
        tok = d.get("next_page_token")
        if not tok:
            return out

def sql(psql_cmd, query):
    r = subprocess.run(shlex.split(psql_cmd), input=query, capture_output=True, text=True, check=True)
    return [l.split("|") for l in r.stdout.splitlines() if l.strip()]

def utc(d, hhmm):
    return datetime.combine(d, datetime.strptime(hhmm, "%H:%M").time(), ET).astimezone(ZoneInfo("UTC")).strftime("%Y-%m-%dT%H:%M:%SZ")

def write_gz(path, rows):
    with gzip.open(path, "wt") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")

def read_gz(path):
    with gzip.open(path, "rt") as f:
        return [json.loads(l) for l in f]

def manifest(folder):
    files = sorted(f for f in os.listdir(folder) if f != "manifest.json")
    m = {f: hashlib.sha256(open(os.path.join(folder, f), "rb").read()).hexdigest() for f in files}
    json.dump(m, open(os.path.join(folder, "manifest.json"), "w"), indent=1)

def bar_rows(by_sym):
    rows = []
    for s, bars in by_sym.items():
        for b in bars:
            t = datetime.fromisoformat(b["t"].replace("Z", "+00:00")).astimezone(ET)
            rows.append({"s": s, "d": t.date().isoformat(), "m": t.hour * 60 + t.minute - 570,
                         "o": b["o"], "h": b["h"], "l": b["l"], "c": b["c"], "v": b["v"]})
    return rows

# ---------- capture ----------

def capture_eod(day, out, headers, psql_cmd):
    folder = os.path.join(out, "raw", day.isoformat()); os.makedirs(folder, exist_ok=True)
    armed = sql(psql_cmd, "SELECT occurred_at, subject->>'ticker', subject->>'direction', subject->>'trigger' "
                "FROM audit_log WHERE kind='TriggerArmed' AND (occurred_at AT TIME ZONE 'America/New_York')::date "
                f"= '{day.isoformat()}' ORDER BY occurred_at;")
    wl = sql(psql_cmd, "SELECT source_message_id, author, ideas::text, recorded_at FROM watchlist_daily "
             f"WHERE et_date = '{day.isoformat()}';")
    legs = {}
    for ts, tk, dr, lv in armed:
        k = (tk, dr, float(lv))
        legs.setdefault(k, ts)
    post = None
    if wl:
        mid = wl[0][0].rsplit("-", 1)[-1]
        post = {"source_message_id": wl[0][0], "author": wl[0][1], "ideas": json.loads(wl[0][2]),
                "recorded_at": wl[0][3], "posted_at": snowflake_time(mid).isoformat()}
    json.dump({"legs": [{"s": k[0], "dir": k[1], "lvl": k[2], "armed_at": v} for k, v in sorted(legs.items())],
               "watchlist_daily": post}, open(os.path.join(folder, "legs.json"), "w"), indent=1)
    syms = sorted({k[0] for k in legs} | {"SPY"})
    bars = paged("/v2/stocks/bars", {"symbols": ",".join(syms), "timeframe": "1Min", "feed": "sip",
                 "adjustment": "raw", "limit": 10000, "start": utc(day - timedelta(days=10), "04:00"),
                 "end": utc(day, "20:00")}, headers, "bars")
    write_gz(os.path.join(folder, "stock_1m.ndjson.gz"), bar_rows(bars))
    manifest(folder)
    return folder

def capture_flow(day, out, headers, psql_cmd):
    folder = os.path.join(out, "raw", day.isoformat()); os.makedirs(folder, exist_ok=True)
    uni = [r[0] for r in sql(psql_cmd, "SELECT DISTINCT subject->>'ticker' FROM audit_log WHERE kind='TriggerArmed' "
                             f"AND occurred_at >= '{(day - timedelta(days=90)).isoformat()}' ORDER BY 1;")]
    opens = paged("/v2/stocks/bars", {"symbols": ",".join(uni), "timeframe": "1Min", "feed": "sip",
                  "adjustment": "raw", "limit": 10000, "start": utc(day, "09:30"), "end": utc(day, "09:31")},
                  headers, "bars")
    rows = []
    for u in uni:
        if u not in opens:
            continue
        spot = opens[u][0]["o"]
        symbols = list(get_snapshot_symbols(u, day, spot, headers))
        exps = sorted({s[len(u):len(u) + 6] for s in symbols})[:2]
        symbols = [s for s in symbols if s[len(u):len(u) + 6] in exps]
        per_min = {}
        for i in range(0, len(symbols), 100):
            ob = paged("/v1beta1/options/bars", {"symbols": ",".join(symbols[i:i + 100]), "timeframe": "1Min",
                       "limit": 10000, "start": utc(day, "09:30"), "end": utc(day, "10:30")}, headers, "bars")
            for r in bar_rows(ob):
                if r["d"] != day.isoformat() or not 0 <= r["m"] < 60:
                    continue
                right = r["s"][len(u) + 6]
                cv, pv = per_min.get(r["m"], (0, 0))
                per_min[r["m"]] = (cv + (r["v"] if right == "C" else 0), pv + (r["v"] if right == "P" else 0))
        rows += [{"s": u, "m": mm, "call_v": cv, "put_v": pv, "expiries": exps} for mm, (cv, pv) in sorted(per_min.items())]
    write_gz(os.path.join(folder, "flow.ndjson.gz"), rows)
    manifest(folder)
    return folder

def get_snapshot_symbols(u, day, spot, headers):
    snaps = {}
    tok = None
    while True:
        p = {"feed": "opra", "limit": 1000, "expiration_date_gte": day.isoformat(),
             "expiration_date_lte": (day + timedelta(days=21)).isoformat(),
             "strike_price_gte": round(spot * 0.95, 2), "strike_price_lte": round(spot * 1.05, 2)}
        if tok:
            p["page_token"] = tok
        d = get(f"/v1beta1/options/snapshots/{u}", p, headers)
        snaps.update(d.get("snapshots") or {})
        tok = d.get("next_page_token")
        if not tok:
            return snaps.keys()

# ---------- score ----------

def session_levels(stock, s, day):
    """Prior-session RTH H/L, premarket H/L (04:00-09:29), prior-5-session RTH H/L."""
    mine = [b for b in stock if b["s"] == s]
    days = sorted({b["d"] for b in mine if b["d"] < day and any(0 <= x["m"] < 390 for x in mine if x["d"] == b["d"])})
    def hl(ds, lo, hi):
        bs = [b for b in mine if b["d"] in ds and lo <= b["m"] < hi]
        return (max(b["h"] for b in bs), min(b["l"] for b in bs)) if bs else (None, None)
    pdh, pdl = hl(days[-1:], 0, 390)
    pwh, pwl = hl(days[-5:], 0, 390) if len(days) >= 5 else (None, None)
    pmh, pml = hl([day], -330, 0)
    return [pdh, pdl, pmh, pml, pwh, pwl]

def flow_window(flow, s, end_m):
    return sum(r["call_v"] - r["put_v"] for r in flow if r["s"] == s and r["m"] <= min(end_m, 59))

def score_day(day, out):
    folder = os.path.join(out, "raw", day.isoformat())
    meta = json.load(open(os.path.join(folder, "legs.json")))
    stock = read_gz(os.path.join(folder, "stock_1m.ndjson.gz"))
    flow_path = os.path.join(folder, "flow.ndjson.gz")
    flow = read_gz(flow_path) if os.path.exists(flow_path) else None
    prior = sorted(f for f in os.listdir(os.path.join(out, "raw")) if f < day.isoformat())[-20:]
    hist_flow = {f: read_gz(os.path.join(out, "raw", f, "flow.ndjson.gz")) for f in prior
                 if os.path.exists(os.path.join(out, "raw", f, "flow.ndjson.gz"))}
    rows, open_et = [], datetime.combine(day, datetime.strptime("09:30", "%H:%M").time(), ET)
    for leg in meta["legs"]:
        s, sign, lvl = leg["s"], 1 if leg["dir"] == "ABOVE" else -1, leg["lvl"]
        armed = datetime.fromisoformat(leg["armed_at"].replace(" ", "T") + ("" if "+" in leg["armed_at"][-6:] else "+00:00"))
        rb = rth([b for b in stock if b["s"] == s and b["d"] == day.isoformat()])
        base = {"d": day.isoformat(), "s": s, "dir": leg["dir"], "lvl": lvl}
        if armed >= open_et:
            rows.append(dict(base, arm="real", status="armed_after_open")); continue
        status, tm, r60, rc = break_outcome(rb, lvl, sign)
        row = dict(base, arm="real", status=status, trig_m=tm, r60=r60, rclose=rc,
                   f2_net=f2_trade(s, rb, lvl, sign, session_levels(stock, s, day.isoformat())))
        if status == "fired" and tm >= 15 and flow is not None:
            hist = [flow_window(hist_flow[f], s, tm) for f in sorted(hist_flow)]
            z = flow_z(flow_window(flow, s, tm), hist)
            row.update(f3_z=None if z is None else z * sign, f3_tag=tag(None if z is None else z * sign))
        rows.append(row)
        for off in PLACEBO_OFFSETS:
            st, ptm, pr60, prc = break_outcome(rb, lvl * (1 + off), sign)
            rows.append(dict(base, lvl=lvl * (1 + off), arm=f"placebo{off:+.3f}", status=st, trig_m=ptm, r60=pr60, rclose=prc))
    return rows

COLS = ["d", "s", "dir", "lvl", "arm", "status", "trig_m", "r60", "rclose", "f2_net", "f3_z", "f3_tag"]

def append_scores(out, day, rows):
    path = os.path.join(out, "scores.csv")
    old = []
    if os.path.exists(path):
        old = [l for l in open(path).read().splitlines()[1:] if not l.startswith(day.isoformat() + ",")]
    new = [",".join("" if r.get(c) is None else str(r.get(c)) for c in COLS) for r in rows]
    open(path, "w").write("\n".join([",".join(COLS)] + sorted(old + new)) + "\n")

# ---------- check / analyze ----------

def check(out, headers, today):
    spy = paged("/v2/stocks/bars", {"symbols": "SPY", "timeframe": "1Day", "feed": "sip", "limit": 10000,
                "start": START.isoformat(), "end": today.isoformat()}, headers, "bars").get("SPY", [])
    trading = sorted({b["t"][:10] for b in spy if b["t"][:10] < today.isoformat()})
    have = {f for f in os.listdir(os.path.join(out, "raw")) if os.path.exists(os.path.join(out, "raw", f, "manifest.json"))} \
        if os.path.isdir(os.path.join(out, "raw")) else set()
    explained = set(json.load(open(os.path.join(out, "explained_gaps.json")))) if os.path.exists(os.path.join(out, "explained_gaps.json")) else set()
    missing = [d for d in trading if d not in have and d not in explained]
    print(f"trading days {len(trading)}  captured {len(have & set(trading))}  explained {len(explained & set(trading))}  missing {missing}")
    return missing

def analyze(out, today):
    if today < ANALYSIS_DATE:
        sys.exit(f"refusing: the single pre-registered look is on {ANALYSIS_DATE}")
    import csv
    rows = [{k: (None if v == "" else v) for k, v in r.items()} for r in csv.DictReader(open(os.path.join(out, "scores.csv")))]
    f = lambda k: (lambda r: float(r[k]) if r.get(k) is not None else None)
    real = [r for r in rows if r["arm"] == "real" and r["status"] == "fired"]
    plac = [r for r in rows if r["arm"].startswith("placebo") and r["status"] == "fired"]
    by_day = {}
    for r in real: by_day.setdefault(r["d"], [[], []])[0].append(f("r60")(r))
    for r in plac: by_day.setdefault(r["d"], [[], []])[1].append(f("r60")(r))
    diff = [{"d": d, "v": (sum(x for x in a if x is not None) / max(1, sum(x is not None for x in a)))
             - (sum(x for x in b if x is not None) / max(1, sum(x is not None for x in b)))}
            for d, (a, b) in by_day.items() if a and b]
    print("F1 real-placebo r60 (day means):", cluster_boot(diff, "d", lambda r: r["v"]))
    print("F2 net per trade:", cluster_boot([r for r in rows if r["arm"] == "real"], "d", f("f2_net")))
    ag = [r for r in real if r.get("f3_tag") == "AGREE"]; si = [r for r in real if r.get("f3_tag") == "SILENT"]
    print("F3 AGREE / SILENT r60:", cluster_boot(ag, "d", f("r60")), cluster_boot(si, "d", f("r60")))
    print("Apply Holm across F1-F3 and the PASS/KILL rules in the PREREG by hand; report alongside the reviewer's rebuild.")

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True); ap.add_argument("--env"); ap.add_argument("--psql", default=DEFAULT_PSQL)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for c in ("capture-flow", "capture-eod", "score"):
        sub.add_parser(c).add_argument("--day", default=datetime.now(ET).date().isoformat())
    sub.add_parser("check"); sub.add_parser("analyze")
    a = ap.parse_args(); out = os.path.expanduser(a.out); today = datetime.now(ET).date()
    if a.cmd == "capture-eod":
        print(capture_eod(date.fromisoformat(a.day), out, creds(a.env), a.psql))
    elif a.cmd == "capture-flow":
        print(capture_flow(date.fromisoformat(a.day), out, creds(a.env), a.psql))
    elif a.cmd == "score":
        d = date.fromisoformat(a.day); rows = score_day(d, out); append_scores(out, d, rows)
        print(f"{d} scored {sum(r['arm'] == 'real' for r in rows)} legs")
    elif a.cmd == "check":
        sys.exit(1 if check(out, creds(a.env), today) else 0)
    else:
        analyze(out, today)

if __name__ == "__main__":
    main()
