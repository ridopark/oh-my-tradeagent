#!/usr/bin/env python3
"""How often does OPRA actually update an option quote?

Phase 3 of docs/plans/PLAN-2026-08-17-premium-poll-latency.md — the one
measurement that decides whether the premium poll should go below 500ms.

`AlpacaMarketData:532` rejects a quote whose timestamp equals the previous one:

    if (prevStamp != null && q.retrievedAt() != null && prevStamp.isEqual(q.retrievedAt()))

`retrievedAt` is `latestQuote.t` off `/v1beta1/options/snapshots` (`:240,:251`).
So polling faster than OPRA updates that field buys REST cost and nothing else.
The spike could not measure this from history — there is no options *quotes*
history endpoint, only trades/bars/quotes-latest — so it can only be sampled live.

DECISION RULE (fixed in the plan BEFORE seeing any number, reproduced at the
bottom of the report so the result cannot be rationalised after the fact):

    p50 gap  < 200ms   -> 200ms justified (further saving ~0.35%-0.71% of premium)
    p50 gap  200-500ms -> 500ms is at the knee; STOP at Phase 2
    p50 gap  > 500ms   -> even 500ms may be over-polling

Also records bid withdrawals / book widening that produce no trade print. That
is the options-WebSocket spike's one surviving open question, and it is free to
collect here.

SAFE DURING RTH. This is REST only — unlike scripts/alpaca-ws-conn-check.py,
which opens a second *stocks* WebSocket and will 406 the live feed the watchlist
triggers depend on. Nothing here opens a socket.

Usage:
    python3 scripts/research/option_quote_rate.py
    python3 scripts/research/option_quote_rate.py --seconds 90 --interval-ms 50
    python3 scripts/research/option_quote_rate.py --symbols SPY260817C00640000,...
    python3 scripts/research/option_quote_rate.py --out /path/to/samples.json

Credentials: APCA_API_KEY_ID_DATA / APCA_API_SECRET_KEY_DATA, from the
environment or from .env at the repo root (same convention as
option_poll_interval_sweep.py). Never printed.
"""
import argparse
import concurrent.futures as cf
import datetime as dt
import json
import os
import pathlib
import statistics as st
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

DATA = "https://data.alpaca.markets"


def _find_env():
    """Walk up for .env — the repo root in a normal checkout, and the main repo
    when this runs from one of the git worktrees this repo keeps."""
    here = pathlib.Path(__file__).resolve()
    for p in here.parents:
        if (p / ".env").exists():
            return p / ".env"
    return here.parents[2] / ".env"


ENV = _find_env()

# One request carries every symbol, so the poll rate is per-REQUEST, not
# per-contract. 50ms = 1200 req/min against a 10,000 req/min budget (Algo Trader
# Plus, verified 2026-08-16) — ~12%, leaving room for the live market-data poll,
# kill-switch MTM and GetOptionQuoteActivity, which share that budget.
DEFAULT_INTERVAL_MS = 50
DEFAULT_SECONDS = 60


def creds():
    k = os.environ.get("APCA_API_KEY_ID_DATA", "").strip()
    s = os.environ.get("APCA_API_SECRET_KEY_DATA", "").strip()
    if k and s:
        return k, s
    if not ENV.exists():
        sys.exit(f"no credentials: set APCA_API_KEY_ID_DATA/_SECRET_KEY_DATA or create {ENV}")
    for line in ENV.read_text().splitlines():
        line = line.strip()
        if "=" not in line or line.startswith("#"):
            continue
        n, v = line.split("=", 1)
        v = v.strip().strip("'\"")
        if n.strip() == "APCA_API_KEY_ID_DATA":
            k = k or v
        elif n.strip() == "APCA_API_SECRET_KEY_DATA":
            s = s or v
    if not (k and s):
        sys.exit("no APCA_API_KEY_ID_DATA / APCA_API_SECRET_KEY_DATA found")
    return k, s


K, S = creds()
HDRS = {"APCA-API-KEY-ID": K, "APCA-API-SECRET-KEY": S}


def get(path, params):
    url = f"{DATA}{path}?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers=HDRS)
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)


def parse_ns(ts):
    """RFC3339 -> integer nanoseconds. Alpaca returns nanosecond precision, which
    datetime cannot hold, and the whole point here is sub-millisecond gaps."""
    if not ts:
        return None
    ts = ts.rstrip("Z")
    date, _, frac = ts.partition(".")
    base = int(dt.datetime.strptime(date, "%Y-%m-%dT%H:%M:%S").replace(
        tzinfo=dt.timezone.utc).timestamp())
    return base * 1_000_000_000 + int((frac + "0" * 9)[:9]) if frac else base * 1_000_000_000


