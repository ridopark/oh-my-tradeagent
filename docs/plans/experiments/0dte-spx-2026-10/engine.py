"""Multi-leg SPXW 0DTE replay engine on Alpaca 1-min TRADE-print bars.
Marks: last print forward-filled (stale <= 15 min, else leg 'unknown' -> skip stop check that minute).
SPX: SPY close x per-day parity ratio. IV: ATM straddle = 0.7979*sigma*S*sqrt(T).
Costs per leg per side: half-spread h(price) + $0.011 fees; h = 0.05 (<2), 0.10 (2-10), 0.20 (>10); stress = 2x.
Held to 16:00 -> cash-settled at intrinsic vs SPX close estimate, no exit spread.
"""
import pandas as pd, numpy as np, os
from scipy.stats import norm
S=os.environ.get('CACHE')
ratio=pd.read_csv(f'{S}/spx_ratio.csv',index_col=0).ratio
_spy=pd.read_parquet(f'{S}/spy_1m.parquet'); _spy=_spy[_spy.t>='2024-03-01']
_spy['d']=_spy.t.dt.date.astype(str); SPY={d:g.set_index('t') for d,g in _spy.groupby('d')}
FEE=0.011
def half(px,stress): return (0.05 if px<2 else 0.10 if px<10 else 0.20)*(2 if stress else 1)
class Day:
    def __init__(s,d):
        s.d=d; fs=[f'{S}/spxw/{d}.parquet',f'{S}/spxw2/{d}.parquet']
        o=pd.concat([pd.read_parquet(f) for f in fs if os.path.exists(f)])
        s.o=o; s.key=list(zip(o.cp,o.k))
        idx=pd.date_range(f'{d} 09:30',f'{d} 15:59',freq='1min',tz='America/New_York')
        s.idx=idx
        s.c=o.pivot_table(index='t',columns=['cp','k'],values='c').reindex(idx)
        s.op=o.pivot_table(index='t',columns=['cp','k'],values='o').reindex(idx)
        s.hi=o.pivot_table(index='t',columns=['cp','k'],values='h').reindex(idx)
        s.lo=o.pivot_table(index='t',columns=['cp','k'],values='l').reindex(idx)
        s.mark=s.c.ffill(limit=15)
        sp=SPY[d].c.reindex(idx).ffill(); s.spx=sp*ratio[d]
        s.settle=s.spx.iloc[-1]
    def px(s,cp,k,t):
        """fill reference at minute t: bar open if printed, else last close within 5 min."""
        if (cp,k) not in s.c.columns: return np.nan
        v=s.op.at[t,(cp,k)]
        if np.isnan(v): v=s.c[(cp,k)].loc[:t].ffill(limit=5).iloc[-1]
        return v
    def iv(s,t):
        S0=s.spx.at[t]; k=round(S0/5)*5; st=s.px('C',k,t)+s.px('P',k,t)
        T=max((s.idx[-1]-t).total_seconds()/60+1,1)/(390*252)
        return st/(0.7979*S0*np.sqrt(T)), st, k
    def strike_for_delta(s,cp,delta,t):
        sig,_,_=s.iv(s_t:=t); S0=s.spx.at[t]; T=max((s.idx[-1]-t).total_seconds()/60+1,1)/(390*252)
        ks=np.arange(round(S0/5)*5-150,round(S0/5)*5+155,5)
        d1=(np.log(S0/ks)+0.5*sig**2*T)/(sig*np.sqrt(T))
        dl=norm.cdf(d1) if cp=='C' else norm.cdf(d1)-1
        return int(ks[np.argmin(abs(abs(dl)-delta))])
def run_structure(day,t0,legs,stop_mult=None,target_frac=None,exit_hm=None,stress=False):
    """legs: list of (cp,k,qty) qty>0 long, <0 short. Returns dict with pnl in $ points per 1 lot and credit.
    stop_mult: exit when cost-to-close >= stop_mult*credit (credit structures) .
    target_frac: exit when cost-to-close <= (1-target_frac)*credit.
    """
    entry=0.0
    for cp,k,q in legs:
        p=day.px(cp,k,t0)
        if np.isnan(p) or p<=0: return None
        entry+= q*(p + np.sign(q)*(half(p,stress)+FEE))   # pay for longs, receive (negative) for shorts
    credit=-entry   # >0 for credit structures
    def close_cost(t,use='mark'):
        v=0.0
        for cp,k,q in legs:
            p=day.mark.at[t,(cp,k)] if (cp,k) in day.mark.columns else np.nan
            if np.isnan(p): return np.nan
            v+= -q*(p - np.sign(q)*(half(p,stress)+FEE))     # to close: sell longs at p-h, buy shorts at p+h
        return -v   # cost to close (>0 means pay)
    after=day.idx[day.idx>t0]
    for t in after:
        hm=t.strftime('%H:%M')
        if exit_hm and hm>=exit_hm:
            cc=close_cost(t)
            if not np.isnan(cc): return dict(pnl=credit-cc,credit=credit,exit='time',t=hm)
        cc=close_cost(t)
        if np.isnan(cc): continue
        if stop_mult is not None and cc>=stop_mult*credit: return dict(pnl=credit-cc,credit=credit,exit='stop',t=hm)
        if target_frac is not None and cc<=(1-target_frac)*credit: return dict(pnl=credit-cc,credit=credit,exit='target',t=hm)
    Sx=day.settle; val=sum(q*(max(Sx-k,0) if cp=='C' else max(k-Sx,0)) for cp,k,q in legs)
    return dict(pnl=credit+val,credit=credit,exit='settle',t='16:00')
