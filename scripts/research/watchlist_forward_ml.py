#!/usr/bin/env python3
"""Forward scorer for the frozen watchlist ML models (docs/plans/PREREG-2026-10-24-watchlist-forward-ml-v1.md).

Reads the watchlist-forward-v1 raw folders (legs.json + stock_1m.ndjson.gz, SPY included), applies v1's trigger and
exclusion rules, computes 24 trigger-time features with the SAME function used to build the frozen context, and
writes each model's probability per leg to <out>/ml_scores.csv. Models: M1 TabPFN-3.5 in-context on
ml_context_v1.parquet, M2 gradient boosting (14 features) refit deterministically from the same context.

  watchlist_forward_ml.py --out ~/data/watchlist-forward --frozen ~/scripts/mlfwd score --day 2026-10-27
  watchlist_forward_ml.py --out ... --frozen ... build-context --bars stock_1m.jsonl.gz --legs legs_raw.psv --earn earn.csv
  watchlist_forward_ml.py --out ... analyze      (refuses before 2027-04-23)
"""
import argparse, csv, gzip, hashlib, json, math, os, sys, warnings
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo
import numpy as np, pandas as pd
warnings.filterwarnings("ignore")
ET = ZoneInfo("America/New_York"); ANALYSIS_DATE = date(2027, 4, 23); PICK = 0.55
F24 = ["fire_min","etf","sg","off","mom5","mom15","from_open","rng_so_far","vol_ratio","bar_body","prev_ret","prev_rng","gap","dow",
       "spy_from_open","spy_prev","spy_rv20","round","nearest_std_bps","dist_PMH","dist_PML","dist_PDH","dist_PDL","earnings_day"]
F14 = F24[:14]

def roundness(v):
    for step in (10, 5, 2.5, 1, 0.5):
        if abs(v/step - round(v/step)) < 1e-6: return step
    return 0.0

def sessions(bars):
    """bars: DataFrame s,d(date),m,o,h,l,c,v with m = minutes after 09:30 (premarket negative). -> dict (s,d) -> dict of arrays."""
    out = {}
    for (s, d), g in bars.groupby(["s", "d"]):
        g = g.sort_values("m"); r = g[(g.m >= 0) & (g.m < 390)]; pm = g[(g.m >= -330) & (g.m < 0)]
        out[(s, d)] = dict(m=r.m.values, o=r.o.values, h=r.h.values, l=r.l.values, c=r.c.values, v=r.v.values,
                           PMH=pm.h.max() if len(pm) else np.nan, PML=pm.l.min() if len(pm) else np.nan)
    return out

def v1_trigger(S, lvl, sg):
    """v1 rule: gap if first RTH open beyond; trigger = first RTH close beyond. -> (status, i, r60)"""
    c, o, m = S["c"], S["o"], S["m"]
    if len(c) < 300: return "no_bars", None, None
    if (o[0] - lvl) * sg > 0: return "gap", None, None
    hit = np.where((c - lvl) * sg > 0)[0]
    if not len(hit): return "no_trigger", None, None
    i = int(hit[0]); j = np.where(m >= m[i] + 60)[0]
    return "fired", i, (sg * (c[j[0]] - c[i]) / c[i] if len(j) else np.nan)

