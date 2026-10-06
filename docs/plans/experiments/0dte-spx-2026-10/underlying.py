"""Underlying-only test of the guide's rules on SPY 1-min RTH bars (2016-01 .. 2026-10).
Pre-registered (from the guide, not fitted):
  ORB: opening range = 09:30-09:44 bars. Scan bar CLOSES 09:45..10:29; first close > ORH -> long,
       < ORL -> short. Entry = NEXT bar open. One trade/day. Horizons: 10,15,30,60m, and to 14:45.
  CVD filters (tick-rule, trade level): A = cum signed vol since 09:30 agrees with direction;
       B = last-5-min signed vol agrees. Also report the DISAGREE bucket.
  Power hour: PH1 Gao et al: sign(r 09:30->10:00) -> trade 15:30->16:00.
              PH2 sign(r open->15:00) -> trade 15:00->16:00.
Split: FIT 2016-2020, TEST 2021-2026 (rules weren't fitted, so this is a stability check).
t-stats: one obs/day, Newey-West HAC lag 5.
"""
import sys, glob, os, pandas as pd, numpy as np
S=sys.argv[1]
b=pd.read_parquet(f'{S}/spy_1m.parquet')
b['d']=b.t.dt.date; b['hm']=b.t.dt.strftime('%H:%M')
def nw_t(x,L=5):
    x=np.asarray(x,float); x=x[~np.isnan(x)]; n=len(x)
    if n<10: return np.nan
    e=x-x.mean(); g0=e@e/n; s=g0
    for l in range(1,L+1): s+=2*(1-l/(L+1))*(e[l:]@e[:-l])/n
    return x.mean()/np.sqrt(s/n)
cvd={}
for f in glob.glob(f'{S}/cvd/*.parquet'):
    c=pd.read_parquet(f); cvd[pd.Timestamp(os.path.basename(f)[:10]).date()]=c.set_index(c.m.dt.strftime('%H:%M')).sv
trades=[]; ph=[]
for d,g in b.groupby('d'):
    g=g.set_index('hm')
    if '09:30' not in g.index or '15:59' not in g.index or len(g)<370: continue  # skip half-days/gaps
    orr=g.loc['09:30':'09:44']; H,L=orr.h.max(),orr.l.min()
    def px(hm):  # price at open of bar hm (or last close before)
        if hm in g.index: return g.at[hm,'o']
        return g.loc[:hm].c.iloc[-1]
    # power hour
    r1=px('10:00')/g.at['09:30','o']-1; r2=px('15:00')/g.at['09:30','o']-1
    close=g.at['15:59','c']
    ph.append(dict(d=d,PH1=np.sign(r1)*(close/px('15:30')-1)*1e4,PH2=np.sign(r2)*(close/px('15:00')-1)*1e4,
                   L1=(close/px('15:30')-1)*1e4,L2=(close/px('15:00')-1)*1e4))
    scan=g.loc['09:45':'10:29']
    sig=None
    for hm,row in scan.iterrows():
        if row.c>H: sig=(hm,1);break
        if row.c<L: sig=(hm,-1);break
    if not sig: continue
    hm,dr=sig; t0=pd.Timestamp(f'{d} {hm}')+pd.Timedelta(minutes=1); e_hm=t0.strftime('%H:%M')
    e=px(e_hm); r=dict(d=d,dir=dr,sig=hm)
    for h in (10,15,30,60):
        x=(t0+pd.Timedelta(minutes=h)).strftime('%H:%M'); r[f'h{h}']=dr*(px(x)/e-1)*1e4
    r['h1445']=dr*(px('14:45')/e-1)*1e4
    c=cvd.get(d)
    if c is not None:
        r['cvdA']=np.sign(c.loc[:hm].sum())==dr
        lo=(pd.Timestamp(f'{d} {hm}')-pd.Timedelta(minutes=4)).strftime('%H:%M')
        r['cvdB']=np.sign(c.loc[lo:hm].sum())==dr
    trades.append(r)
T=pd.DataFrame(trades); P=pd.DataFrame(ph)
for X in (T,P): X['per']=np.where(pd.to_datetime(X.d).dt.year<=2020,'FIT16-20','TEST21-26')
T.to_parquet(f'{S}/orb_trades.parquet'); P.to_parquet(f'{S}/ph.parquet')
def summ(df,cols,label):
    out=[]
    for per,g in [('ALL',df)]+list(df.groupby('per')):
        for c in cols:
            x=g[c].dropna(); out.append(dict(set=label,per=per,metric=c,n=len(x),mean_bp=x.mean(),t=nw_t(x),hit=(x>0).mean()))
    return pd.DataFrame(out)
H=['h10','h15','h30','h60','h1445']
R=[summ(T,H,'ORB all')]
if 'cvdA' in T:
    TC=T.dropna(subset=['cvdA'])
    R+= [summ(TC,H,'ORB (cvd days)'),summ(TC[TC.cvdA==True],H,'ORB+CVD-A agree'),summ(TC[TC.cvdA==False],H,'ORB CVD-A DISagree'),
         summ(TC[TC.cvdB==True],H,'ORB+CVD-B agree'),summ(TC[TC.cvdB==False],H,'ORB CVD-B DISagree')]
R+=[summ(T[T.dir==1],H,'ORB longs'),summ(T[T.dir==-1],H,'ORB shorts'),summ(P,['PH1','PH2','L1','L2'],'PowerHour')]
R=pd.concat(R); pd.set_option('display.width',200)
print(R.round(2).to_string(index=False))
print('\nORB by year (h30, h1445):'); print(T.assign(y=pd.to_datetime(T.d).dt.year).groupby('y')[['h30','h1445']].agg(['count','mean']).round(1).to_string())
print('\nPH2 by year:'); print(P.assign(y=pd.to_datetime(P.d).dt.year).groupby('y')[['PH1','PH2']].agg(['mean']).round(2).T.to_string())
