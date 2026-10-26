import pandas as pd, numpy as np
rng = np.random.default_rng(11)
e = pd.read_parquet("events.parquet")
def boot(df, col, B=2000):
    df = df.dropna(subset=[col]); g = df.groupby("d")[col].agg(["sum","count"])
    if len(g) < 3: return (np.nan,)*3
    idx = rng.integers(0, len(g), (B, len(g)))
    s, c = g["sum"].values[idx].sum(1), g["count"].values[idx].sum(1)
    return df[col].mean(), *np.percentile(s/c, [2.5, 97.5])
print("FIRE RATES"); print(e.groupby(["half","kind"]).agg(n=("fired","size"), fire=("fired","mean"), gap=("gap","mean")).round(3))
f = e[e.fired==1]
for sub, name in [(f[f.gap==0], "INTRADAY CROSS"), (f[f.gap==1], "GAP-THROUGH")]:
    print(f"\n=== {name}  (signed return, bps; mean [95% day-clustered CI]) ===")
    for half in ["FIT","TEST"]:
        for kind in ["real","placebo"]:
            x = sub[(sub.half==half)&(sub.kind==kind)]
            cells = [f"n={len(x)}"]
            for c in ["r5","r15","r30","r60","rclose","mfe","mae"]:
                m, lo, hi = boot(x, c); cells.append(f"{c}:{m*1e4:+.0f}[{lo*1e4:+.0f},{hi*1e4:+.0f}]")
            print(half, kind.ljust(7), " ".join(cells))
