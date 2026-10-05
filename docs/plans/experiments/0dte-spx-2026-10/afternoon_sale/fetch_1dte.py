"""Day T-1 trading (15:30-16:00 ET) of day-T-expiry SPXW contracts, strikes ±200pt of T-1 spot, 5-grid.
Plus next-morning (09:30-10:00 ET) bars of the same contracts for the overnight-exit leg."""
import sys, os; sys.path.insert(0, sys.argv[1]); from alp import *
import pandas as pd
from concurrent.futures import ThreadPoolExecutor
S=sys.argv[1]
r=pd.read_csv(f'{S}/spx_ratio.csv',index_col=0).ratio  # expiry days with SPXW data
spy=pd.read_parquet(f'{S}/spy_1m.parquet'); spy['d']=spy.t.dt.date.astype(str)
close=spy.groupby('d').c.last(); days=sorted(set(r.index)&set(close.index))
prev={}; ds=sorted(close.index)
for i,d in enumerate(ds):
    if i and d in days: prev[d]=ds[i-1]
def one(T):
    f=f'{S}/spxw1dte/{T}.parquet'
    if os.path.exists(f) or T not in prev: return 0
    tm1=prev[T]; spot=close[tm1]*r[T]; yy=T[2:4]+T[5:7]+T[8:10]
    c0=int(round(spot/5)*5)
    syms=[f'SPXW{yy}{cp}{k*1000:08d}' for k in range(c0-200,c0+205,5) for cp in 'CP']
    out=[]
    for win,d0 in (('eve',tm1),('morn',T)):
        s=pd.Timestamp(f'{d0} '+('15:30' if win=='eve' else '09:30'),tz='America/New_York').tz_convert('UTC').strftime('%Y-%m-%dT%H:%M:%SZ')
        e=pd.Timestamp(f'{d0} '+('16:00' if win=='eve' else '10:00'),tz='America/New_York').tz_convert('UTC').strftime('%Y-%m-%dT%H:%M:%SZ')
        for i in range(0,len(syms),100):
            for page in paged('/v1beta1/options/bars','bars',symbols=','.join(syms[i:i+100]),timeframe='1Min',start=s,end=e,limit=10000):
                for sym,v in page.items():
                    for x in v: x['sym']=sym; x['win']=win; out.append(x)
    df=pd.DataFrame(out)
    if df.empty: return 0
    df['t']=pd.to_datetime(df.t,utc=True).dt.tz_convert('America/New_York')
    df['cp']=df.sym.str[10]; df['k']=df.sym.str[11:].astype(int)/1000
    df.to_parquet(f); return len(df)
with ThreadPoolExecutor(8) as ex: n=list(ex.map(one,days))
print('days',len(n),'with data',sum(x>0 for x in n),'bars',sum(n))
