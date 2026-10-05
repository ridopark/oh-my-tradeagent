"""A6 underlying: asymmetric first-passage, target T bp before stop Sbp, 120-min horizon, timeout = return at 120m.
EV in bp per trade. ORB30 ALL vs score==2. FIT 2016-20 / TEST 2021-2026Q1."""
import sys, pandas as pd, numpy as np
S=sys.argv[1]
b=pd.read_parquet(f'{S}/spy_1m.parquet'); b=b[b.t<'2026-04-01']; b['d']=b.t.dt.date
F=pd.read_parquet(f'{S}/orb30_feat.parquet')
G={d:g.set_index(g.t.dt.strftime('%H:%M')) for d,g in b.groupby('d')}
rows=[]
for d,r in F.iterrows():
    g=G[d]; idx=list(g.index); i=idx.index(r.sig_hm)+1
    p=g.iloc[i:i+120]; e=p.o.iloc[0]; dr=r.dir
    fav=((p.h/e-1) if dr>0 else (1-p.l/e))*1e4; adv=((1-p.l/e) if dr>0 else (p.h/e-1))*1e4
    end=dr*(p.c.iloc[-1]/e-1)*1e4
    for T_,S_ in ((15,30),(20,30),(20,40),(25,40),(30,30)):
        ft=np.argmax(fav.values>=T_) if (fav.values>=T_).any() else 10**9
        at=np.argmax(adv.values>=S_) if (adv.values>=S_).any() else 10**9
        if at<=ft and at<10**9: res,v=('stop',-S_)
        elif ft<10**9: res,v=('target',T_)
        else: res,v=('time',end)
        rows.append(dict(d=d,fit=r.fit,score=r.score,TS=f'T{T_}/S{S_}',res=res,bp=v,win=v>0))
X=pd.DataFrame(rows)
for lab,sub in [('ALL',X),('score==2',X[X.score==2])]:
    t=sub.groupby(['TS','fit']).agg(n=('bp','size'),win=('win','mean'),ev_bp=('bp','mean'),timeout=('res',lambda s:(s=='time').mean())).unstack('fit')
    t.columns=[f"{a}_{'FIT' if b else 'TEST'}" for a,b in t.columns]
    for c in t.columns:
        if c.startswith(('win','timeout')): t[c]*=100
    print(f'\n== {lab}'); print(t.round(1).to_string())
