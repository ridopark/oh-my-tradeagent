"""Generic-level family (PREREG2): ABOVE=premarket high, BELOW=premarket low. FIT 2016-2021, TEST 2022-2025."""
import pandas as pd, numpy as np, sys, datetime as dt
from multiprocessing import Pool
from hyp import run, run_overnight, volok
def one(sym):
    df = pd.read_json(f"decade/{sym}.jsonl.gz", lines=True, compression="gzip")
    df["t"] = pd.to_datetime(df.t, utc=True).dt.tz_convert("America/New_York"); df["d"] = df.t.dt.date
    mm = df.t.dt.hour*60+df.t.dt.minute
    pm = df[(mm>=240)&(mm<570)].groupby("d").agg(PMH=("h","max"), PML=("l","min"), n=("c","size"))
    rth = df[(mm>=570)&(mm<960)].copy(); rth["m"] = mm[(mm>=570)&(mm<960)]-570; rth["s"] = sym
    sess = {(sym,d): g.sort_values("m").reset_index(drop=True) for d,g in rth.groupby("d")}
    pm = pm[(pm.n >= 10) & (pm.PMH > pm.PML)]
    legs = pd.concat([pd.DataFrame({"s":sym,"d":pm.index,"dir":"ABOVE","lvl":pm.PMH.values}),
                      pd.DataFrame({"s":sym,"d":pm.index,"dir":"BELOW","lvl":pm.PML.values})])
    legs = legs[legs.d < dt.date(2026,1,1)]
    vo = volok(sess)
    R = run(legs, sess, vo); R["ts"] = R.ts.astype(str)
    R = pd.concat([R, run_overnight(legs, sess, vo)])
    R["half"] = np.where(R.d >= dt.date(2022,1,1), "TEST", "FIT")
    R.to_parquet(f"decade/hyp_{sym}.parquet"); return sym, len(legs), len(R)
if __name__ == "__main__":
    with Pool(int(sys.argv[1])) as p:
        for r in p.imap_unordered(one, sys.argv[2].split(",")): print(r, flush=True)
