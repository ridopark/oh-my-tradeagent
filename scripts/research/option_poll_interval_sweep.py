#!/usr/bin/env python3
"""Sweep many (contract, day) pairs: does 500ms beat 2000ms consistently?

⚠⚠ THIS SCRIPT'S HEADLINE RESULT IS RETRACTED. DO NOT QUOTE IT. ⚠⚠

It reports "500ms and 2000ms exit at the same price" (14 of 18 identical). That
result is an ARTIFACT, not a finding: it replays TRADE PRINTS, whose median gap
is 1520ms, and 45.6% of gaps exceed 2000ms. A 500ms poll of that series re-reads
the same value three or four times, so this method CANNOT detect the effect it
is asked about. Run option_print_resolution.py to see the gap distribution.

The corrected analysis is option_velocity.py, which measures detection delay
instead: a poll at interval I observes a stop crossing ~I/2 late, and at the
measured p99 downward velocity of 2.31%/s that 0.75s difference is worth
1.7-3.5% of premium. See docs/plans/SPIKE-options-premium-websocket.md.

Kept in the tree because the reasoning error is instructive, not because the
number is usable.


Focuses on the case that actually matters: a contract that RISES then FALLS,
because a trailing stop is only armed on a position that has gone profitable.
Contracts whose peak is the first print are skipped -- a trail would never have
been armed on them.
"""
import json, sys, datetime as dt, urllib.parse, urllib.request, pathlib, statistics as st

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from option_poll_interval_sim import load, sample, throttled, trail_exit, blind_spot, pct  # noqa: E402

ENV = "/home/ridopark/src/oh-my-tradeagent/.env"
CACHE = pathlib.Path(__file__).parent / "cache"
CACHE.mkdir(exist_ok=True)


def creds():
    k = s = ""
    for line in pathlib.Path(ENV).read_text().splitlines():
        line = line.strip()
        if "=" not in line or line.startswith("#"):
            continue
        n, v = line.split("=", 1)
        v = v.strip().strip("'\"")
        if n.strip() == "APCA_API_KEY_ID_DATA":
            k = v
        elif n.strip() == "APCA_API_SECRET_KEY_DATA":
            s = v
    return k, s


K, S = creds()


def fetch(sym, day):
    f = CACHE / f"{sym}_{day}.json"
    if f.exists():
        return json.loads(f.read_text())
    rows, token, pages = [], None, 0
    while True:
        q = {"symbols": sym, "start": f"{day}T13:30:00Z", "end": f"{day}T20:00:00Z", "limit": "10000"}
        if token:
            q["page_token"] = token
        url = "https://data.alpaca.markets/v1beta1/options/trades?" + urllib.parse.urlencode(q)
        req = urllib.request.Request(url, headers={"APCA-API-KEY-ID": K, "APCA-API-SECRET-KEY": S})
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                d = json.load(r)
        except Exception as e:
            print(f"  {sym} {day}: fetch failed {e}")
            return []
        rows.extend(d.get("trades", {}).get(sym, []))
        pages += 1
        token = d.get("next_page_token")
        if not token or pages > 20:
            break
    f.write_text(json.dumps(rows))
    return rows


TRAIL = 0.35  # /live operator trailing stop default
results, bs2k, bs500 = [], [], []

# (expiry, [trading days that week]) — a contract must exist on the day we replay.
BATCHES = [
    ("260817", ["2026-08-10", "2026-08-11", "2026-08-12", "2026-08-13", "2026-08-14"]),
    ("260810", ["2026-08-04", "2026-08-05", "2026-08-06", "2026-08-07"]),
    ("260803", ["2026-07-28", "2026-07-29", "2026-07-30", "2026-07-31"]),
]
STRIKES = [764, 767, 770, 773, 776, 779, 782, 785]

for EXP, DAYS in BATCHES:
  for day in DAYS:
    for strike in STRIKES:
        for cp in ("C", "P"):
            sym = f"SPY{EXP}{cp}{strike*1000:08d}"
            rows = fetch(sym, day)
            if len(rows) < 500:
                continue
            f = CACHE / f"{sym}_{day}.json"
            series = load(str(f))
            # Only contracts that actually RAN UP before falling: a trail is armed
            # on a winner. Require the peak to be >=5% above the open and not in
            # the first 10% of prints.
            prices = [p for _, p in series]
            peak_i = max(range(len(prices)), key=lambda i: prices[i])
            if prices[peak_i] < prices[0] * 1.05 or peak_i < len(prices) * 0.10:
                continue
            e2 = throttled(sample(series, 2000))
            e5 = throttled(sample(series, 500))
            t2, p2, k2 = trail_exit(e2, TRAIL)
            t5, p5, k5 = trail_exit(e5, TRAIL)
            if t2 is None or t5 is None:
                continue
            bs2k += blind_spot(series, 2000)
            bs500 += blind_spot(series, 500)
            results.append(dict(sym=sym, day=day, n=len(series), peak2=k2, peak5=k5,
                                p2=p2, p5=p5, dt=t5 - t2, dpct=(p5 - p2) / p2 * 100,
                                emit2=len(e2), emit5=len(e5)))

print(f"\n{'='*92}")
print(f"SWEEP — {len(results)} (contract, day) pairs that ran up >=5% then fell "
      f"(trail {TRAIL*100:.0f}%)")
print(f"{'='*92}")
print(f"{'contract':<22}{'day':<12}{'peak2s':>8}{'peak.5s':>9}{'exit2s':>8}{'exit.5s':>9}"
      f"{'Δt(s)':>9}{'Δprice%':>9}")
for r in sorted(results, key=lambda r: r["dpct"]):
    print(f"{r['sym']:<22}{r['day']:<12}{r['peak2']:>8.2f}{r['peak5']:>9.2f}"
          f"{r['p2']:>8.2f}{r['p5']:>9.2f}{r['dt']:>9.1f}{r['dpct']:>+9.2f}")

if results:
    d = [r["dpct"] for r in results]
    better = sum(1 for x in d if x > 0.05)
    worse = sum(1 for x in d if x < -0.05)
    same = len(d) - better - worse
    print(f"\n  500ms exit price vs 2000ms:")
    print(f"    better {better}   same(±0.05%) {same}   worse {worse}   of {len(d)}")
    print(f"    mean {st.mean(d):+.3f}%   median {st.median(d):+.3f}%   "
          f"min {min(d):+.2f}%   max {max(d):+.2f}%")
    em2 = sum(r["emit2"] for r in results)
    em5 = sum(r["emit5"] for r in results)
    print(f"\n  Temporal signals emitted: {em2} @2000ms -> {em5} @500ms "
          f"({(em5/em2-1)*100:+.1f}% for 4x the polling)")
    print(f"\n  Blind spot (adverse move inside one interval), pooled:")
    print(f"    2000ms  p95 {pct(bs2k,.95)*100:.3f}%  p99 {pct(bs2k,.99)*100:.3f}%  "
          f"max {max(bs2k)*100:.3f}%")
    print(f"     500ms  p95 {pct(bs500,.95)*100:.3f}%  p99 {pct(bs500,.99)*100:.3f}%  "
          f"max {max(bs500)*100:.3f}%")
