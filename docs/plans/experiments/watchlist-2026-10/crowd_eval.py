import pandas as pd, numpy as np, glob, datetime as dt, json, gzip
from math import log, sqrt, exp, erf
rng = np.random.default_rng(70)
N = lambda x: 0.5*(1+erf(x/sqrt(2)))
def bs(S,K,T,v,cp,r=.04):
    if T<=0 or v<=0: return max(0,(S-K) if cp=="C" else (K-S))
    d1=(log(S/K)+(r+v*v/2)*T)/(v*sqrt(T)); d2=d1-v*sqrt(T)
    return S*N(d1)-K*exp(-r*T)*N(d2) if cp=="C" else K*exp(-r*T)*N(-d2)-S*N(-d1)
def iv(p,S,K,T,cp):
    lo,hi=1e-3,5.0
    if p<=bs(S,K,T,lo,cp) or p>=bs(S,K,T,hi,cp): return np.nan
    for _ in range(60):
        m=(lo+hi)/2
        if bs(S,K,T,m,cp)>p: hi=m
        else: lo=m
    return (lo+hi)/2
def delta(S,K,T,v,cp,r=.04):
    d1=(log(S/K)+(r+v*v/2)*T)/(v*sqrt(T)); return N(d1) if cp=="C" else N(d1)-1
C = pd.read_csv("crowd/contracts.csv", parse_dates=["d","exp"]); C["d"]=C.d.dt.date; C["exp"]=C.exp.dt.date
T0 = pd.read_csv("crowd/_triggers.csv"); T0=T0[T0.trigger_time_et.notna()]
T0["d"]=pd.to_datetime(T0.et_date).dt.date; T0["t0"]=pd.to_datetime(T0.trigger_time_et).dt.tz_localize("America/New_York")
tr = []
for f in glob.glob("crowd/trades_*.jsonl.gz"):
    x = pd.read_json(f, lines=True, compression="gzip")
    if len(x): tr.append(x)
