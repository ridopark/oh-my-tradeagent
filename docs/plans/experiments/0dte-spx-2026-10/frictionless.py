import sys, os; S=sys.argv[1]; os.environ['CACHE']=S; sys.path.insert(0,S)
import engine as E
E.half=lambda p,stress: 0.0          # zero spread, fees only
sys.argv=['x',S]; exec(open(f'{S}/strategies.py').read().split("if __name__")[0])
import pandas as pd
from concurrent.futures import ProcessPoolExecutor
def only_base(d): return [r for r in day_results(d) if r['fill']=='base']
if __name__=='__main__':
    with ProcessPoolExecutor(8) as ex: res=[r for x in ex.map(only_base,sorted(E.ratio.index),chunksize=4) for r in x]
    R=pd.DataFrame(res); R['fill']='frictionless'; R.to_parquet(f'{S}/strategies_frictionless.parquet')
    import numpy as np
    R['half']=np.where(R.d<'2025-07-01','H1','H2')
    g=R.groupby('strat').agg(days=('ret','size'),mean_pct=('ret',lambda x:x.mean()*100),t=('ret',lambda x:x.mean()/x.std()*np.sqrt(len(x))),total_pts=('pnl','sum'))
    g['H1']=R[R.half=='H1'].groupby('strat').ret.mean()*100; g['H2']=R[R.half=='H2'].groupby('strat').ret.mean()*100
    print(g.round(2).to_string())
