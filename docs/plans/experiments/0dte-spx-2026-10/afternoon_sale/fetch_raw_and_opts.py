"""Raw (unadjusted) 1-min bars 2024-03.. for U, then 0DTE option bars 12:30-16:00 ET, strikes $1 within +-3% of the open."""
import sys, os; sys.path.insert(0, sys.argv[1]); from alp import *
import pandas as pd, numpy as np
from concurrent.futures import ThreadPoolExecutor
S=sys.argv[1]; U=sys.argv[2]; os.makedirs(f'{S}/etfopt/{U}',exist_ok=True)
rows=[]
for page in paged(f'/v2/stocks/{U}/bars','bars',timeframe='1Min',start='2024-03-01',end='2026-10-03',feed='sip',limit=10000,adjustment='raw'): rows+=page
b=pd.DataFrame(rows); b['t']=pd.to_datetime(b.t,utc=True).dt.tz_convert('America/New_York')
b=b[(b.t.dt.time>=pd.Timestamp('09:30').time())&(b.t.dt.time<pd.Timestamp('16:00').time())]; b.to_parquet(f'{S}/etfopt/{U}_raw_1m.parquet')
op=b.groupby(b.t.dt.date.astype(str)).o.first()
def one(d):
    f=f'{S}/etfopt/{U}/{d}.parquet'
    if os.path.exists(f): return 0
    o=op[d]; yy=d[2:4]+d[5:7]+d[8:10]
    syms=[f'{U}{yy}{cp}{k*1000:08d}' for k in range(int(o*0.97),int(o*1.03)+2) for cp in 'CP']
    s=pd.Timestamp(f'{d} 12:30',tz='America/New_York').tz_convert('UTC').strftime('%Y-%m-%dT%H:%M:%SZ')
    e=pd.Timestamp(f'{d} 16:00',tz='America/New_York').tz_convert('UTC').strftime('%Y-%m-%dT%H:%M:%SZ')
    out=[]
    for i in range(0,len(syms),100):
        for page in paged('/v1beta1/options/bars','bars',symbols=','.join(syms[i:i+100]),timeframe='1Min',start=s,end=e,limit=10000):
            for sym,v in page.items():
                for x in v: x['sym']=sym; out.append(x)
    df=pd.DataFrame(out)
    if df.empty: return 0
    df['t']=pd.to_datetime(df.t,utc=True).dt.tz_convert('America/New_York'); n=len(U)
    df['cp']=df.sym.str[n+6]; df['k']=df.sym.str[n+7:].astype(int)/1000
    df.to_parquet(f); return len(df)
with ThreadPoolExecutor(6) as ex: n=list(ex.map(one,list(op.index)))
print(U,len(n),'days',sum(x>0 for x in n),'with 0DTE data',sum(n),'bars')
