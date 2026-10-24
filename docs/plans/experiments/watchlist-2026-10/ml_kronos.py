"""Kronos-small as a direction signal at the watchlist trigger: context = 400 1m bars ending at the trigger bar
(spanning prior sessions as needed), forecast 60 bars, signal = sign(pred close[+60] - last close) * leg sign.
Evaluated on 2026 real legs, 2026 placebo legs, and a stratified sample of 10-year generic breaks."""
import sys, time, warnings, datetime as dt; warnings.filterwarnings("ignore")
import pandas as pd, numpy as np, torch
sys.path.insert(0, "ext/kronos"); from model import Kronos, KronosTokenizer, KronosPredictor
rng = np.random.default_rng(9); LOOK, PRED = 400, 60
tok = KronosTokenizer.from_pretrained("NeoQuasar/Kronos-Tokenizer-base"); mdl = Kronos.from_pretrained("NeoQuasar/Kronos-small")
P = KronosPredictor(mdl, tok, device="cuda" if torch.cuda.is_available() else "cpu", max_context=512)
def load_sym(sym):
    try: df = pd.read_json(f"decade/{sym}.jsonl.gz", lines=True, compression="gzip")
    except FileNotFoundError: df = pd.DataFrame()
    r2 = pd.read_parquet("rth_1m.parquet"); r2 = r2[r2.s==sym]
    df = pd.concat([df, r2[["t","o","h","l","c","v"]].assign(t=r2.t.dt.tz_convert("UTC").dt.strftime("%Y-%m-%dT%H:%M:%SZ"))]) if len(df) else r2[["t","o","h","l","c","v"]].assign(t=r2.t.dt.tz_convert("UTC").dt.strftime("%Y-%m-%dT%H:%M:%SZ"))
    df["t"] = pd.to_datetime(df.t, utc=True).dt.tz_convert("America/New_York"); df = df.drop_duplicates("t").sort_values("t")
    mm = df.t.dt.hour*60+df.t.dt.minute; df = df[(mm>=570)&(mm<960)].reset_index(drop=True)   # RTH only, continuous across sessions
    df["d"] = df.t.dt.date; df["m"] = mm[(mm>=570)&(mm<960)].values-570; return df
def contexts(legs):
    """legs: DataFrame s,d,fire_min,sg,r60. Returns list of (df_ctx, x_ts, y_ts, meta)."""
    out = []
    for sym, L in legs.groupby("s"):
        df = load_sym(sym); idx = {(d, m): i for i, (d, m) in enumerate(zip(df.d, df.m))}
        for r in L.itertuples():
            i = idx.get((r.d, int(r.fire_min)))
            if i is None or i < LOOK: continue
            ctx = df.iloc[i-LOOK+1:i+1]; fut = df.iloc[i+1:i+1+PRED]
            if len(fut) < PRED: continue
            x = ctx[["o","h","l","c","v"]].rename(columns={"o":"open","h":"high","l":"low","c":"close","v":"volume"}).reset_index(drop=True)
            x["amount"] = x.volume * x[["open","high","low","close"]].mean(axis=1)
            out.append((x, pd.Series(ctx.t.values), pd.Series(fut.t.values), dict(s=sym, d=r.d, sg=r.sg, r60=r.r60, last=float(ctx.c.iloc[-1]), kind=r.kind)))
    return out
def run(items, tag, bs=32):
    preds = []; t0 = time.time()
    for i in range(0, len(items), bs):
        chunk = items[i:i+bs]
        print(f"batch {i}/{len(items)} elapsed {time.time()-t0:.0f}s", flush=True)
        pr = P.predict_batch([c[0] for c in chunk], [c[1] for c in chunk], [c[2] for c in chunk], pred_len=PRED, T=0.6, top_p=0.9, sample_count=1, verbose=False)
        for (x, xt, yt, meta), p in zip(chunk, pr): preds.append(dict(meta, pred60=float(p["close"].iloc[-1]/meta["last"]-1)))
    R = pd.DataFrame(preds); R["fcast"] = R.pred60*R.sg*1e4; R["r60b"] = R.r60*1e4; R["agree"] = R.fcast > 0
    return R
if __name__ == "__main__":
    e = pd.read_parquet("events.parquet"); e = e[(e.fired==1)&(e.gap==0)&(e.d!=dt.date(2026,7,1))&e.r60.notna()].copy(); e["sg"] = np.where(e.dir=="ABOVE",1,-1)
    real = e[e.kind=="real"]; plac = e[e.kind=="placebo"].sample(400, random_state=1)
    Dd = pd.read_parquet("ml_decade_legs.parquet"); Dd["kind"] = "decade"; Dd["yr"] = pd.to_datetime(Dd.d).dt.year
    dec = Dd.groupby("yr", group_keys=False).apply(lambda g: g.sample(min(len(g), 100), random_state=2))
    t0 = time.time(); items = contexts(pd.concat([real.assign(kind="real"), plac.assign(kind="placebo"), dec[["s","d","fire_min","sg","r60","kind"]]]))
    print(f"contexts built: {len(items)} ({time.time()-t0:.0f}s)"); t0 = time.time(); R = run(items, "all"); print(f"predicted in {time.time()-t0:.0f}s"); R.to_parquet("ml_kronos_preds.parquet")
    R["half"] = np.where(pd.to_datetime(R.d) >= "2026-09-01", "TEST", "FIT")
    from scipy.stats import spearmanr
    for kind, g in R.groupby("kind"):
        grp = g.groupby("half") if kind != "decade" else g.groupby(pd.cut(pd.to_datetime(g.d).dt.year, [2015,2021,2025,2026], labels=["FIT16-21","TEST22-25","2026"]))
        for h, x in grp:
            if len(x) < 20: continue
            rho = spearmanr(x.fcast, x.r60b).correlation; a, d_ = x[x.agree], x[~x.agree]
            gg = x.groupby("d").apply(lambda z: z[z.agree].r60b.mean() - z[~z.agree].r60b.mean() if z.agree.any() and (~z.agree).any() else np.nan).dropna()
            print(f"{kind:8s} {str(h):10s} n={len(x):4d} spearman(fcast,r60)={rho:+.3f} | dir-accuracy={(np.sign(x.fcast)==np.sign(x.r60b)).mean():.3f} | AGREE n={len(a)} r60={a.r60b.mean():+.1f} | DISAGREE n={len(d_)} r60={d_.r60b.mean():+.1f} | agree-minus-disagree={a.r60b.mean()-d_.r60b.mean():+.1f} (day-paired median {gg.median():+.1f}, n_days={len(gg)})")
