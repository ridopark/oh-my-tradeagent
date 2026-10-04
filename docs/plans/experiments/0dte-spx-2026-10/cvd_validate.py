import sys; sys.path.insert(0, sys.argv[1]); from alp import *
import pandas as pd, numpy as np
from scipy.stats import norm
from concurrent.futures import ThreadPoolExecutor
S=sys.argv[1]
bars=pd.read_parquet(f'{S}/spy_1m.parquet')
days=sorted(bars.t.dt.date.unique())
rng=np.random.default_rng(7); sample=[days[i] for i in sorted(rng.choice(len(days),30,replace=False))]
def tick_cvd(d):
    s=pd.Timestamp(f'{d} 09:30',tz='America/New_York').tz_convert('UTC').isoformat().replace('+00:00','Z')
    e=pd.Timestamp(f'{d} 10:30',tz='America/New_York').tz_convert('UTC').isoformat().replace('+00:00','Z')
    p=[];v=[];t=[]
    for page in paged('/v2/stocks/SPY/trades','trades',start=s,end=e,feed='sip',limit=10000):
        for x in page: p.append(x['p']);v.append(x['s']);t.append(x['t'])
    p=np.array(p);v=np.array(v,float)
    dp=np.sign(np.diff(p,prepend=p[0]))
    sgn=pd.Series(dp).replace(0,np.nan).ffill().fillna(0).values
    m=pd.to_datetime(pd.Series(t),utc=True,format='ISO8601').dt.tz_convert('America/New_York').dt.floor('1min')
    return d,pd.Series(sgn*v).groupby(m).sum()
with ThreadPoolExecutor(8) as ex: res=list(ex.map(tick_cvd,sample))
rows=[]
for d,tk in res:
    b=bars[bars.t.dt.date==d].set_index('t')
    b=b[b.index.time<pd.Timestamp('10:30').time()]
    r=np.log(b.c).diff().fillna(np.log(b.c/b.o))
    sig=r.std()
    bvc=b.v*(2*norm.cdf(r/sig)-1)
    br=b.v*np.sign(b.c-b.o)
    j=pd.concat([tk.rename('tick'),bvc.rename('bvc'),br.rename('barsign')],axis=1).dropna()
    rows.append(dict(day=d,n=len(j),corr_bvc=j.tick.corr(j.bvc),corr_bar=j.tick.corr(j.barsign),
        cum_corr_bvc=j.tick.cumsum().corr(j.bvc.cumsum()),cum_corr_bar=j.tick.cumsum().corr(j.barsign.cumsum()),
        sign_agree_10_bvc=np.mean(np.sign(j.tick.rolling(5).sum())==np.sign(j.bvc.rolling(5).sum())),
        sign_agree_10_bar=np.mean(np.sign(j.tick.rolling(5).sum())==np.sign(j.barsign.rolling(5).sum()))))
R=pd.DataFrame(rows); print(R.round(3).to_string()); print(R.drop(columns='day').median().round(3))
R.to_csv(f'{S}/cvd_validate.csv',index=False)
