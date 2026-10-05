"""T5 (EXPLORATORY, n too small for any pass bar): buy SPXW ATM straddle 13:00 on FOMC statement days,
exit at 14:35 or 15:30 marks. Control: same trade on the Wednesday one week before each FOMC day."""
import sys, os, pandas as pd, numpy as np
S=sys.argv[1]; os.environ['CACHE']=S; sys.path.insert(0,S); import engine as E
fomc=[d for d in open(f'{S}/fomc_dates.txt').read().split() if d in set(pd.read_csv(f'{S}/spx_ratio.csv',index_col=0).index)]
ctrl=[str((pd.Timestamp(d)-pd.Timedelta(days=7)).date()) for d in fomc]
rows=[]
for grp,days in (('FOMC',fomc),('ctrl-1wk',ctrl)):
    for d in days:
        try: day=E.Day(d)
        except Exception: continue
        t=pd.Timestamp(f'{d} 13:00',tz='America/New_York')
        try: iv,st,atm=day.iv(t)
        except Exception: continue
        c0,p0=day.px('C',atm,t),day.px('P',atm,t)
        if not (np.isfinite(c0) and np.isfinite(p0)): continue
        for xhm in ('14:35','15:30'):
            tx=pd.Timestamp(f'{d} {xhm}',tz='America/New_York')
            cx=day.mark.at[tx,('C',atm)] if ('C',atm) in day.mark.columns else np.nan
            px=day.mark.at[tx,('P',atm)] if ('P',atm) in day.mark.columns else np.nan
            if not (np.isfinite(cx) and np.isfinite(px)): continue
            for stress in (False,True):
                h=lambda p: E.half(p,stress)+E.FEE
                cost=c0+h(c0)+p0+h(p0); back=max(cx-h(cx),0)+max(px-h(px),0)
                rows.append(dict(grp=grp,d=d,exit=xhm,fill='stress' if stress else 'base',ret=(back-cost)/cost))
R=pd.DataFrame(rows)
print(R.groupby(['grp','exit','fill']).ret.agg(n='size',mean=lambda x:x.mean()*100,med=lambda x:x.median()*100,win=lambda x:(x>0).mean()*100,worst=lambda x:x.min()*100,best=lambda x:x.max()*100).round(1).to_string())
