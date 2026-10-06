import sys; sys.path.insert(0, sys.argv[1]); from alp import *
import pandas as pd
from concurrent.futures import ThreadPoolExecutor
def year(y):
    rows=[]
    for page in paged('/v2/stocks/SPY/bars','bars',timeframe='1Min',start=f'{y}-01-01',end=f'{y}-12-31T23:59:59Z',feed='sip',limit=10000,adjustment='raw'):
        rows+=page
    df=pd.DataFrame(rows); df['t']=pd.to_datetime(df.t,utc=True).dt.tz_convert('America/New_York')
    df=df[(df.t.dt.time>=pd.Timestamp('09:30').time())&(df.t.dt.time<pd.Timestamp('16:00').time())]
    return df
with ThreadPoolExecutor(6) as ex: dfs=list(ex.map(year,range(2016,2027)))
df=pd.concat(dfs).sort_values('t'); df.to_parquet(f'{sys.argv[1]}/spy_1m.parquet')
print(len(df), df.t.min(), df.t.max(), df.t.dt.date.nunique(),'days')
