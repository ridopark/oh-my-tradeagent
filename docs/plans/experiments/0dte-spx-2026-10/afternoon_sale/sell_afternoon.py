"""STEP 1 — pre-registered afternoon 0DTE premium sale, held to the close (2026-10-04, written before results).
  S1 iron fly : short ATM C+P at K=nearest strike; wings at K +- W, W = straddle price rounded to strike grid (>= 1 grid)
  S2 iron condor: short C = first strike >= spot*1.0015, short P = last strike <= spot*0.9985;
                  long C = first strike >= spot*1.006, long P = last strike <= spot*0.994 (pushed 1 grid out if equal)
  Entry 14:00 (primary); 13:00, 15:00 sensitivity. Entry px = bar open at t, else last close within 5 min; else skip day.
  Exits: 'settle' = intrinsic at 15:59 close (SPX is cash-settled; for SPY/QQQ/IWM this ignores exercise mechanics)
         'close1555' = buy back at 15:55 marks (last print <= 15 min old; missing long wing = 0, missing short = skip)
  Costs per leg per side: ETF h=$0.01 base / $0.02 stress + $0.0005 fees; SPX engine half() + $0.011.
  Unit: P&L / max risk (max wing width - credit), one obs per day.
  PASS: at 14:00, for a structure: SPY AND QQQ mean>0 under stress+close1555; SPY+QQQ day-averaged t>=2;
        both halves (split 2025-07-01) >0. SPX is IN-SAMPLE (14:00 was chosen on SPX); IWM exploratory.
"""
import os, sys, glob, pandas as pd, numpy as np
S=sys.argv[1]; os.environ['CACHE']=S; sys.path.insert(0,S); import engine as E
from concurrent.futures import ProcessPoolExecutor
TIMES=['13:00','14:00','15:00']
class ETFDay:
    def __init__(s,U,d):
        o=pd.read_parquet(f'{S}/etfopt/{U}/{d}.parquet')
        idx=pd.date_range(f'{d} 12:30',f'{d} 15:59',freq='1min',tz='America/New_York')
        s.c=o.pivot_table(index='t',columns=['cp','k'],values='c').reindex(idx)
        s.op=o.pivot_table(index='t',columns=['cp','k'],values='o').reindex(idx)
        sp=RAW[U][d]; s.spot=sp.reindex(idx).o.ffill(); s.settle=sp.c.iloc[-1]; s.grid=1.0
        s.h=lambda p,st: 0.02 if st else 0.01; s.fee=0.0005
    def px(s,cp,k,t):
        if (cp,k) not in s.c.columns: return np.nan
        v=s.op.at[t,(cp,k)]
        if np.isnan(v): v=s.c[(cp,k)].loc[:t].ffill(limit=5).iloc[-1]
        return v
    def mark(s,cp,k,t):
        if (cp,k) not in s.c.columns: return np.nan
        return s.c[(cp,k)].loc[:t].ffill(limit=15).iloc[-1]
class SPXDay:
    def __init__(s,d):
        s.D=E.Day(d); s.spot=s.D.spx; s.settle=s.D.settle; s.grid=5.0
        s.h=lambda p,st: E.half(p,st); s.fee=E.FEE
    def px(s,cp,k,t): return s.D.px(cp,int(k),t)
    def mark(s,cp,k,t):
        m=s.D.mark; return m.at[t,(cp,int(k))] if (cp,int(k)) in m.columns else np.nan
RAW={}
def load_raw(U):
    b=pd.read_parquet(f'{S}/etfopt/{U}_raw_1m.parquet'); b['d']=b.t.dt.date.astype(str)
    return {d:g.set_index('t') for d,g in b.groupby('d')}
def legs_for(day,t,struct):
    g=day.grid; sp=day.spot.at[t]; up=lambda x: np.ceil(x/g-1e-9)*g; dn=lambda x: np.floor(x/g+1e-9)*g
    if struct=='S1 iron fly':
        K=round(sp/g)*g; st=day.px('C',K,t)+day.px('P',K,t)
        if not np.isfinite(st): return None
        W=max(g,round(st/g)*g); return [('C',K,-1),('P',K,-1),('C',K+W,1),('P',K-W,1)]
    sc,sp_=up(sp*1.0015),dn(sp*0.9985); lc,lp=up(sp*1.006),dn(sp*0.994)
    if lc<=sc: lc=sc+g
    if lp>=sp_: lp=sp_-g
    return [('C',sc,-1),('P',sp_,-1),('C',lc,1),('P',lp,1)]
def run(day,t,legs,stress,exit):
    cr=0.0
    for cp,k,q in legs:
        p=day.px(cp,k,t)
        if not np.isfinite(p): return None
        cr+= (p-day.h(p,stress)-day.fee) if q<0 else -(p+day.h(p,stress)+day.fee)
    if exit=='settle':
        X=day.settle; val=sum(q*(max(X-k,0) if cp=='C' else max(k-X,0)) for cp,k,q in legs)
    else:
        t2=t.normalize()+pd.Timedelta(hours=15,minutes=55); val=0.0
        for cp,k,q in legs:
            m=day.mark(cp,k,t2)
            if not np.isfinite(m):
                if q<0: return None
                m=0.0
            val+= q*(max(m-day.h(m,stress)-day.fee,0) if q>0 else (m+day.h(m,stress)+day.fee))
    wc=max(k for cp,k,q in legs if cp=='C' and q>0)-max(k for cp,k,q in legs if cp=='C' and q<0)
    wp=min(k for cp,k,q in legs if cp=='P' and q<0)-min(k for cp,k,q in legs if cp=='P' and q>0)
    risk=max(wc,wp)-cr; pnl=cr+val
    return dict(pnl=pnl,credit=cr,risk=risk,ret=pnl/risk if risk>0 else np.nan)
def f(args):
    U,d=args
    try: day=SPXDay(d) if U=='SPX' else ETFDay(U,d)
    except Exception: return []
    out=[]
    for hm in TIMES:
        t=pd.Timestamp(f'{d} {hm}',tz='America/New_York')
        if not np.isfinite(day.spot.at[t]): continue
        for struct in ('S1 iron fly','S2 iron condor'):
            legs=legs_for(day,t,struct)
            if not legs: continue
            for stress in (False,True):
                for ex in ('settle','close1555'):
                    r=run(day,t,legs,stress,ex)
                    if r: out.append(dict(U=U,d=d,hm=hm,struct=struct,fill='stress' if stress else 'base',exit=ex,**r))
    return out
if __name__=='__main__':
    for U in ('SPY','QQQ','IWM'): RAW[U]=load_raw(U)
    jobs=[('SPX',d) for d in sorted(E.ratio.index)]
    for U in ('SPY','QQQ','IWM'): jobs+=[(U,os.path.basename(p)[:10]) for p in sorted(glob.glob(f'{S}/etfopt/{U}/*.parquet'))]
    with ProcessPoolExecutor(8) as ex: R=pd.DataFrame([r for x in ex.map(f,jobs,chunksize=8) for r in x])
    R.to_parquet(f'{S}/sell_afternoon.parquet'); print(len(R),'rows')