Tr = pd.concat(tr); Tr["t"]=pd.to_datetime(Tr.t, utc=True).dt.tz_convert("America/New_York"); Tr["mi"]=Tr.t.dt.floor("min")
bars = pd.read_parquet("rth_1m.parquet"); spot = bars.set_index(["s","t"]).c
G = {s:g.sort_values("t") for s,g in Tr.groupby("sym")}
out=[]
for L in C.merge(T0[["d","ticker","right","t0"]], on=["d","ticker","right"]).groupby(["d","ticker","right","exp"]):
    (d,tk,cp,e), x = L
    t0 = x.t0.iloc[0]; roles = dict(zip(x.role, x.occ)); k = x.strike.iloc[0]
    if roles.get("listed") not in G: continue
    def win(sym,a,b):
        g=G.get(sym); 
        if g is None: return g
        return g[(g.t>=t0+pd.Timedelta(minutes=a))&(g.t<t0+pd.Timedelta(minutes=b))]
    def vol(sym,a,b):
        w=win(sym,a,b); return 0 if w is None else w.s.sum()
    ctrl=[roles[r] for r in ("near","far") if r in roles and roles[r] in G]
    if not ctrl: continue
    Lpre,Lpost=vol(roles["listed"],-30,-5),vol(roles["listed"],0,10)
    Cpre,Cpost=sum(vol(c,-30,-5) for c in ctrl),sum(vol(c,0,10) for c in ctrl)
    h1=log((Lpost+1)/(Lpre+1))-log((Cpost+1)/(Cpre+1))
    Tyr=lambda t: max(((pd.Timestamp(e).tz_localize("America/New_York")+pd.Timedelta(hours=16))-t).total_seconds()/(365*86400),1e-6)
    def med_iv(sym,a,b,K):
        w=win(sym,a,b)
        if w is None or len(w)==0: return np.nan
        vals=[]
        for r in w.itertuples():
            S=spot.get((tk,r.mi))
            if S is not None: vals.append(iv(r.p,S,K,Tyr(r.t),cp))
        vals=[v for v in vals if np.isfinite(v)]; return np.median(vals) if vals else np.nan
    Ks={roles["listed"]:k}; 
    for r_ in ("near","far"):
        if r_ in roles: Ks[roles[r_]]=float(roles[r_][-8:])/1000
    def div(sym,a1,b1,a2,b2): return med_iv(sym,a2,b2,Ks[sym])-med_iv(sym,a1,b1,Ks[sym])
    h2=div(roles["listed"],-10,0,0,5)-np.nanmean([div(c,-10,0,0,5) for c in ctrl])
    h2d=div(roles["listed"],-10,0,25,35)-np.nanmean([div(c,-10,0,25,35) for c in ctrl])
    # H3: short listed at first trade >= t0+1m, exit first trade >= t0+10/30m; spread proxy = Roll on prior 30m, fallback 3%
    g=G[roles["listed"]]; pre=g[(g.t>=t0-pd.Timedelta(minutes=30))&(g.t<t0)].p.values
    dp=np.diff(pre); cov=np.cov(dp[1:],dp[:-1])[0,1] if len(dp)>3 else np.nan
    hs=(sqrt(-cov)/np.median(pre)) if np.isfinite(cov) and cov<0 else .03
    ent=g[g.t>=t0+pd.Timedelta(minutes=1)]
    res={}
    if len(ent):
        p0=ent.p.iloc[0]; t1=ent.t.iloc[0]; S0=spot.get((tk,ent.mi.iloc[0])); v0=iv(p0,S0,k,Tyr(t1),cp) if S0 else np.nan
        for H in (10,30):
            ex=g[g.t>=t1+pd.Timedelta(minutes=H)]
            if len(ex)==0 or not np.isfinite(v0): continue
            p1=ex.p.iloc[0]; S1=spot.get((tk,ex.mi.iloc[0]))
            if S1 is None: continue
            D=delta(S0,k,Tyr(t1),v0,cp)
            pnl=(p0*(1-hs))-(p1*(1+hs))+D*(S1-S0)-abs(D)*S0*1e-4*2
            res[H]=pnl/p0
    out.append(dict(d=d,ticker=tk,right=cp,exp=e,dte=(e-d).days,Lpre=Lpre,Lpost=Lpost,Cpre=Cpre,Cpost=Cpost,h1=h1,h2=h2,h2_decay=h2d,hs=hs,h3_10=res.get(10),h3_30=res.get(30)))
R=pd.DataFrame(out); R["half"]=np.where(R.d>=dt.date(2026,10,8),"TEST","FIT"); R.to_parquet("crowd/h123.parquet")
def ci(x,col):
    x=x.dropna(subset=[col]); g=x.groupby("d")[col].agg(["sum","count"])
    if len(g)<3: return (np.nan,np.nan)
    i=rng.integers(0,len(g),(2000,len(g))); return tuple(np.percentile(g["sum"].values[i].sum(1)/g["count"].values[i].sum(1),[2.5,97.5]))
print("legs x expiries:",len(R), R.groupby("half").size().to_dict())
for col,lab in [("h1","H1 vol log-ratio DiD"),("h2","H2 IV pop (vol pts) 0-5m"),("h2_decay","H2 IV at 25-35m"),("h3_10","H3 short+hedge 10m, ret/premium"),("h3_30","H3 short+hedge 30m")]:
    for h,x in R.groupby("half"):
        lo,hi=ci(x,col); print(f"{lab:32s} {h}: n={x[col].notna().sum():3d} mean={x[col].mean():+.4f} CI[{lo:+.4f},{hi:+.4f}]")
print("\nby dte bucket (H1, H2):"); print(R.assign(b=np.where(R.dte==0,"0DTE",np.where(R.dte<=4,"1-4d","5d+"))).groupby("b")[["h1","h2","h3_10"]].agg(["count","mean"]).round(3))
