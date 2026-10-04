"""Candidate 6 base: Zarattini/Aziz/Barbon 'Beat the Market' noise-area intraday momentum on SPY.
Rules (paper): sigma(t) = mean over prior 14 days of |close(t)/open - 1| at the same minute.
UB = max(open, prev_close)*(1+sigma), LB = min(open, prev_close)*(1-sigma). Decisions at HH:00/HH:30
from 10:00. Long if close > UB, short if < LB. Exit/flip when price crosses trailing stop:
long stop = max(UB, VWAP), short stop = min(LB, VWAP), checked at the same half-hour marks. Flat at 15:59 close.
Unit size (no vol sizing). Cost 0.5 bp per side. One observation = one day.
Paper sample ended early 2024 -> 2024-05..2026-10 is post-publication."""
import sys, pandas as pd, numpy as np
S=sys.argv[1]
b=pd.read_parquet(f'{S}/spy_1m.parquet'); b['d']=b.t.dt.date; b['hm']=b.t.dt.strftime('%H:%M')
C=b.pivot_table(index='d',columns='hm',values='c'); O=b.pivot_table(index='d',columns='hm',values='o')
V=b.pivot_table(index='d',columns='hm',values='v'); VW=b.pivot_table(index='d',columns='hm',values='vw')
C=C.ffill(axis=1); op=O['09:30']; pc=C['15:59'].shift(1)
mv=(C.div(op,axis=0)-1).abs(); sig=mv.rolling(14).mean().shift(1)
vwap=(VW*V).cumsum(axis=1)/V.cumsum(axis=1)
marks=[m for m in C.columns if m>='10:00' and m<'16:00' and m[3:] in('00','30')]
UB=sig.mul(np.maximum(op,pc),axis=0)+np.maximum(op,pc).values[:,None]; LB=np.minimum(op,pc).values[:,None]-sig.mul(np.minimum(op,pc),axis=0)
out=[]
for d in C.index[15:]:
    if C.loc[d].isna().sum()>30 or np.isnan(pc[d]): continue
    pos=0; pnl=0.0; ent=None; trades=0; ev=[]
    for m in marks:
        p=C.at[d,m]; u=UB.at[d,m]; l=LB.at[d,m]; vw=vwap.at[d,m]
        if pos==1 and p<max(u,vw): pnl+=p/ent-1-1e-4; pos=0; ev[-1][2]=m
        elif pos==-1 and p>min(l,vw): pnl+=1-p/ent-1e-4; pos=0; ev[-1][2]=m
        if pos==0:
            if p>u: pos=1; ent=p; trades+=1; ev.append([m,1,None])
            elif p<l: pos=-1; ent=p; trades+=1; ev.append([m,-1,None])
    p=C.at[d,'15:59']
    if pos==1: pnl+=p/ent-1-1e-4
    if pos==-1: pnl+=1-p/ent-1e-4
    out.append(dict(d=d,pnl=pnl*1e4,trades=trades,ev=[(a,b_,c) for a,b_,c in ev]))
R=pd.DataFrame(out); R['y']=pd.to_datetime(R.d).dt.year
import pickle; pickle.dump(R,open(f'{S}/noise_area.pkl','wb'))
def st(x): return pd.Series(dict(days=len(x),mean_bp=x.mean(),t=x.mean()/x.std()*np.sqrt(len(x)),sharpe=x.mean()/x.std()*np.sqrt(252)))
print(R.groupby('y').pnl.apply(st).unstack().round(2).to_string())
for lab,m in [('2016-2020',R.y<=2020),('2021-2023',(R.y>=2021)&(R.y<=2023)),('PUBLISHED-ERA 2016-2024-04',R.d<pd.Timestamp('2024-05-01').date()),('POST-PUB 2024-05..',R.d>=pd.Timestamp('2024-05-01').date())]:
    print(lab, st(R.pnl[m]).round(2).to_dict())
