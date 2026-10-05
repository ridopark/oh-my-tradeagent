"""ORB30 conditioning features (all known at entry). Outcome X20 (+20bp before -20bp in 60m) and ret60.
FIT = 2016-2020, TEST = 2021-2026Q1. Buckets: terciles cut on FIT only."""
import sys, pandas as pd, numpy as np
S=sys.argv[1]
b=pd.read_parquet(f'{S}/spy_1m.parquet'); b=b[b.t<'2026-04-01']; b['d']=b.t.dt.date; b['hm']=b.t.dt.strftime('%H:%M')
R=pd.read_parquet(f'{S}/orb30_under.parquet').set_index('d')
day_close=b.groupby('d').c.last(); prev_close=day_close.shift(1)
feat={}
for d,g in b.groupby('d'):
    if d not in R.index: continue
    g=g.set_index('hm'); r=R.loc[d]; dr=r.dir; sh=r.sig_hm
    o=g.at['09:30','o']; pc=prev_close.get(d)
    orr=g.loc['09:30':'09:59']; H,L=orr.h.max(),orr.l.min()
    bar=g.loc[sh]; upto=g.loc[:sh]; vwap=(upto.vw*upto.v).sum()/upto.v.sum()
    feat[d]=dict(gap_align=np.sign(o-pc)*dr if pc==pc else np.nan,
                 gap_size=abs(o/pc-1)*1e4 if pc==pc else np.nan,
                 vol_ratio=bar.v/orr.v.mean(),
                 strength=((bar.c-H)/(H-L) if dr>0 else (L-bar.c)/(H-L)),
                 vwap_align=float(np.sign(bar.c-vwap)==dr),
                 prevclose_align=float(np.sign(bar.c-pc)==dr) if pc==pc else np.nan,
                 minutes_after=(pd.Timestamp(f'2000-01-01 {sh}')-pd.Timestamp('2000-01-01 10:00')).seconds/60)
F=pd.DataFrame(feat).T.astype(float); R=R.join(F)
R['rng_rel']=R.rng/R.rng.rolling(20).mean().shift(1)
R['fit']=R.y<=2020
def show(col,cuts=None,cat=False):
    x=R[col]
    if cat: lab=x
    else:
        q=R.loc[R.fit,col].quantile([1/3,2/3]).values if cuts is None else cuts
        lab=pd.cut(x,[-np.inf,*q,np.inf],labels=['lo','mid','hi'])
    t=R.groupby([lab,R.fit]).agg(n=('X20','size'),hit=('X20','mean'),ret60=('ret60','mean')).unstack('fit')
    t.columns=[f"{a}_{'FIT' if b else 'TEST'}" for a,b in t.columns]; t['hit_FIT']*=100; t['hit_TEST']*=100
    print(f'\n-- {col}'); print(t[['n_FIT','hit_FIT','ret60_FIT','n_TEST','hit_TEST','ret60_TEST']].round(1).to_string())
for c,cat in [('gap_align',True),('rng_rel',False),('vol_ratio',False),('strength',False),('vwap_align',True),('prevclose_align',True),('minutes_after',False),('gap_size',False)]:
    show(c,cat=cat)
R.to_parquet(f'{S}/orb30_feat.parquet')
