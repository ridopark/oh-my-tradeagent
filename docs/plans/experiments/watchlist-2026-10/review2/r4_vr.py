import pandas as pd, numpy as np
rng = np.random.default_rng(3)
e = pd.read_parquet("events.parquet"); e = e[(e.fired==1)&(e.gap==0)]
B = pd.read_parquet("rth_1m.parquet"); B["m"] = B.t.dt.hour*60+B.t.dt.minute-570
C = {k: g.set_index("m").c.reindex(range(390)).ffill().values for k, g in B.groupby(["s","d"])}
for kind in ["real","placebo"]:
    x = e[e.kind==kind]; rows = []
    for r in x.itertuples():
        c = C.get((r.s, r.d)); f0 = int(r.fire_min)
        if c is None or f0+60 >= 390 or np.isnan(c[f0]): continue
        lr = np.diff(np.log(c[f0:f0+61]))
        rows.append((r.d, lr[:30].sum()**2, (lr[:30]**2).sum(), lr.sum()**2, (lr**2).sum()))
    D = pd.DataFrame(rows, columns=["d","q30","rv30","q60","rv60"]); g = D.groupby("d").sum()
    idx = rng.integers(0, len(g), (2000, len(g)))
    for h in ["30","60"]:
        vr = g["q"+h].sum()/g["rv"+h].sum(); bs = g["q"+h].values[idx].sum(1)/g["rv"+h].values[idx].sum(1)
        print(kind, f"n={len(D)} VR{h}={vr:.2f} 95%CI [{np.percentile(bs,2.5):.2f},{np.percentile(bs,97.5):.2f}]")
