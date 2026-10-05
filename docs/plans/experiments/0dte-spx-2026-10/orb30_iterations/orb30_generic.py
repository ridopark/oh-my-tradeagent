"""Frozen ORB30 score rule applied to any underlying. Score thresholds = that underlying's own FIT (2016-2020)
terciles: strength <= q1/3, rng_rel >= q2/3, minutes_after >= q2/3, gap_size >= q2/3. score==2 is the rule.
Outcomes: T15/S30, T20/S30 first-passage in 120m (timeout = return at 120m), symmetric X20/X30 in 60m."""
import sys, pandas as pd, numpy as np
S=sys.argv[1]; U=sys.argv[2]
path=f'{S}/spy_1m.parquet' if U=='SPY' else f'{S}/etf/{U}_1m.parquet'
b=pd.read_parquet(path); b['d']=b.t.dt.date; b['hm']=b.t.dt.strftime('%H:%M')
prev=b.groupby('d').c.last().shift(1)
def fp(fav,adv,T_,S_,end):
    ft=np.argmax(fav>=T_) if (fav>=T_).any() else 10**9; at=np.argmax(adv>=S_) if (adv>=S_).any() else 10**9
    return -S_ if (at<=ft and at<10**9) else T_ if ft<10**9 else end
rows=[]
for d,g in b.groupby('d'):
    g=g.set_index('hm')
    if '09:30' not in g.index or len(g)<370: continue
    o=g.at['09:30','o']; pc=prev.get(d); orr=g.loc['09:30':'09:59']; H,L=orr.h.max(),orr.l.min()
    sc=g.loc['10:00':'11:59']; sig=None
    for hm,r in sc.iterrows():
        if r.c>H: sig=(hm,1);break
        if r.c<L: sig=(hm,-1);break
    if not sig: continue
    hm,dr=sig; idx=list(g.index); i=idx.index(hm)+1; p=g.iloc[i:i+120]
    if len(p)<30: continue
    e=p.o.iloc[0]; fav=(((p.h/e-1) if dr>0 else (1-p.l/e))*1e4).values; adv=(((1-p.l/e) if dr>0 else (p.h/e-1))*1e4).values
    end120=dr*(p.c.iloc[-1]/e-1)*1e4; p60=slice(0,60)
    bar=g.loc[hm]
    rows.append(dict(d=d,dir=dr,sig_hm=hm,rng=(H-L)/o,
        strength=((bar.c-H)/(H-L) if dr>0 else (L-bar.c)/(H-L)),
        minutes_after=(pd.Timestamp(f'2000-01-01 {hm}')-pd.Timestamp('2000-01-01 10:00')).seconds/60,
        gap_size=abs(o/pc-1)*1e4 if pc==pc else np.nan,
        T15S30=fp(fav,adv,15,30,end120),T20S30=fp(fav,adv,20,30,end120),
        X20=fp(fav[p60],adv[p60],20,20,np.nan),X30=fp(fav[p60],adv[p60],30,30,np.nan),
        ret60=dr*(p.c.iloc[min(59,len(p)-1)]/e-1)*1e4))
R=pd.DataFrame(rows).set_index('d'); R['rng_rel']=R.rng/R.rng.rolling(20).mean().shift(1)
R['y']=pd.to_datetime(R.index).year; fit=R.y<=2020; q=lambda c,p: R.loc[fit,c].quantile(p)
R['score']=(R.strength<=q('strength',1/3)).astype(int)+(R.rng_rel>=q('rng_rel',2/3)).astype(int)+(R.minutes_after>=q('minutes_after',2/3)).astype(int)+(R.gap_size>=q('gap_size',2/3)).astype(int)
R['per']=pd.cut(R.y,[0,2020,2023,2100],labels=['FIT16-20','21-23','24-26'])
R['hold']=pd.to_datetime(R.index)>='2026-04-01'
R.to_parquet(f'{S}/etf/{U}_orb30.parquet')
def summ(x):
    return pd.Series(dict(n=len(x),win_T15S30=(x.T15S30>0).mean()*100,ev_T15S30=x.T15S30.mean(),win_T20S30=(x.T20S30>0).mean()*100,ev_T20S30=x.T20S30.mean(),
                          hitX20=(x.X20.dropna()>0).mean()*100,hitX30=(x.X30.dropna()>0).mean()*100,ret60=x.ret60.mean()))
A=R.groupby('per').apply(summ); B=R[R.score==2].groupby('per').apply(summ)
print(f'===== {U}  thresholds', {c:round(q(c,p),3) for c,p in [('strength',1/3),('rng_rel',2/3),('minutes_after',2/3),('gap_size',2/3)]})
out=pd.concat({'ALL':A,'score==2':B}).round(1); print(out.to_string())
