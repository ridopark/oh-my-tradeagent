import pandas as pd, numpy as np, datetime as dt, itertools
rng = np.random.default_rng(17)
SPLIT = dt.date(2026, 9, 1)
B = pd.read_parquet('rth_1m.parquet'); B['m'] = B.t.dt.hour*60 + B.t.dt.minute - 570
S = {k: {c: g.sort_values('m')[c].values for c in 'ohlcm'} for k, g in B.groupby(['s', 'd'])}
days = {}
for (s, d) in S: days.setdefault(s, []).append(d)
for s in days: days[s].sort()
nxt = {(s, a): b for s, ds in days.items() for a, b in zip(ds, ds[1:])}
alld = sorted({d for (_, d) in S})
L = pd.read_csv('legs_raw.psv', sep='|', header=None, names=['d','wf','ts','s','dir','lvl','mode','mult'])
L['d'] = pd.to_datetime(L.d).dt.date; L = L.drop_duplicates(['d','s','dir','lvl']).reset_index(drop=True)

# --- independent e_gap: open beyond level, enter 9:35 close, exit next session close
rows = []
for x in L.itertuples():
    g = S.get((x.s, x.d))
    if g is None or len(g['c']) < 300: continue
    sg = 1 if x.dir == 'ABOVE' else -1
    if (g['o'][0]-x.lvl)*sg <= 0: continue
    nd = nxt.get((x.s, x.d))
    k = np.searchsorted(g['m'], 5); p0 = g['c'][k]
    skipped = nd is not None and alld.index(nd) - alld.index(x.d) != 1
    rows.append(dict(s=x.s, d=x.d, dir=x.dir, lvl=x.lvl, o=g['o'][0], p0=p0, gap_bps=round(sg*(g['o'][0]/x.lvl-1)*1e4),
                     nd=nd, skipped_day=skipped,
                     r_nc=(sg*(S[(x.s, nd)]['c'][-1]-p0)/p0-2e-4)*1e4 if nd else np.nan,
                     r_close=(sg*(g['c'][-1]-p0)/p0-2e-4)*1e4))
E = pd.DataFrame(rows); E['half'] = np.where(E.d >= SPLIT, 'TEST', 'FIT')
pd.set_option('display.width', 200)
print(E.groupby('half')[['r_nc', 'r_close']].agg(['size', 'mean', 'median']).round(1))
print('distinct days per half', E.groupby('half').d.nunique().to_dict(), ' tickers', E.groupby('half').s.nunique().to_dict())
for h in ['FIT', 'TEST', 'ALL']:
    x = E if h == 'ALL' else E[E.half == h]; v = x.r_nc.dropna().values
    # exact sign-flip test (H0 symmetric around 0)
    n = len(v); flips = np.array(list(itertools.product([1, -1], repeat=n))) if n <= 16 else rng.choice([1, -1], (200000, n))
    p_sf = ((flips*v).mean(1) >= v.mean()).mean()
    t = v.mean()/(v.std(ddof=1)/np.sqrt(n))
    print(h, 'n', n, 'mean', v.mean().round(1), 'sd', v.std(ddof=1).round(1), 't', round(t, 2), 'sign-flip p(one-sided)', round(p_sf, 4),
          'pos', (v > 0).sum(), ' leave-one-out min mean', round(min(np.delete(v, i).mean() for i in range(n)), 1))
# baseline: same tickers' unconditional 9:35->next close drift in same period (long for ABOVE, short for BELOW)
base = []
for x in E.itertuples():
    sg = 1 if x.dir == 'ABOVE' else -1
    for d in days[x.s]:
        nd = nxt.get((x.s, d)); g = S[(x.s, d)]
        if nd is None or len(g['c']) < 300: continue
        k = np.searchsorted(g['m'], 5); base.append((x.half, sg*(S[(x.s, nd)]['c'][-1]/g['c'][k]-1)*1e4))
base = pd.DataFrame(base, columns=['half', 'r']); print('baseline same-ticker same-sign 9:35->next close (bps)', base.groupby('half').r.agg(['mean', 'std']).round(1).to_dict())

# --- vol-filter look-ahead in H-a: entries before minute 30 use the opening-30m range
from_vol = {}
for k, g in S.items():
    w = g['m'] < 30
    from_vol[k] = (g['h'][w].max()-g['l'][w].min())/g['o'][0] if w.any() else np.nan
r = pd.Series(from_vol); r.index = pd.MultiIndex.from_tuples(r.index); r = r.sort_index()
ratio = r/r.groupby(level=0).transform(lambda x: x.shift(1).rolling(20, min_periods=5).mean())
print('vol ratio >1.2 share (prereg said top tercile):', round((ratio > 1.2).mean(), 3), ' 2/3 quantile:', round(ratio.quantile(2/3), 3))
fires = []
for x in L.itertuples():
    g = S.get((x.s, x.d))
    if g is None or len(g['c']) < 300: continue
    sg = 1 if x.dir == 'ABOVE' else -1
    if (g['o'][0]-x.lvl)*sg > 0: continue
    b = (g['c']-x.lvl)*sg > 0
    if b.any() and ratio.get((x.s, x.d), 0) > 1.2:
        fires.append(int(g['m'][np.argmax(b)]))
fires = np.array(fires); print('H-a entries:', len(fires), ' entered before 10:00 (vol filter not yet known):', (fires < 30).sum(), f'({(fires < 30).mean():.0%})')

# --- RC recompute: White (as theirs) vs Hansen-style studentized, on their hyp2026_all
R = pd.read_parquet('hyp2026_all.parquet')
R['v'] = R.hyp+'|st'+R.st.astype(str)+'|tg'+R.tg.astype(str)+'|ts'+R.ts.astype(str)
T = R[R.half == 'TEST']
Pm = T.pivot_table(index='d', columns='v', values='ret', aggfunc='sum').fillna(0)
Nm = T.pivot_table(index='d', columns='v', values='ret', aggfunc='count').fillna(0)
mu = Pm.sum()/Nm.sum(); Bn = 4000
idx = rng.integers(0, len(Pm), (Bn, len(Pm)))
mb = np.stack([Pm.values[i].sum(0)/np.maximum(Nm.values[i].sum(0), 1) for i in idx])
se = mb.std(0)
white = (mb - mu.values).max(1)
stud = ((mb - mu.values)/se).max(1)
out = pd.DataFrame({'mu_bps': mu*1e4, 'n': Nm.sum(), 'se_bps': se*1e4,
                    'p_white': [(white >= m).mean() for m in mu.values],
                    'p_stud': [(stud >= m/s).mean() for m, s in zip(mu.values, se)],
                    'p_naive': [((mb[:, j]-mu.values[j]) >= mu.values[j]).mean() for j in range(len(mu))]})
out['overnight'] = out.index.str.contains('next_')
print('\nRC on TEST (n variants', len(out), ')'); print(out.sort_values('p_stud').head(8).round(3).to_string())
print('min p_white intraday', out[~out.overnight].p_white.min().round(3), ' min p_stud intraday', out[~out.overnight].p_stud.min().round(3))
# White RC restricted to intraday variants only (no n=7 overnight variance hogs)
intr = ~out.overnight.values
w2 = (mb[:, intr]-mu.values[intr]).max(1); print('White RC intraday-only family: min p', round(min((w2 >= m).mean() for m in mu.values[intr]), 3))
print('which variant drives White max:', pd.Series(mu.index[(mb-mu.values).argmax(1)]).value_counts().head(3).to_dict())
