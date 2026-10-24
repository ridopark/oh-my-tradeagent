import pandas as pd, numpy as np, datetime as dt
from hyp import run_overnight, volok
bars = pd.read_parquet("rth_1m.parquet"); bars["m"] = bars.t.dt.hour*60+bars.t.dt.minute-570
sess = {k: g.sort_values("m").reset_index(drop=True) for k,g in bars.groupby(["s","d"])}
legs = pd.read_csv("legs_raw.psv", sep="|", header=None, names=["d","wf","ts","s","dir","lvl","mode","mult"])
legs["d"] = pd.to_datetime(legs.d).dt.date; legs = legs.drop_duplicates(["d","s","dir","lvl"])
R = run_overnight(legs, sess, volok(sess)); R["half"] = np.where(R.d >= dt.date(2026,9,1), "TEST", "FIT")
R0 = pd.read_parquet("hyp2026.parquet"); R0["ts"] = R0.ts.astype(str)
pd.concat([R0, R]).to_parquet("hyp2026_all.parquet")
