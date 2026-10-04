import sys, pandas as pd, numpy as np
S=sys.argv[1]; R=pd.read_parquet(f'{S}/strategies.parquet')
CRASH={'2024-08-05','2025-04-03','2025-04-04','2025-04-07','2025-04-08','2025-04-09'}
R['half']=np.where(R.d<'2025-07-01','H1','H2')
def t(x): return x.mean()/x.std()*np.sqrt(len(x)) if len(x)>2 else np.nan
rows=[]
for (s,f),g in R.groupby(['strat','fill']):
    x=g.ret; nc=g[~g.d.isin(CRASH)].ret
    eq=g.sort_values('d').pnl.cumsum(); dd=(eq-eq.cummax()).min()
    rows.append(dict(strat=s,fill=f,days=len(g),mean_pct=x.mean()*100,t=t(x),win=(g.pnl>0).mean()*100,
        H1=g[g.half=='H1'].ret.mean()*100,H2=g[g.half=='H2'].ret.mean()*100,t_H1=t(g[g.half=='H1'].ret),t_H2=t(g[g.half=='H2'].ret),
        ex_crash=nc.mean()*100,crash_days_pts=g[g.d.isin(CRASH)].pnl.sum(),total_pts=g.pnl.sum(),maxDD_pts=dd,worst_day_pts=g.pnl.min()))
X=pd.DataFrame(rows)
P=[]
for s,g in X.groupby('strat'):
    b=g[g.fill=='base'].iloc[0]; st=g[g.fill=='stress'].iloc[0]
    P.append(dict(strat=s,PASS=bool(b.t>=2.7 and b.H1>0 and b.H2>0 and st.mean_pct>0)))
pd.set_option('display.width',250)
print(X.round(2).to_string(index=False)); print(); print(pd.DataFrame(P).to_string(index=False))