def occ(sym, expiry, right, strike):
    """Compact OCC, the form snapshotQuote sends (`AlpacaMarketData:230`)."""
    return f"{sym}{expiry:%y%m%d}{right}{int(round(strike * 1000)):08d}"


def underlying_price(syms):
    d = get("/v2/stocks/trades/latest", {"symbols": ",".join(syms)})
    return {s: t["p"] for s, t in d.get("trades", {}).items()}


def pick_contracts(underlyings, verbose=True):
    """Resolve real ATM contracts by probing candidates, rather than trusting a
    constructed symbol to exist. Every candidate goes in ONE snapshot request;
    whatever comes back with a two-sided quote is real and quoting."""
    px = underlying_price(underlyings)
    today = dt.date.today()
    near = [today + dt.timedelta(days=i) for i in range(0, 5)]
    far = [today + dt.timedelta(days=i) for i in range(21, 46)
           if (today + dt.timedelta(days=i)).weekday() == 4]
    cands, meta = [], {}
    for u in underlyings:
        if u not in px:
            continue
        atm = round(px[u])
        for e in near + far:
            for k in (atm - 1, atm, atm + 1):
                s = occ(u, e, "C", k)
                cands.append(s)
                meta[s] = (u, e, k, (e - today).days)
    snaps = {}
    for i in range(0, len(cands), 100):
        chunk = cands[i:i + 100]
        try:
            snaps.update(get("/v1beta1/options/snapshots",
                             {"symbols": ",".join(chunk)}).get("snapshots", {}))
        except urllib.error.HTTPError as e:
            if verbose:
                print(f"  candidate probe chunk failed: {e}", file=sys.stderr)
    live = []
    for s, snap in snaps.items():
        q = (snap or {}).get("latestQuote") or {}
        bp, ap = q.get("bp"), q.get("ap")
        if not bp or not ap or bp <= 0 or ap <= 0:
            continue
        u, e, k, dte = meta[s]
        live.append({"symbol": s, "underlying": u, "dte": dte, "strike": k,
                     "spot": px[u], "moneyness": abs(k - px[u])})
    if not live:
        sys.exit("no candidate contract returned a two-sided quote — market closed, or no chain")
    chosen, seen = [], set()
    # Nearest expiry and the ~30d expiry, one ATM contract each per underlying.
    for u in underlyings:
        mine = [c for c in live if c["underlying"] == u]
        for bucket in ("near", "far"):
            pool = [c for c in mine if (c["dte"] <= 4 if bucket == "near" else c["dte"] >= 21)]
            if not pool:
                continue
            best = min(pool, key=lambda c: (c["dte"] if bucket == "near" else -c["dte"],
                                            c["moneyness"]))
            if best["symbol"] not in seen:
                seen.add(best["symbol"])
                best["bucket"] = bucket
                chosen.append(best)
    return chosen


def sample(symbols, seconds, interval_ms, concurrency=8, verbose=True):
    """Issue snapshot requests at a fixed CADENCE, overlapping in flight.

    A sequential loop cannot sample faster than one network round-trip (~165ms
    from a laptop, ~58ms from the cluster), which silently becomes the floor on
    every gap it reports. Overlapping requests decouple cadence from latency.
    Responses may land out of order; that is harmless because the analysis keys
    on the quote's own timestamp, never on arrival order."""
    rows = {s: [] for s in symbols}
    errors = []
    lock = threading.Lock()
    counter = {"n": 0}
    deadline = time.time() + seconds

    def one():
        try:
            body = get("/v1beta1/options/snapshots", {"symbols": ",".join(symbols)})
            snaps = body.get("snapshots", {})
        except urllib.error.HTTPError as e:
            with lock:
                errors.append(e.code)
            return
        except Exception as e:  # noqa: BLE001 — a transient read must not lose the run
            with lock:
                errors.append(str(e))
            return
        wall = time.time()
        with lock:
            counter["n"] += 1
            for s in symbols:
                snap = snaps.get(s) or {}
                q = snap.get("latestQuote") or {}
                tr = snap.get("latestTrade") or {}
                if not q:
                    continue
                rows[s].append({
                    "wall": wall,
                    "qt": parse_ns(q.get("t")),
                    "bp": q.get("bp"), "bs": q.get("bs"),
                    "ap": q.get("ap"), "as": q.get("as"),
                    "tt": parse_ns(tr.get("t")), "tp": tr.get("p"),
                })

    with cf.ThreadPoolExecutor(max_workers=concurrency) as pool:
        pending = []
        skipped = 0
        while time.time() < deadline:
            t0 = time.time()
            pending = [f for f in pending if not f.done()]
            # Back-pressure. Submitting on a fixed cadence regardless of how much
            # is already in flight means a latency spike queues work unboundedly
            # and the run overshoots its request rate — against a budget shared
            # with the live market-data poll and kill-switch MTM. Skip the tick
            # instead: a gap in sampling costs one data point, overshooting costs
            # someone else's quotes.
            if len(pending) >= 2 * concurrency:
                skipped += 1
            else:
                pending.append(pool.submit(one))
            lag = interval_ms / 1000.0 - (time.time() - t0)
            if lag > 0:
                time.sleep(lag)
        if skipped and verbose:
            print(f"  {skipped} tick(s) skipped for back-pressure (latency > cadence)")
        cf.wait(pending, timeout=30)

    n = counter["n"]
    if verbose:
        rate = n / seconds if seconds else 0
        print(f"  {n} requests ({rate:.0f}/s, ~{rate * 60:.0f} req/min), {len(errors)} errors")
    return rows, n, errors


