"""SPXW 0DTE option replay of the guide's rules, 2024-03 .. 2026-10 (Alpaca trade-print minute bars).
PRE-REGISTERED grid (all reported, nothing dropped):
  entries : ORB (all) | ORB+CVD-A agree | PH2 (sign open->15:00, enter 15:01, flat 15:50)
  strikes : ATM | 1-OTM (5pt)
  exits   : R0 hold-to-flat | R1 stop-30 | R2 stop-50 | R3 GUIDE(stop-30, 15m time-stop if <+10%,
            half off at +50% then BE stop) | R4 GUIDE with stop-50 | R5 30-min fixed exit
  flat    : 14:45 for ORB (repo force_close_0dte_et for SPX-style), 15:50 for PH2
  costs   : per side = half-spread h + fees $0.011 (=$1.10/contract: Alpaca $0.50 + ~$0.60 Cboe/ORF est.)
            h in {0.10 base, 0.25 stress} index pts. Bars are trade prints, so this is ON TOP of the print.
  fills   : entry = open of entry bar (first bar with a print within 3 min, else skip);
            stops: if bar low <= stop -> exit at min(bar open, stop); stop checked BEFORE target in a bar.
"""
import sys, pandas as pd, numpy as np
S=sys.argv[1]
T=pd.read_parquet(f'{S}/orb_trades.parquet'); P=pd.read_parquet(f'{S}/ph.parquet')
spy=pd.read_parquet(f'{S}/spy_1m.parquet'); spy=spy[spy.t>='2024-03-01']; spy['d']=spy.t.dt.date.astype(str)
ratio=pd.read_csv(f'{S}/spx_ratio.csv',index_col=0).ratio
FEE=0.011
def sim(path, e, rule, flat_hm, h):
    """path: DataFrame indexed by time (bars after entry incl. entry bar) with o,h,l,c. returns pct pnl of entry cost."""
    entry=e+h+FEE; stop={'R1':.7,'R2':.5,'R3':.7,'R4':.5}.get(rule)
    stop_px = entry*stop if stop else None
    t0=path.index[0]; legs=[]  # (fraction, exit_price)
    rem=1.0; be=False
    for t,b in path.iterrows():
        hm=t.strftime('%H:%M')
        if hm>=flat_hm: legs.append((rem,b.o)); rem=0; break
        if rule=='R5' and t>=t0+pd.Timedelta(minutes=30): legs.append((rem,b.o)); rem=0; break
        if stop_px is not None and b.l<=stop_px:
            legs.append((rem,min(b.o,stop_px))); rem=0; break
        if rule in('R3','R4'):
            if not be and b.h>=entry*1.5: legs.append((0.5,entry*1.5)); rem=0.5; be=True; stop_px=entry
            if not be and t>=t0+pd.Timedelta(minutes=15) and b.c<entry*1.10: legs.append((rem,b.c)); rem=0; break
    if rem>0: legs.append((rem,path.c.iloc[-1]))
    proceeds=sum(f*max(px-h-FEE,0) for f,px in legs)
    return proceeds/entry-1
rows=[]
def run(kind,d,dr,entry_t,flat_hm,cvdA=None):
    try: o=pd.read_parquet(f'{S}/spxw/{d}.parquet')
    except FileNotFoundError: return
    s=spy[(spy.d==d)&(spy.t>=entry_t)]
    if s.empty or d not in ratio: return
    spx=s.o.iloc[0]*ratio[d]; atm=round(spx/5)*5; cp='C' if dr>0 else 'P'
    for sk,k in (('ATM',atm),('OTM1',atm+5*dr)):
        p=o[(o.cp==cp)&(o.k==k)].set_index('t').sort_index()
        p=p[p.index>=entry_t]
        if p.empty or p.index[0]>entry_t+pd.Timedelta(minutes=3): continue
        e=p.o.iloc[0]
        if e<0.5: continue
        for rule in ('R0','R1','R2','R3','R4','R5'):
            for h in (0.10,0.25):
                rows.append(dict(kind=kind,d=d,dir=dr,strike=sk,rule=rule,h=h,prem=e,ret=sim(p,e,rule,flat_hm,h),cvdA=cvdA))
for _,r in T[T.d.astype(str)>='2024-03-01'].iterrows():
    et=pd.Timestamp(f"{r.d} {r.sig}",tz='America/New_York')+pd.Timedelta(minutes=1)
    run('ORB',str(r.d),r.dir,et,'14:45',r.get('cvdA'))
for _,r in P[P.d.astype(str)>='2024-03-01'].iterrows():
    dr=np.sign(r.PH2/ (r.L2 if r.L2!=0 else np.nan)) if r.L2!=0 else np.nan  # recover sign(r_open->15:00)
    if not np.isfinite(dr) or dr==0: continue
    run('PH2',str(r.d),int(dr),pd.Timestamp(f"{r.d} 15:01",tz='America/New_York'),'15:50')
R=pd.DataFrame(rows); R.to_parquet(f'{S}/replay.parquet'); print(len(R),'rows')
