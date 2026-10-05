"""A3: score = #{strength lo, rng_rel hi, minutes_after hi, gap_size hi}, thresholds = FIT (2016-20) terciles.
Also: FADE on gap_size lo (trade opposite the breakout). Outcomes X15/X20/X30 + ret60."""
import sys, pandas as pd, numpy as np
S=sys.argv[1]; R=pd.read_parquet(f'{S}/orb30_feat.parquet')
q=lambda c,p: R.loc[R.fit,c].quantile(p)
R['score']=(R.strength<=q('strength',1/3)).astype(int)+(R.rng_rel>=q('rng_rel',2/3)).astype(int)+(R.minutes_after>=q('minutes_after',2/3)).astype(int)+(R.gap_size>=q('gap_size',2/3)).astype(int)
print('thresholds:',{c:round(q(c,p),3) for c,p in [('strength',1/3),('rng_rel',2/3),('minutes_after',2/3),('gap_size',2/3),('gap_size',1/3)]})
g=R.groupby(['score','fit']).agg(n=('X20','size'),X15=('X15','mean'),X20=('X20','mean'),X30=('X30','mean'),ret60=('ret60','mean')).unstack('fit')
g.columns=[f"{a}_{'FIT' if b else 'TEST'}" for a,b in g.columns]
for c in g.columns:
    if c[:1]=='X': g[c]*=100
print(g.round(1).to_string())
fade=R[R.gap_size<=q('gap_size',1/3)]
print('\nFADE small-gap (hit = breakout FAILS = 1-X):')
print(fade.groupby('fit').agg(n=('X20','size'),X15=('X15',lambda x:(1-x).mean()*100),X20=('X20',lambda x:(1-x).mean()*100),X30=('X30',lambda x:(1-x).mean()*100),ret60=('ret60',lambda x:-x.mean())).round(1).to_string())
R.to_parquet(f'{S}/orb30_feat.parquet')
