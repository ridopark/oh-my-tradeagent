"""Golden fixtures for CondorMarketActivity (Phase 2). Reproduces t4_iv_gate.richness_etf('SPY') for chosen
days, the spx_level.py parity spot on the same day's 14:00 bar closes, and sell_afternoon S2 condor strikes."""
import sys, math, pandas as pd, numpy as np
S=sys.argv[1]; OUT=sys.argv[2]; DAYS=sys.argv[3:]
raw=pd.read_parquet(f'{S}/etfopt/SPY_raw_1m.parquet'); raw['d']=raw.t.dt.date.astype(str)
up=lambda x: math.ceil(x-1e-9); dn=lambda x: math.floor(x+1e-9)  # $1 grid
rows=[]
for d in DAYS:
    g=raw[raw.d==d].set_index('t'); t=pd.Timestamp(f'{d} 14:00',tz='America/New_York')
    o=pd.read_parquet(f'{S}/etfopt/SPY/{d}.parquet')
    m=g.loc[:t]; spot=g.at[t,'o']; sig=np.log(m.c).diff().dropna().std()
    atm=round(spot); piv=o[(o.k==atm)&(o.t<=t)]; st=0
    for cp in 'CP':
        x=piv[piv.cp==cp].sort_values('t'); assert (t-x.t.iloc[-1]).total_seconds()<=900; st+=x.c.iloc[-1]
    rich=st/(0.7979*sig*np.sqrt(120)*spot)
    with open(f'{OUT}/{d}-spy-closes.csv','w') as f:
        f.write('hhmm,c\n')
        for ts,c in zip(m.index,m.c): f.write(f'{ts:%H:%M},{c!r}\n')
    # parity: last close <= t per (cp,k) for strikes atm+-5, S = K + C - P at min |C-P|
    w={}
    for k in range(atm-5,atm+6):
        cp_={}
        for cp in 'CP':
            x=o[(o.k==k)&(o.cp==cp)&(o.t<=t)].sort_values('t')
            if not x.empty: cp_[cp]=x.c.iloc[-1]
        if len(cp_)==2: w[k]=cp_
    with open(f'{OUT}/{d}-parity.csv','w') as f:
        f.write('k,c,p\n')
        for k,v in w.items(): f.write(f'{k},{float(v["C"])!r},{float(v["P"])!r}\n')
    kbest=min(w,key=lambda k:abs(w[k]['C']-w[k]['P'])); ps=kbest+w[kbest]['C']-w[kbest]['P']
    rows.append(dict(day=d,spot=repr(float(spot)),straddle=repr(float(st)),richness=repr(float(rich)),parity_spot=repr(float(ps)),
        short_call=up(spot*1.0015),short_put=dn(spot*0.9985),long_call=up(spot*1.006),long_put=dn(spot*0.994)))
pd.DataFrame(rows).to_csv(f'{OUT}/golden.csv',index=False); print(pd.DataFrame(rows).to_string())
