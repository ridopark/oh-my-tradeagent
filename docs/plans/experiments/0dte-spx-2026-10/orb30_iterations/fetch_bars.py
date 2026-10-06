import sys; sys.path.insert(0, sys.argv[1]); from alp import *
import pandas as pd
from concurrent.futures import ThreadPoolExecutor
S=sys.argv[1]; U=sys.argv[2]
def year(y):
    rows=[]
    for page in paged(f'/v2/stocks/{U}/bars','bars',timeframe='1Min',start=f'{y}-01-01',end=f'{y}-12-31T23:59:59Z',feed='sip',limit=10000,adjustment='all'):
        rows+=page
    df=pd.DataFrame(rows); df['t']=pd.to_datetime(df.t,utc=True).dt.tz_convert('America/New_York')
    return df[(df.t.dt.time>=pd.Timestamp('09:30').time())&(df.t.dt.time<pd.Timestamp('16:00').time())]
with ThreadPoolExecutor(6) as ex: df=pd.concat(list(ex.map(year,range(2016,2027)))).sort_values('t')
df.to_parquet(f'{S}/etf/{U}_1m.parquet'); print(U,len(df),df.t.dt.date.nunique(),'days')