def features(S, prevs, spyS, spy_closes_before, lvl, sg, i, d, earnings_day):
    """24 features at trigger bar i. prevs: list of prior sessions (most recent last, >=1). spy_closes_before: SPY closes
    strictly before d (ascending). Returns None if the leg is out of the training distribution (i < 5)."""
    if i < 5 or not prevs: return None
    c, h, l, v, o, m = (S[k] for k in ("c","h","l","v","o","m")); p = prevs[-1]; pc = p["c"][-1]
    si = int(np.searchsorted(spyS["m"], m[i])) if spyS is not None else None
    sc = np.asarray(spy_closes_before, float)
    rets = sc[1:] / sc[:-1] - 1 if len(sc) >= 2 else np.array([])
    H5 = max(x["h"].max() for x in prevs[-5:]) if len(prevs) >= 5 else np.nan   # 5-session H/L undefined until 5 prior sessions exist
    L5 = min(x["l"].min() for x in prevs[-5:]) if len(prevs) >= 5 else np.nan
    cands = [p["h"].max(), p["l"].min(), S["PMH"], S["PML"], H5, L5]            # PDH, PDL, PMH, PML, PWH, PWL (round-14 table definition)
    f = dict(fire_min=float(m[i]), etf=float(S["etf"]), sg=float(sg), off=sg*(lvl/pc-1),
        mom5=sg*(c[i]/c[i-5]-1), mom15=sg*(c[i]/c[max(0,i-15)]-1), from_open=sg*(c[i]/o[0]-1), rng_so_far=(h[:i+1].max()-l[:i+1].min())/o[0],
        vol_ratio=v[max(0,i-5):i+1].mean()/max(v[:i+1].mean(),1), bar_body=sg*(c[i]-o[i])/o[i], prev_ret=sg*(p["c"][-1]/p["c"][0]-1),
        prev_rng=(p["h"].max()-p["l"].min())/pc, gap=sg*(o[0]/pc-1), dow=float(d.weekday()),
        spy_from_open=(spyS["c"][si]/spyS["o"][0]-1)*sg if spyS is not None and si < len(spyS["c"]) else np.nan,
        spy_prev=(rets[-1]*sg) if len(rets) else np.nan,                       # SPY return of the PRIOR session (not the same day)
        spy_rv20=float(np.std(rets[-20:])) if len(rets) >= 10 else np.nan,
        round=roundness(lvl), nearest_std_bps=float(np.nanmin([abs(lvl/x-1) for x in cands]))*1e4,
        dist_PMH=(lvl/S["PMH"]-1)*1e4, dist_PML=(lvl/S["PML"]-1)*1e4, dist_PDH=(lvl/p["h"].max()-1)*1e4, dist_PDL=(lvl/p["l"].min()-1)*1e4,
        earnings_day=earnings_day)
    return f

# ---------- data loading ----------
def load_raw(out, day):
    folder = os.path.join(out, "raw", day.isoformat())
    meta = json.load(open(os.path.join(folder, "legs.json")))
    rows = [json.loads(l) for l in gzip.open(os.path.join(folder, "stock_1m.ndjson.gz"), "rt")]
    b = pd.DataFrame(rows); b["d"] = pd.to_datetime(b.d).dt.date
    return meta["legs"], b

