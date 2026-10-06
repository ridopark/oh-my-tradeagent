"""H1/H2 conditioning tests on the SPY ORB trades from underlying.py.  PRE-REGISTERED 2026-10-04,
written before any H1/H2 result was seen.

  H1  ORB on scheduled macro days (CPI or NFP release [ALFRED vintage dates, CPIAUCNS/PAYEMS], or
      FOMC statement day [federalreserve.gov calendars]) vs all other days.
  H2a Off-exchange share (exchange 'D' = FINRA ADF/TRF) of SPY volume 09:30-09:44, ranked against the
      TRAILING 60 days only (no look-ahead): top tercile vs bottom tercile.
  H2b Off-exchange tick-signed imbalance 09:30-09:44 (sign from the global trade sequence) agrees
      with the breakout direction vs disagrees.
  Horizons: h30 and h1445 (to 14:45) -> 3 hypotheses x 2 horizons = 6 tests.
  PASS (per test): pooled Welch t of (condition - complement) >= 2.64 (Bonferroni 0.05/6, 2-sided)
      AND the difference has the same sign in all three periods P1 2016-20, P2 2021-23, P3 2024-26.
  CPI/NFP/FOMC split of H1 is reported as EXPLORATORY only.
"""
import sys, glob, os, pandas as pd, numpy as np
S=sys.argv[1]
T=pd.read_parquet(f'{S}/orb_trades.parquet'); T['d']=pd.to_datetime(T.d)
CAL=os.path.join(os.path.dirname(os.path.abspath(__file__)),'calendar')
rd=lambda f:set(pd.to_datetime(open(f'{CAL}/{f}').read().split()))
cpi,nfp,fomc=rd('CPIAUCNS_dates.txt'),rd('PAYEMS_dates.txt'),rd('fomc_dates.txt')
T['cpi']=T.d.isin(cpi); T['nfp']=T.d.isin(nfp); T['fomc']=T.d.isin(fomc); T['event']=T.cpi|T.nfp|T.fomc
T['per']=pd.cut(T.d.dt.year,[0,2020,2023,2100],labels=['P1 16-20','P2 21-23','P3 24-26'])
# H2 features
rows=[]
for f in sorted(glob.glob(f'{S}/offex/*.parquet')):
    o=pd.read_parquet(f); o=o[o.m.dt.strftime('%H:%M')<='09:44']
    D=o[o.D]; rows.append(dict(d=pd.Timestamp(os.path.basename(f)[:10]),share=D.v.sum()/o.v.sum(),imb=D.sv.sum()/max(D.v.sum(),1)))
F=pd.DataFrame(rows).sort_values('d')
F['rank']=F.share.rolling(61).apply(lambda x:(x[:-1]<x[-1]).mean(),raw=True)   # today vs prior 60
T=T.merge(F,on='d',how='left')
T['h2a']=np.where(T['rank']>=2/3,True,np.where(T['rank']<1/3,False,np.nan))
T['h2b']=np.where(T.imb.isna(),np.nan,np.sign(T.imb)==T.dir)
def welch(a,b):
    a,b=a.dropna(),b.dropna(); return (a.mean()-b.mean())/np.sqrt(a.var()/len(a)+b.var()/len(b)), a.mean()-b.mean()
out=[]
for name,flag in [('H1 event',T.event),('H2a offex-share hi vs lo',T.h2a),('H2b offex-imb agrees',T.h2b),
                  ('expl: CPI',T.cpi),('expl: NFP',T.nfp),('expl: FOMC',T.fomc)]:
    m=pd.Series(flag).astype('float')
    for h in ('h30','h1445'):
        a,b=T[h][m==1],T[h][m==0]; t,dif=welch(a,b)
        r=dict(test=name,h=h,n_cond=a.notna().sum(),n_comp=b.notna().sum(),cond_bp=a.mean(),comp_bp=b.mean(),diff_bp=dif,t=t)
        for p in T.per.cat.categories:
            k=T.per==p; r[p]=welch(T[h][(m==1)&k],T[h][(m==0)&k])[1]
        same=np.sign([r[p] for p in T.per.cat.categories]); r['PASS']=(abs(t)>=2.64) and len(set(same))==1 and not name.startswith('expl')
        out.append(r)
pd.set_option('display.width',220); print(pd.DataFrame(out).round(2).to_string(index=False))
print('\noffex coverage days:',F.share.notna().sum(),' share median %.3f'%F.share.median(), ' by year:',F.groupby(F.d.dt.year).share.median().round(3).to_dict())
