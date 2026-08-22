#!/usr/bin/env python3
"""Baseline experiment E1 + E2 — see ../PLAN-2026-08-21-position-context-correlator.md

Usage:  python3 baseline_experiment.py <chat.tsv> <bars.ndjson>

Reproduces the 2026-08-21 run verbatim. Re-run against >=3 months of history and compare
against the numbers recorded in the plan. Do NOT re-derive with a different method.
"""
import json, re, sys, math, datetime, collections, statistics as st

SYMS = {"MU","SPY","AAPL","QQQ","TSLA","AMD","INTC","NVDA","AMZN",
        "AVGO","GOOGL","ORCL","META","HOOD","NBIS"}
BEAR = r"\b(dead|heavy|weak|wrecked|reject\w*|dying|dump\w*|fade|fading|bleed\w*|red|puts?|short|breakdown|lower|dropping|sinking|ugly|trash)\b"
BULL = r"\b(beast|strong|ripping|rips?|breakout|breaking out|sending|sends?|green|calls?|long|bounce|bouncing|squeeze|squeezing|higher|pumping|moon|flying)\b"
HOR = [5, 15, 30, 60]

def tstat(v, minn=20):
    if len(v) < minn: return 0.0
    sd = st.pstdev(v)
    return 0.0 if sd == 0 else st.mean(v) / (sd / math.sqrt(len(v)))

def load_bars(path):
    by_sym = collections.defaultdict(dict); series = collections.defaultdict(list)
    for line in open(path):
        b = json.loads(line)
        ts = int(datetime.datetime.strptime(b["t"][:19], "%Y-%m-%dT%H:%M:%S")
                 .replace(tzinfo=datetime.timezone.utc).timestamp()) // 60
        by_sym[b["s"]][ts] = b["c"]; series[b["s"]].append((ts, b["c"], b["v"]))
    for s in series: series[s].sort()
    return by_sym, series

def e1(chat_path, by_sym):
    """Chat sentiment -> forward underlying return."""
    def fwd(sym, m0, k):
        a = by_sym[sym].get(m0)
        if a is None:
            for d in (1, 2, 3):
                a = by_sym[sym].get(m0 + d)
                if a is not None: m0 += d; break
        b = by_sym[sym].get(m0 + k)
        return None if (a is None or b is None or a <= 0) else (b - a) / a * 100

    buckets = {"bull": {k: [] for k in HOR}, "bear": {k: [] for k in HOR}}
    cnt = collections.Counter()
    for line in open(chat_path):
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 4: continue
        _, ep, _author, content = parts[0], int(parts[1]), parts[2], parts[3]
        hb, hr = bool(re.search(BULL, content.lower())), bool(re.search(BEAR, content.lower()))
        if hb == hr: continue                      # skip neutral AND mixed
        tag = "bull" if hb else "bear"
        for t in set(re.findall(r"\b[A-Z]{2,5}\b", content)):
            if t not in SYMS: continue
            cnt[tag] += 1
            for k in HOR:
                r = fwd(t, ep // 60, k)
                if r is not None: buckets[tag][k].append(r)

    print(f"\n=== E1: chat sentiment (bull={cnt['bull']} bear={cnt['bear']}) ===")
    print(f"{'hor':>4} | {'BULL n':>7} {'mean%':>8} {'t':>6} | {'BEAR n':>7} {'mean%':>8} {'t':>6}")
    for k in HOR:
        bl, br = buckets["bull"][k], buckets["bear"][k]
        if len(bl) < 5 or len(br) < 5: continue
        print(f"{k:>3}m | {len(bl):>7} {st.mean(bl):>8.3f} {tstat(bl,5):>6.2f} |"
              f" {len(br):>7} {st.mean(br):>8.3f} {tstat(br,5):>6.2f}")

def e2(series):
    """Classic TA signals -> forward underlying return."""
    hor = [5, 15, 30]
    sig = collections.defaultdict(lambda: collections.defaultdict(list))
    base = {k: [] for k in hor}
    for sym, rows in series.items():
        closes = [c for _, c, _ in rows]; vols = [v for _, _, v in rows]; n = len(rows)
        for i in range(60, n - 61):
            m, c, v = rows[i]
            if rows[i + 30][0] - m > 45: continue        # skip session gaps
            fwd = {k: (rows[i + k][1] - c) / c * 100
                   for k in hor if i + k < n and rows[i + k][0] - m <= k + 15}
            if len(fwd) < len(hor): continue
            for k in hor: base[k].append(fwd[k])
            w20, w60 = closes[i-20:i], closes[i-60:i]
            sma20, sma60 = sum(w20)/20, sum(w60)/60
            z = (c - sma20) / (st.pstdev(w20) or 1e-9)
            vspike = v / ((sum(vols[i-20:i]) / 20) or 1e-9)
            g = l = 0.0
            for j in range(i-14, i):
                d = closes[j] - closes[j-1]; g += max(d, 0); l += max(-d, 0)
            rsi = 100.0 if l == 0 else 100 - 100 / (1 + (g/14) / (l/14))
            for name, cond in (
                ("z>2 (stretched up)", z > 2), ("z<-2 (stretched down)", z < -2),
                ("RSI>75", rsi > 75), ("RSI<25", rsi < 25),
                ("vol spike >3x", vspike > 3),
                ("vol spike >3x & z>1", vspike > 3 and z > 1),
                ("vol spike >3x & z<-1", vspike > 3 and z < -1),
                ("sma20>sma60 cross-up", sma20 > sma60 and closes[i-1] < sma60),
            ):
                if cond:
                    for k in hor: sig[name][k].append(fwd[k])

    print(f"\n=== E2: TA signals (baseline n={len(base[5]):,}, "
          f"mean5m={st.mean(base[5]):+.4f}%) ===")
    print(f"{'signal':<26} {'n':>6} | " + " | ".join(f"{k}m mean%   t" for k in hor))
    for name in sig:
        r = sig[name]
        if len(r[5]) < 30: continue
        print(f"{name:<26} {len(r[5]):>6} | " +
              " | ".join(f"{st.mean(r[k]):+8.4f} {tstat(r[k]):>5.2f}" for k in hor))
    print("\n|t|>=2 ~ 'not noise'.  NOTE: effects near 0.02% are FAR below options spread cost;")
    print("no result here is tradeable without modelling spread + delta leverage.")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    by_sym, series = load_bars(sys.argv[2])
    print(f"bars: {sum(len(v) for v in by_sym.values()):,} across {len(by_sym)} symbols")
    e1(sys.argv[1], by_sym)
    e2(series)