def earnings_flags(day, tickers):
    """Best-effort: Nasdaq calendar for day and the prior weekday (after-close reports). NaN on failure."""
    try:
        import urllib.request
        got = set()
        for dd in (day, day - timedelta(days=3 if day.weekday()==0 else 1)):
            req = urllib.request.Request(f"https://api.nasdaq.com/api/calendar/earnings?date={dd.isoformat()}", headers={"User-Agent": "Mozilla/5.0", "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=20) as r: j = json.load(r)
            for row in (j.get("data") or {}).get("rows") or []: got.add(row.get("symbol"))
        return {t: float(t in got) for t in tickers}
    except Exception:
        return {t: np.nan for t in tickers}

def spy_closes(out, frozen, upto):
    seed = pd.read_csv(os.path.join(frozen, "spy_closes_seed.csv")); seed["d"] = pd.to_datetime(seed.d).dt.date
    extra_p = os.path.join(out, "ml_spy_closes.csv")
    extra = pd.read_csv(extra_p) if os.path.exists(extra_p) else pd.DataFrame(columns=["d","close"])
    if len(extra): extra["d"] = pd.to_datetime(extra.d).dt.date
    allc = pd.concat([seed, extra]).drop_duplicates("d").sort_values("d")
    return allc[allc.d < upto].close.tolist(), allc

def score_day(day, out, frozen, write=True):
    legs, bars = load_raw(out, day); S = sessions(bars)
    for k in S: S[k]["etf"] = k[0] in ("SPY","QQQ")
    open_et = datetime.combine(day, datetime.min.time().replace(hour=9, minute=30), ET)
    closes_before, allc = spy_closes(out, frozen, day)
    spyS = S.get(("SPY", day)); earn = earnings_flags(day, sorted({l["s"] for l in legs}))
    rows = []
    for L in legs:
        s, sg, lvl = L["s"], 1 if L["dir"]=="ABOVE" else -1, float(L["lvl"])
        armed = datetime.fromisoformat(L["armed_at"].replace(" ", "T") + ("" if "+" in L["armed_at"][-6:] else "+00:00"))
        row = dict(d=day.isoformat(), s=s, dir=L["dir"], lvl=lvl)
        if armed >= open_et or (s, day) not in S: rows.append(dict(row, status="armed_after_open" if armed >= open_et else "no_bars")); continue
        st, i, r60 = v1_trigger(S[(s, day)], lvl, sg); row.update(status=st, r60=r60)
        if st != "fired": rows.append(row); continue
        prevs = [S[(s, dd)] for dd in sorted(dd for (ss, dd) in S if ss == s and dd < day) if len(S[(s, dd)]["c"]) >= 300]
        f = features(S[(s, day)], prevs, spyS, closes_before, lvl, sg, i, day, earn.get(s, np.nan))
        row.update(trig_m=int(S[(s, day)]["m"][i]), **(f or {}), scored=int(f is not None)); rows.append(row)
    R = pd.DataFrame(rows)
    if R.get("scored", pd.Series(dtype=float)).fillna(0).astype(bool).any():
        probs = predict(R[R.scored == 1], frozen); R = R.merge(probs, on=["d","s","dir"], how="left")
    if write:
        if spyS is not None and len(spyS["c"]):
            allc = pd.concat([allc, pd.DataFrame({"d":[day], "close":[float(spyS["c"][-1])]})]).drop_duplicates("d").sort_values("d")
            allc[~allc.d.isin(pd.read_csv(os.path.join(frozen, "spy_closes_seed.csv")).d.map(lambda x: pd.to_datetime(x).date()))].to_csv(os.path.join(out, "ml_spy_closes.csv"), index=False)
        path = os.path.join(out, "ml_scores.csv"); cols = ["d","s","dir","lvl","status","trig_m","r60","scored","p_tabpfn24","p_gbm14","pick_tabpfn24","pick_gbm14"] + F24
        old = pd.read_csv(path) if os.path.exists(path) else pd.DataFrame(columns=cols)
        pd.concat([old[old.d != day.isoformat()], R.reindex(columns=cols)]).to_csv(path, index=False)
    return R

def predict(X, frozen):
    ctx = pd.read_parquet(os.path.join(frozen, "ml_context_v1.parquet"))
    from sklearn.ensemble import GradientBoostingClassifier
    gbm = GradientBoostingClassifier(n_estimators=150, max_depth=2, learning_rate=0.05, subsample=0.8, random_state=0).fit(ctx[F14].values, ctx.y.values)
    p_g = gbm.predict_proba(X[F14].values.astype(float))[:,1]
    try:
        tok = os.path.expanduser("~/.config/tabpfn/token")
        if os.path.exists(tok): os.environ.setdefault("TABPFN_TOKEN", open(tok).read().strip())
        from tabpfn import TabPFNClassifier
        p_t = TabPFNClassifier(device="cpu", random_state=0).fit(ctx[F24].values.astype(float), ctx.y.values).predict_proba(X[F24].values.astype(float))[:,1]
    except Exception as e:
        print(f"WARN tabpfn unavailable: {e}", file=sys.stderr); p_t = np.full(len(X), np.nan)
    return pd.DataFrame({"d": X.d.values, "s": X.s.values, "dir": X.dir.values, "p_tabpfn24": p_t, "p_gbm14": p_g,
                         "pick_tabpfn24": (p_t > PICK).astype(float), "pick_gbm14": (p_g > PICK).astype(float)})

def build_context(frozen, bars_gz, legs_psv, earn_csv):
    """Rebuild the frozen context from the research bars (extended hours) + the 647 levels, with THIS file's features()."""
    rows = [json.loads(l) for l in gzip.open(bars_gz, "rt")]; b = pd.DataFrame(rows)
    b["t"] = pd.to_datetime(b.t, utc=True).dt.tz_convert("America/New_York"); b["d"] = b.t.dt.date; b["m"] = b.t.dt.hour*60 + b.t.dt.minute - 570
    S = sessions(b[["s","d","m","o","h","l","c","v"]]); [S[k].__setitem__("etf", k[0] in ("SPY","QQQ")) for k in S]
    legs = pd.read_csv(legs_psv, sep="|", header=None, names=["d","wf","ts","s","dir","lvl","mode","mult"]); legs["d"] = pd.to_datetime(legs.d).dt.date
    legs = legs.drop_duplicates(["d","s","dir","lvl"]); legs = legs[legs.d != date(2026,7,1)]
    earn = pd.read_csv(earn_csv) if earn_csv and os.path.exists(earn_csv) else None
    if earn is not None: earn["d"] = pd.to_datetime(earn.d).dt.date; eflag = {(r.s, r.d): float(r.earnings_day) for r in earn.itertuples()}
    spy_days = sorted(d for (s, d) in S if s == "SPY"); spy_close = {d: float(S[("SPY", d)]["c"][-1]) for d in spy_days if len(S[("SPY", d)]["c"])}
    out = []
    for L in legs.itertuples():
        if (L.s, L.d) not in S: continue
        sg = 1 if L.dir == "ABOVE" else -1; st, i, r60 = v1_trigger(S[(L.s, L.d)], L.lvl, sg)
        if st != "fired" or r60 is None or np.isnan(r60): continue
        prevs = [S[(L.s, dd)] for dd in sorted(dd for (ss, dd) in S if ss == L.s and dd < L.d) if len(S[(L.s, dd)]["c"]) >= 300]
        f = features(S[(L.s, L.d)], prevs, S.get(("SPY", L.d)), [spy_close[d] for d in spy_days if d < L.d and d in spy_close], L.lvl, sg, i, L.d,
                     eflag.get((L.s, L.d), 0.0) if earn is not None else np.nan)
        if f is None: continue
        out.append(dict(s=L.s, d=L.d.isoformat(), dir=L.dir, level=L.lvl, trig_m=int(S[(L.s, L.d)]["m"][i]), r60=r60, y=int(r60 > 0), **f))
    C = pd.DataFrame(out); C.to_parquet(os.path.join(frozen, "ml_context_v1.parquet"), index=False)
    h = hashlib.sha256(open(os.path.join(frozen, "ml_context_v1.parquet"), "rb").read()).hexdigest()
    json.dump({"context_rows": len(C), "context_last_date": C.d.max(), "features_24": F24, "features_14": F14, "sha256_context": h,
               "built": datetime.now(ET).isoformat(timespec="seconds")}, open(os.path.join(frozen, "FROZEN.json"), "w"), indent=1)
    print(f"context: {len(C)} legs through {C.d.max()}, base rate {C.y.mean():.3f}, sha256 {h[:16]}")

def analyze(out, today):
    if today < ANALYSIS_DATE: sys.exit(f"refusing: the single pre-registered look is on {ANALYSIS_DATE}")
    R = pd.read_csv(os.path.join(out, "ml_scores.csv")); R = R[(R.scored == 1) & R.r60.notna()]
    from sklearn.metrics import roc_auc_score
    rng = np.random.default_rng(20261024)
    for mcol, pcol in (("p_tabpfn24","pick_tabpfn24"), ("p_gbm14","pick_gbm14")):
        x = R.dropna(subset=[mcol]); y = (x.r60 > 0).astype(int).values
        auc = roc_auc_score(y, x[mcol]); null = [roc_auc_score(rng.permutation(y), x[mcol]) for _ in range(1000)]
        picks = x[x[pcol] == 1]; g = picks.groupby("d").r60.agg(["sum","count"])
        ci = np.percentile([g["sum"].values[i].sum()/g["count"].values[i].sum() for i in rng.integers(0, len(g), (2000, len(g)))], [2.5, 97.5])*1e4 if len(g) >= 3 else (np.nan, np.nan)
        print(f"{mcol}: n={len(x)} AUC={auc:.3f} null95={np.percentile(null,95):.3f} | picks n={len(picks)} mean r60={picks.r60.mean()*1e4:+.1f} bps CI[{ci[0]:+.0f},{ci[1]:+.0f}] | non-picks {x[x[pcol]==0].r60.mean()*1e4:+.1f}")
    print("Apply Holm across M1/M2 and the PASS/KILL rule from the PREREG; report beside the independent re-derivation.")

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--out", required=True); ap.add_argument("--frozen", required=True)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("score").add_argument("--day", default=datetime.now(ET).date().isoformat())
    b = sub.add_parser("build-context"); b.add_argument("--bars", required=True); b.add_argument("--legs", required=True); b.add_argument("--earn", default=None)
    sub.add_parser("analyze")
    a = ap.parse_args(); out, frozen = os.path.expanduser(a.out), os.path.expanduser(a.frozen)
    if a.cmd == "score":
        R = score_day(date.fromisoformat(a.day), out, frozen); print(f"{a.day}: {len(R)} legs, {int(R.get('scored', pd.Series(dtype=float)).fillna(0).sum())} scored")
    elif a.cmd == "build-context": build_context(frozen, a.bars, a.legs, a.earn)
    else: analyze(out, datetime.now(ET).date())

if __name__ == "__main__": main()
