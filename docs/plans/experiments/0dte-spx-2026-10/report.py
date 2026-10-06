import sys, pandas as pd, numpy as np
S=sys.argv[1]; R=pd.read_parquet(f'{S}/replay.parquet')
R['half']=np.where(R.d<'2025-07-01','H1 24-03..25-06','H2 25-07..26-10')
def t(x): x=np.asarray(x); return x.mean()/(x.std(ddof=1)/np.sqrt(len(x)))
def agg(g): return pd.Series(dict(n=len(g),mean=g.ret.mean()*100,t=t(g.ret),hit=(g.ret>0).mean()*100,med=g.ret.median()*100,p5=g.ret.quantile(.05)*100))
pd.set_option('display.width',220)
for kind in R.kind.unique():
  for h in (0.10,0.25):
    X=R[(R.kind==kind)&(R.h==h)]
    A=X.groupby(['strike','rule']).apply(agg)
    B=X.groupby(['strike','rule','half']).apply(agg)[['n','mean','t']].unstack('half'); B.columns=[f'{b[:2]}_{a}' for a,b in B.columns]
    print(f'\n=== {kind}  half-spread {h} ===  (ret = % of premium incl. costs, per trade)')
    print(A.join(B).round(1).to_string())
print('\nentry premium (ATM) median by kind:', R[(R.strike=='ATM')].groupby('kind').prem.median().round(2).to_dict())
if R.cvdA.notna().any():
    X=R[(R.kind=='ORB')&(R.h==0.10)&R.cvdA.notna()]
    print('\n=== ORB split by CVD-A (h=0.10) ===')
    print(X.groupby(['strike','rule','cvdA']).apply(agg).round(1).to_string())
