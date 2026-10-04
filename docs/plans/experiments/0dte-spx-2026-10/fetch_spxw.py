import sys, os; sys.path.insert(0, sys.argv[1]); from alp import *
import pandas as pd, numpy as np
from concurrent.futures import ThreadPoolExecutor
S=sys.argv[1]
b=pd.read_parquet(f'{S}/spy_1m.parquet'); b=b[b.t>='2024-03-01']
op=b.groupby(b.t.dt.date).o.first()
def one(d):
    f=f'{S}/spxw/{d}.parquet'
    if os.path.exists(f): return 0
    c=round(op[d]*10.03/5)*5; yy=d.strftime('%y%m%d')
    syms=[f'SPXW{yy}{cp}{k*1000:08d}' for k in range(int(c)-100,int(c)+105,5) for cp in 'CP']
    rows=[]
    for i in range(0,len(syms),50):
        for page in paged('/v1beta1/options/bars','bars',symbols=','.join(syms[i:i+50]),timeframe='1Min',
                          start=f'{d}T13:00:00Z',end=f'{d}T21:00:00Z',limit=10000):
            for s,v in page.items():
                for x in v: x['sym']=s; rows.append(x)
    df=pd.DataFrame(rows)
    if df.empty: return 0
    df['t']=pd.to_datetime(df.t,utc=True).dt.tz_convert('America/New_York')
    df['cp']=df.sym.str[10]; df['k']=df.sym.str[11:].astype(int)/1000
    df.to_parquet(f); return len(df)
with ThreadPoolExecutor(6) as ex: n=list(ex.map(one,list(op.index)))
print(len(n),'days', sum(x>0 for x in n),'with data', sum(n),'bars')
