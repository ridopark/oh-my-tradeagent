"""Graph-surfaced composition queries U2/U3/U4 (predictions pre-registered in graph.json).
U2: does the richness gate help at 13:00 and 15:00 too (monotone with the M1 curve)?
U3: RISK CHECK - do gated FOMC afternoons underperform gated non-FOMC? (vol rich for a reason)
U4: is EV monotone in richness terciles (continuous mechanism, terciles only, no threshold search)?"""
import sys, os, glob, pandas as pd, numpy as np
S=sys.argv[1]; os.environ['CACHE']=S; sys.path.insert(0,S); import engine as E
R=pd.read_parquet(f'{S}/sell_afternoon.parquet'); R=R[(R.struct=='S2 iron condor')&(R.exit=='settle')&(R.fill=='stress')].dropna(subset=['ret'])
# rebuild richness per U per day at EACH entry hour (reuse t4 logic, generalized)
def richness(U,hm):
    out={}
    if U=='SPX':
        spy=pd.read_parquet(f'{S}/spy_1m.parquet'); spy=spy[spy.t>='2024-03-01']; spy['d']=spy.t.dt.date.astype(str)
        for d in sorted(set(R[R.U=='SPX'].d)):
            try: day=E.Day(d); t=pd.Timestamp(f'{d} {hm}',tz='America/New_York'); iv,st,atm=day.iv(t)
            except Exception: continue
            m=spy[(spy.d==d)&(spy.t<=t)]
            if len(m)<150 or not np.isfinite(st): continue
            sig=np.log(m.c).diff().dropna().std(); rem=(pd.Timestamp(f'{d} 16:00',tz='America/New_York')-t).total_seconds()/60
            if sig>0: out[d]=st/(0.7979*sig*np.sqrt(rem)*day.spx.at[t])
        return pd.Series(out)
    raw=pd.read_parquet(f'{S}/etfopt/{U}_raw_1m.parquet'); raw['d']=raw.t.dt.date.astype(str)
    for d,g in raw.groupby('d'):
        g=g.set_index('t'); t=pd.Timestamp(f'{d} {hm}',tz='America/New_York')
        try: o=pd.read_parquet(f'{S}/etfopt/{U}/{d}.parquet')
        except Exception: continue
        m=g.loc[:t]
        if len(m)<150 or t not in g.index: continue
        spot=g.at[t,'o']; sig=np.log(m.c).diff().dropna().std(); atm=round(spot); st=0
        for cp in 'CP':
            x=o[(o.k==atm)&(o.cp==cp)&(o.t<=t)].sort_values('t')
            if x.empty or (t-x.t.iloc[-1]).total_seconds()>900: st=None; break
            st+=x.c.iloc[-1]
        rem=(pd.Timestamp(f'{d} 16:00',tz='America/New_York')-t).total_seconds()/60
        if st and sig>0: out[d]=st/(0.7979*sig*np.sqrt(rem)*spot)
    return pd.Series(out)
tt=lambda x: x.mean()/x.std()*np.sqrt(len(x))
fomc=set(open(f'{S}/fomc_dates.txt').read().split())
print('== U2: gate at each entry hour (stress, hold-to-settle, % of risk/day) — prediction: gated>ungated at every hour')
u3rows=[]
for hm in ('13:00','14:00','15:00'):
    for U in ('SPY','QQQ','SPX'):
        rich=richness(U,hm).sort_index()
        med=rich.rolling(61).apply(lambda w:np.median(w[:-1]),raw=True)
        gate=(rich>=med).dropna()
        X=R[(R.U==U)&(R.hm==hm)].merge(gate.rename('g'),left_on='d',right_index=True)
        G,Ug=X[X.g],X[~X.g]
        print(f'{hm} {U}: gated n={len(G)} {G.ret.mean()*100:+.1f} (t {tt(G.ret):.1f}) | ungated-rest {Ug.ret.mean()*100:+.1f}')
        if hm=='14:00':
            X['fomc']=X.d.isin(fomc); u3rows.append(X.assign(U=U))
print('\n== U3: RISK CHECK gated FOMC vs gated non-FOMC (14:00) — prediction: FOMC underperforms')
A=pd.concat(u3rows); G=A[A.g]
f,nf=G[G.fomc],G[~G.fomc]
print(f'gated FOMC: n={len(f)} mean {f.ret.mean()*100:+.1f}% win {(f.ret>0).mean()*100:.0f}% worst {f.ret.min()*100:.0f}%')
print(f'gated non-FOMC: n={len(nf)} mean {nf.ret.mean()*100:+.1f}% win {(nf.ret>0).mean()*100:.0f}%')
print('\n== U4: EV by richness tercile (trailing ranks, 14:00, pooled SPY+QQQ) — prediction: monotone')
rows=[]
for U in ('SPY','QQQ'):
    rich=richness(U,'14:00').sort_index()
    rank=rich.rolling(61).apply(lambda w:(w[:-1]<w[-1]).mean(),raw=True)
    X=R[(R.U==U)&(R.hm=='14:00')].merge(rank.rename('rk'),left_on='d',right_index=True); rows.append(X)
A=pd.concat(rows).dropna(subset=['rk'])
A['terc']=pd.cut(A.rk,[-.01,1/3,2/3,1.01],labels=['cheap','mid','rich'])
print(A.groupby('terc').ret.agg(n='size',mean=lambda x:x.mean()*100,t=tt).round(2).to_string())
