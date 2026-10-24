import pandas as pd, numpy as np, itertools
rng = np.random.default_rng(9)
R = pd.read_parquet('review/my_real.parquet'); P = pd.read_parquet('review/my_placebo.parquet')
E = pd.read_parquet('events.parquet'); G0 = pd.read_parquet('grid.parquet')
print('THEIR events: gap among fired by kind/half'); print(E[E.fired == 1].groupby(['kind', 'half']).gap.mean().round(3))
print('THEIR events: gap among all'); print(E.groupby(['kind', 'half']).gap.mean().round(3))
print('MY placebo gap among fired', P[P.fired == 1].groupby('kind').gap.mean().round(3).to_dict())
e = E[(E.kind == 'real') & (E.fired == 1) & (E.gap == 0)]
print('their real intraday r60 FIT/TEST', (e.groupby('half').r60.mean()*1e4).round(1).to_dict(), 'n', e.groupby('half').size().to_dict())
pv = (G0.groupby(['kind', 'half', 'ts', 'st', 'tg']).ret.mean().unstack(['kind', 'half'])*1e4).sort_values(('real', 'FIT'), ascending=False)
print('their grid best-on-FIT', pv.index[0], pv.iloc[0].round(1).to_dict())
# MFE/MAE sign check from their events for BELOW legs: MFE>=0 >= MAE expected
for dr in ['ABOVE', 'BELOW']:
    x = E[(E.fired == 1) & (E.dir == dr)]
    print(dr, 'mfe<0:', (x.mfe < -1e-12).sum(), 'mae>0:', (x.mae > 1e-12).sum(), 'n', len(x), 'mfe NaN', x.mfe.isna().sum())

# real - placebo difference with independent day bootstraps
def bdist(df, col, B=4000):
    df = df.dropna(subset=[col]); g = df.groupby('d')[col].agg(['sum', 'count'])
    idx = rng.integers(0, len(g), (B, len(g))); return g['sum'].values[idx].sum(1)/g['count'].values[idx].sum(1)
I = R[(R.fired == 1) & (R.gap == 0)]
for kind in ['A_prevclose', 'B_open', 'C_shift']:
    for h in ['FIT', 'TEST']:
        out = []
        for c in ['r5', 'r60', 'rclose']:
            dd = (bdist(I[I.half == h], c) - bdist(P[(P.kind == kind) & (P.half == h) & (P.fired == 1) & (P.gap == 0)], c))*1e4
            out.append(f'{c}: {dd.mean():+.1f} [{np.percentile(dd, 2.5):+.1f},{np.percentile(dd, 97.5):+.1f}]')
        print('real-minus', kind, h, ' | '.join(out))

# gap-legs and fire-time composition: real vs placebo B
print('fire-minute median real', I.fm.median(), ' placeboB', P[(P.kind == 'B_open') & (P.fired == 1) & (P.gap == 0)].fm.median(),
      ' placeboA', P[(P.kind == 'A_prevclose') & (P.fired == 1) & (P.gap == 0)].fm.median())

# same-bar ordering: target-first optimistic bound on my grid
B = pd.read_parquet('rth_1m.parquet'); B['m'] = B.t.dt.hour*60 + B.t.dt.minute - 570
S = {k: {c: g.sort_values('m')[c].values for c in 'ohlcm'} for k, g in B.groupby(['s', 'd'])}
COST = 2e-4
def sim(g, sg, i, ts, st, tg):
    c, h, l, m = g['c'], g['h'], g['l'], g['m']; p0 = c[i]; end = min(m[i]+ts, 385); last = p0
    for j in range(i+1, len(c)):
        if m[j] > end: break
        adv = sg*((l[j] if sg == 1 else h[j])-p0)/p0; fav = sg*((h[j] if sg == 1 else l[j])-p0)/p0
        if tg and fav >= tg: return tg-COST
        if st and adv <= -st: return -st-COST
        last = c[j]
    return sg*(last-p0)/p0-COST
rows = []
for x in I.itertuples():
    for ts, st, tg in itertools.product([15, 30, 60, 385], [0, .0025, .005, .01], [0, .005, .01, .02]):
        rows.append((x.half, ts, st, tg, sim(S[(x.s, x.d)], x.sg, int(x.i), ts, st, tg)))
G = pd.DataFrame(rows, columns=['half', 'ts', 'st', 'tg', 'ret'])
pv = G.groupby(['ts', 'st', 'tg', 'half']).ret.mean().unstack()*1e4
print('TARGET-FIRST grid: both>0', ((pv.FIT > 0) & (pv.TEST > 0)).sum(), ' max FIT', pv.FIT.max().round(1), ' max TEST', pv.TEST.max().round(1))
print('gross (no cost) best variant pair:', ((pv+2).min(axis=1)).max().round(1))
