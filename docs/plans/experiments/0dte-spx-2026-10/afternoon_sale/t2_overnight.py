"""T2 (PRE-REGISTERED 2026-10-05, before results): 1DTE overnight SPXW short premium.
Enter 15:45 on T-1 (prints from the 15:30-16:00 window; px = last print <=15:45 within 10 min, else skip leg).
Structures (strikes on 5-grid from T-1 15:45 spot = SPY 15:45 close x ratio[T]):
  A  put spread  : short first strike <= spot*0.99,  long = short-50
  B  put spread  : short first strike <= spot*0.98,  long = short-50
  C  iron condor : shorts +-1% (call side first strike >= spot*1.01), wings 50 out
  D  put spread  : short first strike <= spot*0.9985, long = short-50 (near-ATM, continuity w/ S2)
Exits: (i) morn = buy back at T 09:35 marks (overnight VRP only; short leg must print 09:30-09:40 else skip),
       (ii) settle = hold to T cash settle (intrinsic at close[T]*ratio[T]).
Costs/leg/side: engine half(px) + $0.011 fees; stress = 2x half. Unit: pnl/(width-credit), one obs/overnight.
16 cells (4x2x2), ALL reported. PASS bar: stress settle-or-morn mean>0 in BOTH halves (split 2025-07-01)
AND pooled t>=2. Single-market caveat: no OOS instrument exists for this; any pass is provisional."""
import sys, os, glob, pandas as pd, numpy as np
S=sys.argv[1]; os.environ['CACHE']=S; sys.path.insert(0,S); import engine as E
ratio=pd.read_csv(f'{S}/spx_ratio.csv',index_col=0).ratio
spy=pd.read_parquet(f'{S}/spy_1m.parquet'); spy['d']=spy.t.dt.date.astype(str)
c1545={d:g[g.t.dt.strftime('%H:%M')<='15:45'].c.iloc[-1] for d,g in spy[spy.t>='2024-02-01'].groupby('d')}
close=spy.groupby('d').c.last()
rows=[]
for f in sorted(glob.glob(f'{S}/spxw1dte/*.parquet')):
    T=os.path.basename(f)[:10]; o=pd.read_parquet(f)
    eve=o[o.win=='eve']; morn=o[o.win=='morn']
    tm1=str(eve.t.dt.date.min())
    if tm1 not in c1545: continue
    spot=c1545[tm1]*ratio[T]; settle=close[T]*ratio[T]
    tcut=pd.Timestamp(f'{tm1} 15:45',tz='America/New_York'); mcut=pd.Timestamp(f'{T} 09:35',tz='America/New_York')
    def px_eve(cp,k):
        x=eve[(eve.cp==cp)&(eve.k==k)&(eve.t<=tcut)].sort_values('t')
        return x.c.iloc[-1] if len(x) and (tcut-x.t.iloc[-1]).total_seconds()<=600 else np.nan
    def px_morn(cp,k,req=True):
        x=morn[(morn.cp==cp)&(morn.k==k)&(morn.t<=mcut+pd.Timedelta(minutes=5))].sort_values('t')
        if not len(x): return np.nan if req else 0.0
        return x.c.iloc[-1]
    g5=lambda v: int(np.floor(v/5)*5); g5u=lambda v: int(np.ceil(v/5)*5)
    structs={'A put1%':[('P',g5(spot*0.99),-1),('P',g5(spot*0.99)-50,1)],
             'B put2%':[('P',g5(spot*0.98),-1),('P',g5(spot*0.98)-50,1)],
             'C ic1%':[('P',g5(spot*0.99),-1),('P',g5(spot*0.99)-50,1),('C',g5u(spot*1.01),-1),('C',g5u(spot*1.01)+50,1)],
             'D putATM':[('P',g5(spot*0.9985),-1),('P',g5(spot*0.9985)-50,1)]}
    for name,legs in structs.items():
        for stress in (False,True):
            h=lambda p: E.half(p,stress)+E.FEE
            cr=0.0; ok=True
            for cp,k,q in legs:
                p=px_eve(cp,k)
                if not np.isfinite(p): ok=False; break
                cr+= -q*p - h(p)
            if not ok: continue
            wc=[abs(l[1]-s[1]) for s in legs for l in legs if s[2]<0 and l[2]>0 and s[0]==l[0]]
            risk=max(wc)-cr
            if risk<=0: continue
            # settle
            val=sum(q*(max(settle-k,0) if cp=='C' else max(k-settle,0)) for cp,k,q in legs)
            rows.append(dict(T=T,struct=name,exit='settle',fill='stress' if stress else 'base',ret=(cr+val)/risk,credit=cr))
            # morning buyback
            val=0.0; ok=True
            for cp,k,q in legs:
                p=px_morn(cp,k,req=(q<0))
                if not np.isfinite(p): ok=False; break
                val+= q*p - h(p if p>0 else 0.05)*(1 if q<0 else (1 if p>0 else 0))
                if q<0: val-=0  # cost applied via h above on both sides
            if ok:
                rows.append(dict(T=T,struct=name,exit='morn',fill='stress' if stress else 'base',ret=(cr+val)/risk,credit=cr))
R=pd.DataFrame(rows); R.to_parquet(f'{S}/t2.parquet')
tt=lambda x: x.mean()/x.std()*np.sqrt(len(x))
CR={'2024-08-05','2025-04-03','2025-04-04','2025-04-07','2025-04-08','2025-04-09'}
g=R.groupby(['struct','exit','fill']).apply(lambda g: pd.Series(dict(n=len(g),mean=g.ret.mean()*100,t=tt(g.ret),win=(g.ret>0).mean()*100,
    H1=g[g['T']<'2025-07-01'].ret.mean()*100,H2=g[g['T']>='2025-07-01'].ret.mean()*100,worst=g.ret.min()*100,exCrash=g[~g['T'].isin(CR)].ret.mean()*100,cr_med=g.credit.median())))
pd.set_option('display.width',240); print(g.round(2).to_string())
