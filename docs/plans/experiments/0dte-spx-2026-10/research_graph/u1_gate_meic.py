"""U1 (pre-registered in graph.json): IV-richness gate x MEIC.
Gate: SPX richness at 12:00 (first MEIC entry) >= trailing-60d median, no lookahead.
Joined onto round-3 per-day MEIC results (C1A 12:00-14:30 clock, per-side stop = total credit), SPXW.
Report gated/ungated-rest/anti x {base, stress, frictionless}. Dataset variants_charged ~230 -> demand t>=3
or treat as confirmation-only."""
import sys, os, pandas as pd, numpy as np
S=sys.argv[1]; os.environ['CACHE']=S; sys.path.insert(0,S); import engine as E
spy=pd.read_parquet(f'{S}/spy_1m.parquet'); spy=spy[spy.t>='2024-03-01']; spy['d']=spy.t.dt.date.astype(str)
rich={}
for d in sorted(E.ratio.index):
    try: day=E.Day(d); t=pd.Timestamp(f'{d} 12:00',tz='America/New_York'); iv,st,atm=day.iv(t)
    except Exception: continue
    m=spy[(spy.d==d)&(spy.t<=t)]
    if len(m)<120 or not np.isfinite(st): continue
    sig=np.log(m.c).diff().dropna().std(); rem=240.0
    if sig>0: rich[d]=st/(0.7979*sig*np.sqrt(rem)*day.spx.at[t])
rich=pd.Series(rich).sort_index()
med=rich.rolling(61).apply(lambda w:np.median(w[:-1]),raw=True); gate=(rich>=med).dropna()
R=pd.read_parquet(f'{S}/strategies.parquet'); R=R[R.strat=='C1A MEIC 12:00-14:30']
F=pd.read_parquet(f'{S}/strategies_frictionless.parquet'); F=F[F.strat=='C1A MEIC 12:00-14:30']
A=pd.concat([R[R.fill.isin(['base','stress'])],F])
A=A.merge(gate.rename('g'),left_on='d',right_index=True)
tt=lambda x: x.mean()/x.std()*np.sqrt(len(x))
rows=[]
for f in ('frictionless','base','stress'):
    for lab,sub in (('GATED',A[(A.fill==f)&A.g]),('anti',A[(A.fill==f)&~A.g]),('ungated-all',A[A.fill==f])):
        x=sub.groupby('d').ret.sum()
        rows.append(dict(fill=f,gate=lab,n=len(x),mean=x.mean()*100,t=tt(x),win=(x>0).mean()*100,
            H1=x[x.index<'2025-07-01'].mean()*100,H2=x[x.index>='2025-07-01'].mean()*100))
print(pd.DataFrame(rows).round(2).to_string(index=False))
