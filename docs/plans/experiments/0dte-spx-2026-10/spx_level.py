"""Per-day SPX/SPY ratio from SPXW 0DTE put-call parity (S ~= K + C - P at the strike minimizing |C-P|)."""
import sys, glob, os, pandas as pd, numpy as np
S=sys.argv[1]
spy=pd.read_parquet(f'{S}/spy_1m.parquet'); spy=spy[spy.t>='2024-03-01'].set_index('t').c
out={}
for f in sorted(glob.glob(f'{S}/spxw/*.parquet')):
    d=os.path.basename(f)[:10]; o=pd.read_parquet(f,columns=['t','cp','k','c'])
    o=o[(o.t.dt.hour>=10)&(o.t.dt.hour<15)]
    w=o.pivot_table(index=['t','k'],columns='cp',values='c').dropna().reset_index()
    w['gap']=(w.C-w.P).abs(); w=w.loc[w.groupby('t').gap.idxmin()]
    w['S']=w.k+w.C-w.P; w=w.set_index('t').join(spy.rename('spy'),how='inner')
    if len(w)<30: continue
    r=w.S/w.spy; out[d]=dict(ratio=r.median(),iqr=r.quantile(.75)-r.quantile(.25),n=len(w))
R=pd.DataFrame(out).T; R.to_csv(f'{S}/spx_ratio.csv'); print(R.describe().round(4))
