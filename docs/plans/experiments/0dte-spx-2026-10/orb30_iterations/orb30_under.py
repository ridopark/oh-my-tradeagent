"""ORB30 on SPY 2016..2026-03 (holdout excluded). Signal: first 1-min close outside 09:30-09:59 range, 10:00-11:59.
Entry next bar open. Outcome: first-touch of +X vs -X (bp of entry) within 60 min, using 1-min high/low
(same-bar both -> counted as loss). X in {10,15,20,30} bp. Reports symmetric hit rate per period."""
import sys, pandas as pd, numpy as np
S=sys.argv[1]; filt=sys.argv[2] if len(sys.argv)>2 else 'none'
b=pd.read_parquet(f'{S}/spy_1m.parquet'); b=b[b.t<'2026-04-01']; b['d']=b.t.dt.date; b['hm']=b.t.dt.strftime('%H:%M')
rows=[]
for d,g in b.groupby('d'):
    g=g.set_index('hm')
    if '09:30' not in g.index or len(g)<370: continue
    orr=g.loc['09:30':'09:59']; H,L=orr.h.max(),orr.l.min(); rng=(H-L)/g.at['09:30','o']
    sc=g.loc['10:00':'11:59']; sig=None
    for i,(hm,r) in enumerate(sc.iterrows()):
        if r.c>H: sig=(i,1);break
        if r.c<L: sig=(i,-1);break
    if not sig: continue
    after=g.loc['10:00':].iloc[sig[0]+1:sig[0]+61]
    if after.empty: continue
    e=after.o.iloc[0]; dr=sig[1]; rec=dict(d=d,dir=dr,rng=rng,sig_hm=sc.index[sig[0]])
    fav=(after.h/e-1)*1e4*dr if dr>0 else (1-after.l/e)*1e4
    adv=(1-after.l/e)*1e4 if dr>0 else (after.h/e-1)*1e4
    for X in (10,15,20,30):
        f=np.argmax(fav.values>=X) if (fav.values>=X).any() else 10**9
        a=np.argmax(adv.values>=X) if (adv.values>=X).any() else 10**9
        rec[f'X{X}']=np.nan if f==a==10**9 else float(f<a)
    rec['ret60']=dr*(after.c.iloc[-1]/e-1)*1e4
    rows.append(rec)
R=pd.DataFrame(rows); R['y']=pd.to_datetime(R.d).dt.year; R.to_parquet(f'{S}/orb30_under.parquet')
per=pd.cut(R.y,[0,2020,2023,2100],labels=['2016-20','2021-23','2024-26Q1'])
cols=['X10','X15','X20','X30']
print('n signals',len(R)); print('hit rate (+X before -X), %:'); print((R.groupby(per)[cols].mean()*100).round(1).to_string())
print('ALL', (R[cols].mean()*100).round(1).to_dict())
print('ret60 bp mean by period', R.groupby(per).ret60.mean().round(2).to_dict())
