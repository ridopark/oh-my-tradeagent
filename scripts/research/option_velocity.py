#!/usr/bin/env python3
"""The metric that actually answers the question.

My earlier "blind spot inside an interval" metric was partly tautological:
a longer window contains more price movement BY CONSTRUCTION. That is not the
same as reacting worse.

The real cost of a slow poll is DETECTION DELAY at the moment the stop is
crossed. If the true price crosses the stop at time t, a poll at interval I
observes it on average I/2 later, and the price keeps moving in the meantime.

    expected extra slippage(2000ms vs 500ms)
        = price velocity during the move  x  (1.000s - 0.250s)

So the question reduces to: how fast does option premium actually move,
per second, during the moves that trigger a stop?
"""
import json, sys, pathlib, statistics as st
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from option_poll_interval_sim import load, pct  # noqa: E402

CACHE = pathlib.Path(__file__).parent / "cache"


def velocities(series, horizon=1.0):
    """|% change| per second, measured over `horizon`-second look-aheads."""
    out, j = [], 0
    for i, (t, p) in enumerate(series):
        if p <= 0:
            continue
        while j < len(series) and series[j][0] < t + horizon:
            j += 1
        if j >= len(series):
            break
        dt = series[j][0] - t
        if dt <= 0:
            continue
        out.append(abs(series[j][1] - p) / p / dt)
    return out


def downward(series, horizon=1.0):
    """Downward-only velocity — the direction a long-call stop cares about."""
    out, j = [], 0
    for i, (t, p) in enumerate(series):
        if p <= 0:
            continue
        while j < len(series) and series[j][0] < t + horizon:
            j += 1
        if j >= len(series):
            break
        dt = series[j][0] - t
        if dt <= 0 or series[j][1] >= p:
            continue
        out.append((p - series[j][1]) / p / dt)
    return out


allv, alld = [], []
for f in sorted(CACHE.glob("*.json")):
    rows = json.loads(f.read_text())
    if len(rows) < 500:
        continue
    s = load(str(f))
    allv += velocities(s)
    alld += downward(s)

DELAY = 1.000 - 0.250  # avg detection delay 2000ms vs 500ms

print(f"\n{'='*80}")
print("PREMIUM VELOCITY (|%| change per second, 1s look-ahead)")
print(f"{'='*80}")
print(f"   samples: {len(allv)}")
for label, xs in (("all moves", allv), ("down moves only", alld)):
    print(f"\n   {label}:")
    print(f"     median      {st.median(xs)*100:>7.3f} %/s   -> extra slippage "
          f"{st.median(xs)*100*DELAY:>6.3f}% of premium")
    for q in (0.90, 0.99, 0.999):
        v = pct(xs, q)
        print(f"     p{q*100:<5.1f}     {v*100:>7.3f} %/s   -> extra slippage "
              f"{v*100*DELAY:>6.3f}% of premium")
    print(f"     max         {max(xs)*100:>7.3f} %/s   -> extra slippage "
          f"{max(xs)*100*DELAY:>6.3f}% of premium")

print(f"\n{'='*80}")
print("READ: 'extra slippage' = what a 2000ms poll costs vs 500ms, in premium %,")
print("      from the 0.75s of extra average detection delay alone.")
print("      CAVEAT: measured on TRADE prints, whose median gap is 1520ms. Quotes")
print("      update far more often, so this UNDERSTATES true velocity.")
