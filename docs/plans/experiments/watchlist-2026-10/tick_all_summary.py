import pandas as pd, numpy as np
rng = np.random.default_rng(31)
A = pd.concat([pd.read_parquet("tick_replay_touch.parquet"), pd.read_parquet("tick_replay_single.parquet")])
V = [("trail",None,None,X) for X in (3,5,10,15,20,30)] + [("fixed",T_,S,None) for T_ in (20,50,100,200) for S in (5,10,20)] + [("combo",100,None,10),("combo",200,None,10)]
lab = {i: f"{k} T={T_} S={S} X={X}".replace(" T=None","").replace(" S=None","").replace(" X=None","") for i,(k,T_,S,X) in enumerate(V)}
A["cls"] = np.where(A.s.isin(["SPY","QQQ"]), "ETF", "single"); A["cost"] = np.where(A.cls=="ETF", 0.3, 1.5)   # stock round-trip bps (spread+fees)
def ci(x):
    g = x.groupby("d").bps.agg(["sum","count"]); i = rng.integers(0,len(g),(3000,len(g)))
    b = g["sum"].values[i].sum(1)/g["count"].values[i].sum(1); return f"[{np.percentile(b,2.5):+.1f},{np.percentile(b,97.5):+.1f}]"
pd.set_option("display.width", 250); pd.set_option("display.max_rows", 200)
S = A.groupby(["v","cls","half"]).apply(lambda x: pd.Series(dict(n=len(x), win=round((x.bps>0).mean(),2), gross=round(x.bps.mean(),2), ci=ci(x)))).unstack(["cls","half"]); S.index = S.index.map(lab)
print("UNCONDITIONED TICK REPLAY, ALL WATCHLIST SYMBOLS — gross bps/trade (entry at first print through the level; stop filled at the triggering print)")
print(S[["n","gross","ci"]].to_string())
P = A.groupby(["v","half"]).bps.mean().unstack("half"); both = ((P["FIT"]>0)&(P["TEST"]>0)).sum(); print(f"\nvariants gross>0 in BOTH halves (pooled all symbols): {both} of {len(P)}")
Pc = A.groupby(["v","cls","half"]).bps.mean().unstack(["cls","half"])
for c in ("single","ETF"):
    if (c,"FIT") in Pc.columns and (c,"TEST") in Pc.columns: print(f"{c} variants >0 both halves:", int(((Pc[(c,"FIT")]>0)&(Pc[(c,"TEST")]>0)).sum()), "of", len(Pc))
X_ = A[A.half=="TEST"].assign(v2=lambda t: t.v.astype(str)+"|"+t.cls); P2 = X_.pivot_table(index="d", columns="v2", values="bps", aggfunc="sum").fillna(0); N2 = X_.pivot_table(index="d", columns="v2", values="bps", aggfunc="count").fillna(0)
mu = (P2.sum()/N2.sum()).values; bs = np.array([P2.values[i].sum(0)/np.maximum(N2.values[i].sum(0),1) for i in [rng.integers(0,len(P2),len(P2)) for _ in range(2000)]]); se = bs.std(0); t = mu/se; tmax = ((bs-mu)/se).max(1)
print("\nTEST studentized max-t over all variant×class cells, top 5:")
for j in np.argsort(-t)[:5]: v,c = P2.columns[j].split("|"); print(f"  {lab[int(v)]:16s} {c:7s} gross={mu[j]:+.2f} t={t[j]:+.2f} p_adj={(tmax>=t[j]).mean():.3f}")
print("\nper-symbol (pooled over the 5-/10-bps trail and T20/S10, both halves): n, gross bps, win")
sub = A[A.v.isin([1,2,7])]; ps = sub.groupby("s").bps.agg(n="size", gross="mean", win=lambda x:(x>0).mean()).round(2).sort_values("gross", ascending=False); print(ps.to_string())
