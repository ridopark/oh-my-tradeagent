#!/usr/bin/env python3
"""Does the replay data even RESOLVE 500ms? And what happens in volatile windows?

Two things the whole-day averages could be hiding:
  1. If trade prints arrive slower than every 500ms, a 500ms poll re-reads the
     same value 4x and CANNOT differ from a 2s poll. That would make my result
     an artifact of the proxy, not a fact about the market.
  2. Whole-day stats are dominated by calm periods. The claim needs testing in
     the volatile windows specifically.
"""
import json, sys, pathlib, statistics as st
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from option_poll_interval_sim import load, sample, throttled, trail_exit, pct  # noqa: E402

CACHE = pathlib.Path(__file__).parent / "cache"


def gaps(series):
    return [series[i][0] - series[i - 1][0] for i in range(1, len(series))]


def vol_windows(series, win=60.0, top_frac=0.05):
    """Return the most volatile `top_frac` of `win`-second windows, by range/mid."""
    buckets = {}
    t0 = series[0][0]
    for t, p in series:
        buckets.setdefault(int((t - t0) // win), []).append(p)
    scored = []
    for k, ps in buckets.items():
        if len(ps) < 5:
            continue
        mid = st.median(ps)
        if mid > 0:
            scored.append(((max(ps) - min(ps)) / mid, k))
    scored.sort(reverse=True)
    keep = {k for _, k in scored[: max(1, int(len(scored) * top_frac))]}
    return [(t, p) for t, p in series if int((t - t0) // win) in keep], scored


def blind_in(series, interval_ms):
    """Adverse move inside one interval, restricted to the given series."""
    step = interval_ms / 1000.0
    res, i = [], 0
    t = series[0][0]
    while t <= series[-1][0] and i < len(series):
        lo = series[i][1]
        start = series[i][1]
        j = i
        while j < len(series) and series[j][0] < t + step:
            lo = min(lo, series[j][1])
            j += 1
        if j > i and start > 0:
            res.append((start - lo) / start)
        i = max(j, i + 1)
        t += step
    return res


files = sorted(CACHE.glob("*.json"))
all_gaps, calm_b2, calm_b5, vol_b2, vol_b5 = [], [], [], [], []
per_contract = []

for f in files:
    rows = json.loads(f.read_text())
    if len(rows) < 500:
        continue
    s = load(str(f))
    g = gaps(s)
    all_gaps += g
    v, scored = vol_windows(s)
    if len(v) > 50:
        vol_b2 += blind_in(v, 2000)
        vol_b5 += blind_in(v, 500)
    calm_b2 += blind_in(s, 2000)
    calm_b5 += blind_in(s, 500)
    per_contract.append((f.stem, len(s), st.median(g),
                         sum(1 for x in g if x <= 0.5) / len(g) * 100,
                         scored[0][0] * 100 if scored else 0))

print(f"\n{'='*86}")
print("1. DOES THE DATA RESOLVE 500ms?  (gap between consecutive trade prints)")
print(f"{'='*86}")
print(f"   contracts pooled     : {len(per_contract)}   prints: {len(all_gaps)+len(per_contract)}")
print(f"   median gap           : {st.median(all_gaps)*1000:>8.0f} ms")
print(f"   p25 / p75            : {pct(all_gaps,.25)*1000:>8.0f} ms / {pct(all_gaps,.75)*1000:.0f} ms")
print(f"   gaps <= 500ms        : {sum(1 for x in all_gaps if x<=0.5)/len(all_gaps)*100:>8.1f}%")
print(f"   gaps <= 2000ms       : {sum(1 for x in all_gaps if x<=2.0)/len(all_gaps)*100:>8.1f}%")
print(f"   gaps  > 2000ms       : {sum(1 for x in all_gaps if x>2.0)/len(all_gaps)*100:>8.1f}%")

print(f"\n{'='*86}")
print("2. BLIND SPOT — whole day vs the most volatile 5% of minutes")
print(f"{'='*86}")
print(f"   {'':<22}{'p50':>9}{'p95':>9}{'p99':>9}{'max':>9}")
for name, b2, b5 in (("whole day", calm_b2, calm_b5), ("volatile 5%", vol_b2, vol_b5)):
    if not b2:
        continue
    print(f"   {name+' @2000ms':<22}{pct(b2,.5)*100:>8.3f}%{pct(b2,.95)*100:>8.3f}%"
          f"{pct(b2,.99)*100:>8.3f}%{max(b2)*100:>8.3f}%")
    print(f"   {name+' @ 500ms':<22}{pct(b5,.5)*100:>8.3f}%{pct(b5,.95)*100:>8.3f}%"
          f"{pct(b5,.99)*100:>8.3f}%{max(b5)*100:>8.3f}%")

print(f"\n{'='*86}")
print("3. PER CONTRACT — print density and the day's most volatile minute")
print(f"{'='*86}")
print(f"   {'contract/day':<36}{'prints':>8}{'med gap':>10}{'<=500ms':>9}{'vol min':>9}")
for name, n, med, frac, topvol in sorted(per_contract, key=lambda r: -r[1])[:12]:
    print(f"   {name:<36}{n:>8}{med*1000:>9.0f}ms{frac:>8.1f}%{topvol:>8.1f}%")
