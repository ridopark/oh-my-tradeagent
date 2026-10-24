import json, glob, pandas as pd, numpy as np
# Roll effective-spread estimate from option 1-min trade-bar closes (crude; no quotes available)
rows = []
for f in glob.glob('author/opt/*.json'):
    b = list((json.load(open(f)).get('bars') or {}).values())
    if not b or len(b[0]) < 30: continue
    c = pd.Series([x['c'] for x in b[0]]); dp = c.diff().dropna()
    cov = np.cov(dp[1:], dp[:-1])[0, 1]
    tick_rel = 0.01/c.median()
    rows.append(dict(f=f.split('/')[-1], px=c.median(), roll_rel=(2*np.sqrt(-cov)/c.median()) if cov < 0 else np.nan, tick_rel=tick_rel))
X = pd.DataFrame(rows)
print('contract-days', len(X), ' median premium', X.px.median().round(2))
print('one-tick spread as % premium (median):', (X.tick_rel.median()*1e4).round(0), 'bps')
print('Roll spread % premium: median', (X.roll_rel.median()*1e4).round(0), 'bps  IQR', (X.roll_rel.quantile([.25, .75])*1e4).round(0).to_dict(), ' NaN(pos cov)', X.roll_rel.isna().sum())

# decade (generic PMH/PML) results available
for f in sorted(glob.glob('decade/hyp_*.parquet')):
    R = pd.read_parquet(f)
    g = R[(R.hyp == 'e_gap') & (R.ts.astype(str) == 'next_close')]
    a = R[(R.hyp == 'a_break_vol') & (R.ts.astype(str) == '60') & (R.st == .005) & (R.tg == .01)]
    print(f, 'legs-days', R.d.nunique(), '| e_gap next_close n/mean bps by half', (g.groupby('half').ret.agg(['size', 'mean'])*[1, 1e4]).round(1).to_dict(),
          '| a_break_vol st.5 tg1 ts60', (a.groupby('half').ret.agg(['size', 'mean'])*[1, 1e4]).round(1).to_dict())
