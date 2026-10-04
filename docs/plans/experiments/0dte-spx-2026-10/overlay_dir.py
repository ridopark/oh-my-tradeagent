import sys, os; S=sys.argv[1]; os.environ['CACHE']=S; sys.path.insert(0,S)
sys.argv=['x',S]; exec(open(f'{S}/strategies.py').read().split("if __name__")[0])
import pandas as pd, numpy as np
from concurrent.futures import ProcessPoolExecutor
def f(d):
    try: day=E.Day(d)
    except Exception: return []
    out=[]
    for m,dr,xm in NA.get(d,[]):
        t=T(d,m); atm=round(day.spx.at[t]/5)*5; cp='P' if dr>0 else 'C'
        for mode,st,tag in (('base',False,'base'),('base',True,'stress')):
            r=Sim(day,mode,st).run([dict(short=(cp,atm))],t,exit_t=T(d,xm) if xm else None)
            if r: out.append(dict(d=d,dir=dr,fill=tag,ret=r['pnl']/r['risk'],held=xm is None,entry=m))
    # placebo: same entry time, OPPOSITE side (short call on long signal)
        t=T(d,m); atm=round(day.spx.at[t]/5)*5; cp='C' if dr>0 else 'P'
        r=Sim(day,'base',False).run([dict(short=(cp,atm))],t,exit_t=T(d,xm) if xm else None)
        if r: out.append(dict(d=d,dir=dr,fill='placebo-opposite',ret=r['pnl']/r['risk'],held=xm is None,entry=m))
    return out
if __name__=='__main__':
    with ProcessPoolExecutor(8) as ex: R=pd.DataFrame([r for x in ex.map(f,sorted(E.ratio.index),chunksize=4) for r in x])
    R['half']=np.where(R.d<'2025-07-01','H1','H2')
    t=lambda x: x.mean()/x.std()*np.sqrt(len(x))
    print(R.groupby(['fill','dir']).ret.agg(n='size',mean=lambda x:x.mean()*100,t=t).round(2).to_string())
    print(R[R.fill=='base'].groupby(['half','dir']).ret.agg(n='size',mean=lambda x:x.mean()*100,t=t).round(2).to_string())
    print('held to close share', R[R.fill=='base'].held.mean().round(2))
