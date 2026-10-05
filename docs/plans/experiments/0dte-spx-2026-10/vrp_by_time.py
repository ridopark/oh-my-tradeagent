"""Gross seller P&L of a 0DTE SPXW ATM straddle (and 10-pt-OTM strangle) sold at time t at the trade print,
held to cash settlement (no exit cost). Gross = before spread/fees. Shows WHEN the premium is overpriced."""
import os, sys, pandas as pd, numpy as np
S=sys.argv[1]; os.environ['CACHE']=S; sys.path.insert(0,S); import engine as E
from concurrent.futures import ProcessPoolExecutor
TIMES=['09:35','10:00','11:00','12:00','13:00','14:00','15:00','15:30']
def f(d):
    try: day=E.Day(d)
    except Exception: return []
    out=[]
    for hm in TIMES:
        t=pd.Timestamp(f'{d} {hm}',tz='America/New_York'); S0=day.spx.at[t]; atm=round(S0/5)*5
        for lab,kc,kp in (('ATM straddle',atm,atm),('strangle +-10',int(np.ceil((S0+10)/5)*5),int(np.floor((S0-10)/5)*5))):
            c,p=day.px('C',kc,t),day.px('P',kp,t)
            if not (np.isfinite(c) and np.isfinite(p)): continue
            prem=c+p; X=day.settle; payoff=max(X-kc,0)+max(kp-X,0)
            out.append(dict(d=d,hm=hm,kind=lab,prem=prem,gross=(prem-payoff),gross_pct=(prem-payoff)/prem*100,
                            cost_pct=2*E.half(c,False)/prem*100 if lab else 0))
    return out
if __name__=='__main__':
    with ProcessPoolExecutor(8) as ex: R=pd.DataFrame([r for x in ex.map(f,sorted(E.ratio.index),chunksize=4) for r in x])
    CR={'2024-08-05','2025-04-03','2025-04-04','2025-04-07','2025-04-08','2025-04-09'}
    t=lambda x: x.mean()/x.std()*np.sqrt(len(x))
    g=R.groupby(['kind','hm']).agg(n=('gross','size'),prem=('prem','median'),seller_gross_pct=('gross_pct','mean'),
        t=('gross_pct',t),seller_win=('gross',lambda x:(x>0).mean()*100),entry_half_spread_pct=('cost_pct','mean'),worst_pct=('gross_pct','min'))
    g['ex_crash_pct']=R[~R.d.isin(CR)].groupby(['kind','hm']).gross_pct.mean()
    g['H1']=R[R.d<'2025-07-01'].groupby(['kind','hm']).gross_pct.mean(); g['H2']=R[R.d>='2025-07-01'].groupby(['kind','hm']).gross_pct.mean()
    pd.set_option('display.width',220); print(g.round(1).to_string())
