"""H9.1 author consensus, per PREREG9. Run: python3 h91.py [--include-manual]"""
import sys, pandas as pd, numpy as np
from pathlib import Path
H = Path(__file__).parent; W = H.parent.parent
INCL_MANUAL = "--include-manual" in sys.argv
rng = np.random.default_rng(91)

cols = ["signal_id", "author", "action", "ticker", "right", "strike", "expiry", "posted_at", "occurred_at"]
sig = pd.read_csv(H / "bto_signals.psv", sep="|", names=cols, dtype=str)
sig = sig[sig.action == "BTO"].drop_duplicates("signal_id")
sig = sig[sig.author != "TradingTheTrend"]                      # watchlist author: not independent
manual = sig.signal_id.str.startswith("manual") | sig.author.str.startswith("tenant:") | (sig.author == "ridopark")
if not INCL_MANUAL: sig = sig[~manual]                          # amendment A1: operator hand entries are not an author
sig["t"] = pd.to_datetime(sig.posted_at, utc=True, format="ISO8601")

e = pd.read_parquet(W / "events.parquet")
e = e[(e.fired == 1) & (e.gap == 0)].copy()
e["trig"] = (pd.to_datetime(e.d.astype(str)) + pd.Timedelta(hours=9, minutes=30)
             + pd.to_timedelta(e.fire_min, unit="m")).dt.tz_localize("America/New_York").dt.tz_convert("UTC")

by_tk = {k: g for k, g in sig.groupby("ticker")}
def tag(r):
    g = by_tk.get(r.s)
    if g is None: return "SILENT", ""
    g = g[(g.t >= r.trig - pd.Timedelta(hours=24)) & (g.t <= r.trig)]
    if g.empty: return "SILENT", ""
    want = "C" if r.dir == "ABOVE" else "P"
    same, opp = (g.right == want).any(), (g.right != want).any()
    t = "BOTH" if same and opp else ("AGREE" if same else "DISAGREE")
    return t, ";".join(sorted(set(g.author)))
e[["tag", "authors"]] = e.apply(lambda r: pd.Series(tag(r)), axis=1)

def boot_diff(df, a, b, col, B=4000):
    """day-clustered bootstrap of mean(col|tag a) - mean(col|tag b); pooled within each resample."""
    df = df.dropna(subset=[col])
    days = df.d.unique()
    agg = {t: df[df.tag == t].groupby("d")[col].agg(["sum", "count"]).reindex(days, fill_value=0) for t in (a, b)}
    pt = df[df.tag == a][col].mean() - df[df.tag == b][col].mean()
    if (agg[a]["count"] > 0).sum() < 1 or (agg[b]["count"] > 0).sum() < 1: return pt, np.nan, np.nan, int((agg[a]["count"] > 0).sum())
    idx = rng.integers(0, len(days), (B, len(days)))
    m = {}
    for t in (a, b):
        s, c = agg[t]["sum"].values[idx].sum(1), agg[t]["count"].values[idx].sum(1)
        with np.errstate(invalid="ignore", divide="ignore"): m[t] = s / c
    d = m[a] - m[b]; d = d[np.isfinite(d)]
    return pt, *np.percentile(d, [2.5, 97.5]), int((agg[a]["count"] > 0).sum())

out = []
P = lambda *x: out.append(" ".join(str(v) for v in x))
P(f"independent BTO signals used: {len(sig)}  (include_manual={INCL_MANUAL})")
P(sig.groupby("author").size().to_string())
for kind in ["real", "placebo"]:
    x = e[e.kind == kind]
    P(f"\n=== {kind}: tag counts per half ===")
    P(pd.crosstab(x.half, x.tag).to_string())
    P(f"\n=== {kind}: mean bps (n) per tag per half ===")
    t = x.groupby(["half", "tag"]).agg(n=("r60", "size"), n_days=("d", "nunique"),
                                        r60=("r60", "mean"), rclose=("rclose", "mean"))
    t[["r60", "rclose"]] = (t[["r60", "rclose"]] * 1e4).round(1); P(t.to_string())
    P(f"\n=== {kind}: contrasts, bps, day-clustered 95% CI ===")
    for half in ["FIT", "TEST"]:
        xh = x[x.half == half]
        for col in ["r60", "rclose"]:
            for b in ["SILENT", "DISAGREE"]:
                pt, lo, hi, nd = boot_diff(xh, "AGREE", b, col)
                P(f"{half} {col:6s} AGREE-{b:8s} {pt*1e4:+7.1f} [{lo*1e4:+7.1f},{hi*1e4:+7.1f}]  AGREE-days={nd}")
# placebo comparison: AGREE real vs AGREE placebo
P("\n=== placebo comparison: AGREE(real) - AGREE(placebo), and (A-S real) - (A-S placebo) point, bps ===")
for half in ["FIT", "TEST"]:
    for col in ["r60", "rclose"]:
        r = e[(e.kind == "real") & (e.half == half)]; p = e[(e.kind == "placebo") & (e.half == half)]
        ar, ap = r[r.tag == "AGREE"][col].mean(), p[p.tag == "AGREE"][col].mean()
        dr = ar - r[r.tag == "SILENT"][col].mean(); dp = ap - p[p.tag == "SILENT"][col].mean()
        P(f"{half} {col:6s} AGREE real {ar*1e4:+.1f} vs placebo {ap*1e4:+.1f} -> {(ar-ap)*1e4:+.1f};  DiD {(dr-dp)*1e4:+.1f}")
# pass rule
P("\n=== PASS RULE (r60) ===")
ok = True
for half in ["FIT", "TEST"]:
    xh = e[(e.kind == "real") & (e.half == half)]
    pt, lo, hi, _ = boot_diff(xh, "AGREE", "SILENT", "r60")
    pd_, *_ = boot_diff(xh, "AGREE", "DISAGREE", "r60")
    c1 = np.isfinite(lo) and pt > 0 and lo > 0; c2 = np.isfinite(pd_) and pd_ > 0
    P(f"{half}: A-S>0 & CI excl 0: {c1};  A-D>0: {c2} ({'n/a, no DISAGREE legs' if not np.isfinite(pd_) else f'{pd_*1e4:+.1f}'})")
    ok &= c1 and c2
P("H9.1:", "PASS" if ok else "FAIL")
P("\n=== AGREE/DISAGREE/BOTH real legs ===")
r = e[(e.kind == "real") & (e.tag != "SILENT")]
P(r[["half", "d", "s", "dir", "fire_min", "tag", "authors", "r60", "rclose"]].sort_values("d").to_string(index=False))
P("\n=== per-author AGREE legs (real) ===")
a = r[r.tag == "AGREE"].assign(author=lambda z: z.authors.str.split(";")).explode("author")
P(a.groupby(["author", "half"]).agg(n=("r60", "size"), r60_bps=("r60", lambda v: round(v.mean()*1e4, 1)),
                                     rclose_bps=("rclose", lambda v: round(v.mean()*1e4, 1))).to_string())
txt = "\n".join(out); print(txt)
(H / ("out_incl_manual.txt" if INCL_MANUAL else "out.txt")).write_text(txt)
