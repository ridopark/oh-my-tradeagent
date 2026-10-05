"""T4 (PRE-REGISTERED 2026-10-05, before results): IV-richness gate on the 14:00 S2 condor (hold-to-settle).
richness(d) = ATM straddle at 14:00 / (0.7979 * sigma_1min(09:30-13:59) * sqrt(120) * spot).
Gate: trade only when richness >= trailing-60-day median (no lookahead). ONE gate, no grid.
Report gated / ungated / anti-gated, base+stress, halves. SPX in-sample; SPY+QQQ = OOS; IWM exploratory.
PASS: gated stress-mean > 0 on SPY AND QQQ, gated >= ungated on both, pooled gated t>=2, both halves >0."""
import sys, os, glob, pandas as pd, numpy as np
S=sys.argv[1]; os.environ['CACHE']=S; sys.path.insert(0,S); import engine as E
R=pd.read_parquet(f'{S}/sell_afternoon.parquet'); R=R[(R.hm=='14:00')&(R.struct=='S2 iron condor')&(R.exit=='settle')].dropna(subset=['ret'])
def richness_etf(U):
    raw=pd.read_parquet(f'{S}/etfopt/{U}_raw_1m.parquet'); raw['d']=raw.t.dt.date.astype(str)
    out={}
    for d,g in raw.groupby('d'):
        g=g.set_index('t'); t=pd.Timestamp(f'{d} 14:00',tz='America/New_York')
        try: o=pd.read_parquet(f'{S}/etfopt/{U}/{d}.parquet')
        except Exception: continue
        m=g.loc[:t]
        if len(m)<200 or t not in g.index: continue
        spot=g.at[t,'o']; sig=np.log(m.c).diff().dropna().std()
        atm=round(spot); piv=o[(o.k==atm)&(o.t<=t)]
        st=0
        for cp in 'CP':
            x=piv[piv.cp==cp].sort_values('t')
            if x.empty or (t-x.t.iloc[-1]).total_seconds()>900: st=None; break
            st+=x.c.iloc[-1]
        if st is None or sig==0: continue
        out[d]=st/(0.7979*sig*np.sqrt(120)*spot)
    return pd.Series(out)
def richness_spx():
    spy=pd.read_parquet(f'{S}/spy_1m.parquet'); spy=spy[spy.t>='2024-03-01']; spy['d']=spy.t.dt.date.astype(str)
    out={}
    for d in sorted(set(R[R.U=='SPX'].d)):
        try: day=E.Day(d)
        except Exception: continue
        t=pd.Timestamp(f'{d} 14:00',tz='America/New_York')
        try: iv,st,atm=day.iv(t)
        except Exception: continue
        m=spy[(spy.d==d)&(spy.t<=t)]
        if len(m)<200 or not np.isfinite(st): continue
        sig=np.log(m.c).diff().dropna().std()
        out[d]=st/(0.7979*sig*np.sqrt(120)*day.spx.at[t])
    return pd.Series(out)
tt=lambda x: x.mean()/x.std()*np.sqrt(len(x))
res=[]
for U in ('SPX','SPY','QQQ','IWM'):
    rich=(richness_spx() if U=='SPX' else richness_etf(U)).sort_index()
    med=rich.rolling(61).apply(lambda w:np.median(w[:-1]),raw=True)  # trailing 60, excl today
    gate=(rich>=med).dropna()
    X=R[R.U==U].merge(gate.rename('g'),left_on='d',right_index=True)
    for f in ('base','stress'):
        for lab,sub in (('ungated',X[X.fill==f]),('GATED',X[(X.fill==f)&X.g]),('anti',X[(X.fill==f)&~X.g])):
            x=sub.ret
            if len(x)<30: continue
            res.append(dict(U=U,fill=f,gate=lab,n=len(x),mean=x.mean()*100,t=tt(x),
                H1=sub[sub.d<'2025-07-01'].ret.mean()*100,H2=sub[sub.d>='2025-07-01'].ret.mean()*100))
T=pd.DataFrame(res); pd.set_option('display.width',220)
print(T.round(2).to_string(index=False))
g=T[(T.gate=='GATED')&(T.fill=='stress')&T.U.isin(['SPY','QQQ'])]
u=T[(T.gate=='ungated')&(T.fill=='stress')&T.U.isin(['SPY','QQQ'])]
ok=all(g.mean_>0 for g in []) # placeholder
print('\nPASS check: gated-stress SPY/QQQ means', dict(zip(g.U,g['mean'].round(2))), 'vs ungated', dict(zip(u.U,u['mean'].round(2))))