def pct(xs, p):
    if not xs:
        return None
    xs = sorted(xs)
    i = min(len(xs) - 1, max(0, int(round((p / 100.0) * (len(xs) - 1)))))
    return xs[i]


def ordered(rows):
    """Rows deduped to one per distinct quote timestamp, in QUOTE order.

    Responses arrive out of order under concurrency, so arrival order is not the
    book's order. The quote's own timestamp is."""
    best = {}
    for r in rows:
        if r["qt"] is not None and r["qt"] not in best:
            best[r["qt"]] = r
    return [best[k] for k in sorted(best)]


def analyse(rows):
    """Gaps between DISTINCT quote timestamps — the exact thing `:532` dedups on."""
    seen = [r["qt"] for r in rows if r["qt"] is not None]
    stamps = sorted(set(seen))
    gaps = [(b - a) / 1e6 for a, b in zip(stamps, stamps[1:])]  # ns -> ms
    return gaps, len(seen) - len(stamps), stamps


def bid_events(rows):
    """Bid withdrawal / book widening with NO new trade print — the only surviving
    argument for the options WebSocket, and invisible to trade-print history."""
    out = []
    spreads = [r["ap"] - r["bp"] for r in rows
               if r.get("ap") and r.get("bp") and r["ap"] > 0 and r["bp"] > 0]
    med = st.median(spreads) if spreads else 0.0
    prev = None
    for r in rows:
        if prev is not None:
            lost_bid = (prev.get("bp") or 0) > 0 and not (r.get("bp") or 0) > 0
            lost_size = (prev.get("bs") or 0) > 0 and (r.get("bs") or 0) == 0
            sp = (r.get("ap") or 0) - (r.get("bp") or 0)
            widened = med > 0 and sp > 3 * med
            no_print = r.get("tt") == prev.get("tt")
            if (lost_bid or lost_size or widened) and no_print:
                out.append({
                    "wall": r["wall"], "kind":
                        "bid_gone" if lost_bid else ("bid_size_zero" if lost_size else "widened"),
                    "prev_bid": prev.get("bp"), "bid": r.get("bp"),
                    "spread": round(sp, 4), "median_spread": round(med, 4),
                })
        prev = r
    return out, med


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seconds", type=int, default=DEFAULT_SECONDS)
    ap.add_argument("--interval-ms", type=int, default=DEFAULT_INTERVAL_MS)
    ap.add_argument("--concurrency", type=int, default=8,
                    help="requests in flight; decouples cadence from round-trip latency")
    ap.add_argument("--symbols", default="", help="comma-separated compact OCC; skips auto-pick")
    ap.add_argument("--underlyings", default="SPY,QQQ")
    ap.add_argument("--out", default="", help="write raw samples as JSON here")
    a = ap.parse_args()

    if a.symbols:
        chosen = [{"symbol": s.strip(), "underlying": s.strip()[:3], "dte": None,
                   "strike": None, "spot": None, "bucket": "manual"}
                  for s in a.symbols.split(",") if s.strip()]
    else:
        print("resolving ATM contracts...")
        chosen = pick_contracts([u.strip() for u in a.underlyings.split(",") if u.strip()])
    for c in chosen:
        d = f"{c['dte']}DTE" if c["dte"] is not None else "?"
        print(f"  {c['symbol']}  {d:>6}  strike={c['strike']}  spot={c['spot']}")

    syms = [c["symbol"] for c in chosen]
    print(f"\nsampling {len(syms)} contracts for {a.seconds}s at {a.interval_ms}ms "
          f"(~{60000 // a.interval_ms} req/min, {a.concurrency} in flight)...")
    rows, nreq, errors = sample(syms, a.seconds, a.interval_ms, a.concurrency)

    print(f"\n{'contract':<22} {'DTE':>5} {'samples':>8} {'updates':>8} {'dup%':>6} "
          f"{'p50':>8} {'p90':>8} {'p99':>8}")
    print("-" * 82)
    report = {}
    for c in chosen:
        s = c["symbol"]
        gaps, dupes, stamps = analyse(rows[s])
        n = len(rows[s])
        dup_pct = (100.0 * dupes / n) if n else 0.0
        p50, p90, p99 = pct(gaps, 50), pct(gaps, 90), pct(gaps, 99)
        report[s] = {"dte": c["dte"], "samples": n, "updates": len(stamps),
                     "dup_pct": dup_pct, "p50": p50, "p90": p90, "p99": p99,
                     "bucket": c["bucket"]}
        f = lambda v: f"{v:8.0f}" if v is not None else "       -"  # noqa: E731
        print(f"{s:<22} {str(c['dte']):>5} {n:>8} {len(stamps):>8} {dup_pct:>5.1f}% "
              f"{f(p50)} {f(p90)} {f(p99)}   (ms)")

    print("\nbid withdrawal / book widening with NO trade print:")
    total_ev = 0
    for c in chosen:
        ev, med = bid_events(ordered(rows[c["symbol"]]))
        total_ev += len(ev)
        report[c["symbol"]]["bid_events"] = len(ev)
        report[c["symbol"]]["median_spread"] = med
        print(f"  {c['symbol']:<22} {len(ev):>4} event(s), median spread {med:.4f}")
        for e in ev[:3]:
            print(f"      {e['kind']}: bid {e['prev_bid']} -> {e['bid']}, spread {e['spread']}")

    # A poll can never resolve a gap shorter than its own interval. If almost
    # nothing deduped, we sampled SLOWER than OPRA updated and every p50 below is
    # an artifact of the sampling rate — the same mistake that made the spike's
    # first 500ms result worthless (trade-print resolution, 1520ms median).
    decision_buckets = ("near", "manual")
    undersampled = [c["symbol"] for c in chosen
                    if report[c["symbol"]]["bucket"] in decision_buckets
                    and report[c["symbol"]]["samples"]
                    and report[c["symbol"]]["dup_pct"] < 10.0]
    if undersampled:
        print(f"\n*** SAMPLING-LIMITED: {len(undersampled)} near-expiry contract(s) deduped "
              f"<10% at {a.interval_ms}ms.")
        print("    The quote updates FASTER than this poll, so the p50 above is an upper")
        print(f"    bound set by --interval-ms, not a measurement. True gap <= {a.interval_ms}ms.")
        print("    Re-run with a smaller --interval-ms until dup% climbs well above zero.")

    near = [r for r in report.values()
            if r["bucket"] in decision_buckets and r["p50"] is not None]
    if near:
        worst = max(r["p50"] for r in near)
        print(f"\nDECISION RULE (plan Phase 3) — slowest measured p50 gap = {worst:.0f}ms"
              + ("  [UPPER BOUND — sampling-limited]" if undersampled else ""))
        if worst < 200:
            print("  -> p50 < 200ms: 200ms IS justified. Expected further saving")
            print("     ~0.35% (p99) to ~0.71% (p99.9) of premium.")
            print("     Weigh the concurrency ceiling: 200ms = 300 req/min per contract")
            print("     => ~33 concurrent contracts vs ~83 at 500ms.")
        elif worst <= 500:
            print("  -> p50 in 200-500ms: 500ms is already AT THE KNEE. STOP at Phase 2.")
            print("     This is a SUCCESSFUL outcome of Phase 3, not a failed one.")
        else:
            print("  -> p50 > 500ms: 500ms may already be over-polling. Do not go below;")
            print("     consider whether Phase 2 itself was past the knee.")
    else:
        print("\nNo contract produced quote updates — cannot apply the rule.")

    if errors:
        print(f"\n{len(errors)} request error(s) out of {nreq}: "
              f"{sorted(set(map(str, errors)))[:5]}")
    if total_ev == 0:
        print("\nNo bid-withdrawal events observed in this window — a single sample,")
        print("not evidence of absence. Re-run in a volatile window before concluding.")

    if a.out:
        p = pathlib.Path(a.out)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps({"chosen": chosen, "report": report, "raw": rows,
                                 "requests": nreq, "interval_ms": a.interval_ms,
                                 "seconds": a.seconds}, default=str))
        print(f"\nraw samples -> {p}")


if __name__ == "__main__":
    main()
