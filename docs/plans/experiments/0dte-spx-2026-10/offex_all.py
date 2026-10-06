"""Per-minute SPY volume split on-exchange vs off-exchange (x=='D', FINRA ADF/TRF), 09:30-10:30 ET.
sv = tick-rule signed volume, where the sign comes from the GLOBAL trade sequence (all venues)."""
import sys, os; sys.path.insert(0, sys.argv[1]); from alp import *
import pandas as pd, numpy as np
from concurrent.futures import ThreadPoolExecutor
S=sys.argv[1]
days=sorted(pd.read_parquet(f'{S}/spy_1m.parquet',columns=['t']).t.dt.date.unique())
def one(d):
    f=f'{S}/offex/{d}.parquet'
    if os.path.exists(f): return
    s=pd.Timestamp(f'{d} 09:30',tz='America/New_York').tz_convert('UTC').strftime('%Y-%m-%dT%H:%M:%SZ')
    e=pd.Timestamp(f'{d} 10:31',tz='America/New_York').tz_convert('UTC').strftime('%Y-%m-%dT%H:%M:%SZ')
    p=[];v=[];t=[];x=[]
    for page in paged('/v2/stocks/SPY/trades','trades',start=s,end=e,feed='sip',limit=10000):
        for r in page: p.append(r['p']);v.append(r['s']);t.append(r['t']);x.append(r['x'])
    p=np.array(p);v=np.array(v,float)
    sgn=pd.Series(np.sign(np.diff(p,prepend=p[0]))).replace(0,np.nan).ffill().fillna(0).values
    m=pd.to_datetime(pd.Series(t),utc=True,format='ISO8601').dt.tz_convert('America/New_York').dt.floor('1min')
    pd.DataFrame({'m':m,'D':np.array(x)=='D','sv':sgn*v,'v':v}).groupby(['m','D']).agg(sv=('sv','sum'),v=('v','sum'),n=('v','size')).reset_index().to_parquet(f)
with ThreadPoolExecutor(10) as ex:
    for i,_ in enumerate(ex.map(one,days)):
        if i%200==0: print(i,flush=True)
print('done')
