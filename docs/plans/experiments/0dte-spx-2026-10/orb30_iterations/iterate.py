"""Iteration harness. DEV = 2024-03-01..2026-03-31; HOLDOUT = 2026-04-01.. (locked; only via --holdout).
Success: win>=55%, stress mean>0, dev t>=2 (base), both dev halves >0; then same win/mean in holdout."""
import os, sys, json, pandas as pd, numpy as np
S=os.environ['CACHE']; sys.argv=[sys.argv[0],S]+sys.argv[1:]
exec(open(f'{S}/strategies.py').read().split("if __name__")[0])
from concurrent.futures import ProcessPoolExecutor
HOLD='2026-04-01'
LOG=f'{S}/iterations.log'
def evaluate(name, fn, holdout=False):
    days=[d for d in sorted(E.ratio.index) if (d>=HOLD)==holdout]
    with ProcessPoolExecutor(8) as ex: rows=[r for x in ex.map(fn,days,chunksize=4) for r in x]
    R=pd.DataFrame(rows)
    if R.empty: print(name,'no trades'); return R
    out=[]
    mid=sorted(R.d.unique())[len(R.d.unique())//2]
    for (v,f),g in R.groupby(['variant','fill']):
        x=g.groupby('d').pnl_r.sum()   # one obs per day
        win=(g.groupby('d').pnl.sum()>0).mean()*100
        out.append(dict(variant=v,fill=f,days=len(x),win=win,mean=x.mean()*100,t=x.mean()/x.std()*np.sqrt(len(x)),
                        h1=x[x.index<mid].mean()*100,h2=x[x.index>=mid].mean()*100,worst=g.groupby('d').pnl.sum().min(),total_pts=g.pnl.sum()))
    O=pd.DataFrame(out).round(2); pd.set_option('display.width',250)
    hdr=f"\n##### {name} {'HOLDOUT' if holdout else 'DEV'}"
    print(hdr); print(O.to_string(index=False))
    open(LOG,'a').write(hdr+'\n'+O.to_string(index=False)+'\n')
    return R
def spread(day,t,cp,short_k,width,mode,stress):
    long_k=short_k+width if cp=='C' else short_k-width
    return Sim(day,mode,stress).run([dict(short=(cp,short_k),long=(cp,long_k))],t)
FILLS=(('base',False,'base'),('base',True,'stress'))
