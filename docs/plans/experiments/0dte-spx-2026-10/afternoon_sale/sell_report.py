import sys, pandas as pd, numpy as np
S=sys.argv[1]; R=pd.read_parquet(f'{S}/sell_afternoon.parquet').dropna(subset=['ret'])
CR={'2024-08-05','2025-04-03','2025-04-04','2025-04-07','2025-04-08','2025-04-09'}
tt=lambda x: x.mean()/x.std()*np.sqrt(len(x))
def agg(g):
    x=g.sort_values('d').ret; eq=x.cumsum()
    return pd.Series(dict(n=len(x),mean=x.mean()*100,t=tt(x),win=(g.pnl>0).mean()*100,H1=g[g.d<'2025-07-01'].ret.mean()*100,H2=g[g.d>='2025-07-01'].ret.mean()*100,
        exCrash=g[~g.d.isin(CR)].ret.mean()*100,worst=x.min()*100,maxDD=(eq-eq.cummax()).min()*100,credit_over_risk=(g.credit/g.risk).median()*100))
pd.set_option('display.width',250)
for hm in ('14:00','13:00','15:00'):
    X=R[(R.hm==hm)]
    T=X.groupby(['struct','U','exit','fill']).apply(agg)
    print(f'\n=================== ENTRY {hm} {"(PRIMARY)" if hm=="14:00" else "(sensitivity)"}  ret = % of max risk per day')
    print(T.round(1).to_string())
# PASS check (14:00, stress, close1555 for SPY/QQQ)
print('\n=== PASS CHECK (14:00, stress, close1555) ===')
for st in ('S1 iron fly','S2 iron condor'):
    X=R[(R.hm=='14:00')&(R.struct==st)&(R.fill=='stress')&(R.exit=='close1555')&R.U.isin(['SPY','QQQ'])]
    m={u:X[X.U==u].ret.mean()*100 for u in ('SPY','QQQ')}
    P=X.groupby('d').ret.mean(); h1=P[P.index<'2025-07-01'].mean()*100; h2=P[P.index>='2025-07-01'].mean()*100
    ok=all(v>0 for v in m.values()) and tt(P)>=2 and h1>0 and h2>0
    print(st,{k:round(v,2) for k,v in m.items()},'pooled t=%.2f'%tt(P),'H1 %.2f H2 %.2f'%(h1,h2),'PASS' if ok else 'FAIL')
