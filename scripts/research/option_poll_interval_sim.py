#!/usr/bin/env python3
"""What does dropping the premium poll 2000ms -> 500ms actually buy?

Replays real OPRA trade prints through the ACTUAL pipeline:
  sample at interval I  ->  1% min-move throttle (SubscribePremiumActivityImpl)
  ->  trailing stop on the emitted series

Two measurements:
  A. BLIND SPOT  - how far the price moves against you inside one sampling
     interval, i.e. movement the workflow structurally cannot see.
  B. TRAIL EXIT  - when/where a trailing stop actually fires at each interval.

CAVEAT: there is no historical options QUOTES endpoint, so this uses trade
prints as the price path. The exit path evaluates the BID; trade prints sit
inside the spread. This measures the SHAPE AND SPEED of the path -- which is
what the interval question turns on -- not absolute fill prices.
"""
import json, sys, datetime as dt, statistics as st

THROTTLE_PCT = 0.01  # market-data.premium-emit-delta-pct default


def load(path):
    rows = json.load(open(path))
    out = []
    for r in rows:
        t = r["t"]
        # RFC3339 with variable-precision nanos
        base, _, frac = t.partition(".")
        micros = int((frac.rstrip("Z") + "000000")[:6]) if frac else 0
        ts = dt.datetime.strptime(base, "%Y-%m-%dT%H:%M:%S").replace(
            tzinfo=dt.timezone.utc, microsecond=micros
        )
        out.append((ts.timestamp(), float(r["p"])))
    out.sort()
    return out


def sample(series, interval_ms):
    """Last print at or before each grid instant -- what a poll would return."""
    step = interval_ms / 1000.0
    t0, t1 = series[0][0], series[-1][0]
    obs, i, last = [], 0, None
    t = t0
    while t <= t1:
        while i < len(series) and series[i][0] <= t:
            last = series[i][1]
            i += 1
        if last is not None:
            obs.append((t, last))
        t += step
    return obs


def throttled(obs):
    """1% min-move gate from the LAST EMITTED value."""
    out, base = [], None
    for t, p in obs:
        if base is None or abs(p - base) >= abs(base * THROTTLE_PCT):
            out.append((t, p))
            base = p
    return out


def blind_spot(series, interval_ms):
    """Max adverse (downward) excursion inside one interval, as % of the price
    observed at the interval's start. This is movement no poll at that rate
    can see until the next sample."""
    step = interval_ms / 1000.0
    res, i = [], 0
    t = series[0][0]
    last_seen = series[0][1]
    while t <= series[-1][0]:
        lo = last_seen
        j = i
        while j < len(series) and series[j][0] < t + step:
            lo = min(lo, series[j][1])
            last_seen = series[j][1]
            j += 1
        if j > i and last_seen > 0:
            start = series[i][1]
            if start > 0:
                res.append((start - lo) / start)
        i = j
        t += step
    return res


def trail_exit(emitted, trail_pct):
    peak = None
    for t, p in emitted:
        if peak is None or p > peak:
            peak = p
        if p <= peak * (1 - trail_pct):
            return t, p, peak
    return None, None, peak


def pct(xs, q):
    if not xs:
        return 0.0
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(q * len(xs)))]


def main():
    label, path = sys.argv[1], sys.argv[2]
    series = load(path)
    print(f"\n{'='*78}\n{label}   {len(series)} prints   "
          f"{dt.datetime.fromtimestamp(series[0][0], dt.timezone.utc):%H:%M}-"
          f"{dt.datetime.fromtimestamp(series[-1][0], dt.timezone.utc):%H:%M} UTC   "
          f"{series[0][1]:.2f} -> {series[-1][1]:.2f}\n{'='*78}")

    print("\nA. BLIND SPOT — adverse move inside one sampling interval (% of price)")
    print(f"   {'interval':>10} {'median':>9} {'p95':>9} {'p99':>9} {'max':>9}  {'>1%':>7}")
    for ims in (2000, 500, 250):
        b = blind_spot(series, ims)
        over = sum(1 for x in b if x > 0.01) / len(b) * 100 if b else 0
        print(f"   {ims:>8}ms {pct(b,.5)*100:>8.3f}% {pct(b,.95)*100:>8.3f}% "
              f"{pct(b,.99)*100:>8.3f}% {max(b)*100:>8.3f}% {over:>6.2f}%")

    print("\nB. TICKS EMITTED to the workflow (after the 1% throttle)")
    for ims in (2000, 500, 250):
        e = throttled(sample(series, ims))
        print(f"   {ims:>8}ms  {len(sample(series,ims)):>7} sampled -> {len(e):>5} emitted")

    print("\nC. TRAILING-STOP EXIT by interval")
    for trail in (0.15, 0.25, 0.35):
        print(f"\n   trail {trail*100:.0f}%:")
        base = None
        for ims in (2000, 500, 250):
            e = throttled(sample(series, ims))
            t, p, peak = trail_exit(e, trail)
            if t is None:
                print(f"     {ims:>6}ms  no exit (peak {peak:.2f})")
                continue
            when = dt.datetime.fromtimestamp(t, dt.timezone.utc).strftime("%H:%M:%S")
            if base is None:
                base = (t, p)
                print(f"     {ims:>6}ms  exit {when}  at {p:>6.2f}  (peak {peak:.2f})")
            else:
                dts, dp = t - base[0], p - base[1]
                print(f"     {ims:>6}ms  exit {when}  at {p:>6.2f}  (peak {peak:.2f})"
                      f"   Δt {dts:+.2f}s  Δprice {dp:+.2f} ({dp/base[1]*100:+.2f}%)")


if __name__ == "__main__":
    main()
