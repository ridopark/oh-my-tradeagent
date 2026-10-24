# Independent recompute of C1/C2 (numpy-only per-session arrays, own placebo variants).
import pandas as pd, numpy as np, datetime as dt, itertools
rng = np.random.default_rng(123)
B = pd.read_parquet('rth_1m.parquet')
B['m'] = B.t.dt.hour*60 + B.t.dt.minute - 570
print('tz', B.t.dt.tz, 'm range', B.m.min(), B.m.max(), 'bars/sess median', B.groupby(['s','d']).size().median())
S = {k: {c: g.sort_values('m')[c].values for c in 'ohlcm'} for k, g in B.groupby(['s','d'])}
days = {}
for (s, d) in S: days.setdefault(s, []).append(d)
for s in days: days[s].sort()
pc = {(s, b): S[(s, a)]['c'][-1] for s, ds in days.items() for a, b in zip(ds, ds[1:])}
L = pd.read_csv('legs_raw.psv', sep='|', header=None, names=['d','wf','ts','s','dir','lvl','mode','mult'])
L['d'] = pd.to_datetime(L.d).dt.date; L = L.drop_duplicates(['d','s','dir','lvl']).reset_index(drop=True)
SPLIT = dt.date(2026, 9, 1)

def ev(s, d, sg, lvl):
    g = S.get((s, d))
    if g is None or len(g['c']) < 300: return None
    c, h, l, m, o = g['c'], g['h'], g['l'], g['m'], g['o']
    gap = (o[0]-lvl)*sg > 0
    b = (c-lvl)*sg > 0
    if not b.any(): return dict(fired=0, gap=int(gap))
    i = int(np.argmax(b)); p0 = c[i]
    r = dict(fired=1, gap=int(gap), fm=int(m[i]), p0=p0, i=i)
    for H in (5, 15, 30, 60):
        j = np.searchsorted(m, m[i]+H)
        r[f'r{H}'] = sg*(c[j]-p0)/p0 if j < len(c) else np.nan
    k = np.searchsorted(m, 385, side='right')-1
    r['rclose'] = sg*(c[k]-p0)/p0
    return r

real = []
for x in L.itertuples():
    sg = 1 if x.dir == 'ABOVE' else -1
    r = ev(x.s, x.d, sg, x.lvl)
    if r is None or (x.s, x.d) not in pc: continue
    real.append(dict(s=x.s, d=x.d, dir=x.dir, sg=sg, lvl=x.lvl, off=x.lvl/pc[(x.s, x.d)]-1, **r))
R = pd.DataFrame(real); R['half'] = np.where(R.d >= SPLIT, 'TEST', 'FIT')
print('real legs evaluated', len(R), 'fired', R.fired.sum(), 'gap among all', R.gap.mean().round(3),
      'gap among fired', R[R.fired == 1].gap.mean().round(3))

def boot(df, col, Bn=2000):
    df = df.dropna(subset=[col]); g = df.groupby('d')[col].agg(['sum', 'count'])
    idx = rng.integers(0, len(g), (Bn, len(g)))
    v = g['sum'].values[idx].sum(1)/g['count'].values[idx].sum(1)
    return df[col].mean()*1e4, *(np.percentile(v, [2.5, 97.5])*1e4), len(df), len(g)

print('\nC1 REAL intraday cross (bps: mean lo hi n ndays)')
I = R[(R.fired == 1) & (R.gap == 0)]
for h in ['FIT', 'TEST']:
    for c in ['r5', 'r15', 'r30', 'r60', 'rclose']:
        print(h, c, np.round(boot(I[I.half == h], c), 1))
    for dr in ['ABOVE', 'BELOW']:
        print('  ', h, dr, 'r60', np.round(boot(I[(I.half == h) & (I.dir == dr)], 'r60'), 1))
print('fire minute distribution (intraday):', I.fm.describe()[['25%', '50%', '75%']].to_dict(),
      ' fired at bar0 (open on near side but first close beyond):', (I.fm == 0).sum())

# Placebo A: original (offset from prior close, 20 other same-half days)
# Placebo B: offset from same-day 9:30 OPEN applied on other days' open  (kills gap-rate mismatch)
# Placebo C: same-day, same ticker, shifted level: real level +/- k*0.5% (level-specificity test)
pl = []
for x in R.itertuples():
    hd = [z for z in days[x.s] if (z >= SPLIT) == (x.d >= SPLIT) and z != x.d and (x.s, z) in pc]
    o0 = S[(x.s, x.d)]['o'][0]; offo = x.lvl/o0-1
    for z in (rng.choice(hd, size=min(20, len(hd)), replace=False) if hd else []):
        for kind, lv in (('A_prevclose', pc[(x.s, z)]*(1+x.off)), ('B_open', S[(x.s, z)]['o'][0]*(1+offo))):
            r = ev(x.s, z, x.sg, lv)
            if r: pl.append(dict(kind=kind, s=x.s, d=z, dir=x.dir, **r))
    for k in (-3, -2, -1, 1, 2, 3):
        r = ev(x.s, x.d, x.sg, x.lvl*(1+k*.005))
        if r: pl.append(dict(kind='C_shift', s=x.s, d=x.d, dir=x.dir, **r))
P = pd.DataFrame(pl); P['half'] = np.where(P.d >= SPLIT, 'TEST', 'FIT')
print('placebo fired-gap share:', P[P.fired==1].groupby('kind').gap.mean().round(3).to_dict())
print('\nfire/gap rates')
print(pd.concat([R.assign(kind='real'), P]).groupby(['kind', 'half']).agg(n=('fired', 'size'), fire=('fired', 'mean'), gap=('gap', 'mean')).round(3))
print('\nPlacebo intraday r60 / rclose (bps)')
for kind in ['A_prevclose', 'B_open', 'C_shift']:
    for h in ['FIT', 'TEST']:
        x = P[(P.kind == kind) & (P.half == h) & (P.fired == 1) & (P.gap == 0)]
        print(kind, h, 'r60', np.round(boot(x, 'r60'), 1), 'rclose', np.round(boot(x, 'rclose'), 1))

# C1 power: day-clustered SE of r60 and rclose, MDE = 2.8*SE
for c in ['r60', 'rclose']:
    for h in ['FIT', 'TEST']:
        x = I[I.half == h].dropna(subset=[c]); g = x.groupby('d')[c].agg(['sum', 'count'])
        idx = rng.integers(0, len(g), (2000, len(g))); v = g['sum'].values[idx].sum(1)/g['count'].values[idx].sum(1)
        print('MDE80', c, h, 'n', len(x), 'days', len(g), 'leg sd bps', round(x[c].std()*1e4), 'clustered SE', round(v.std()*1e4, 1), 'MDE', round(2.8*v.std()*1e4, 1))

# C2: own 64-variant grid on real intraday crosses
COST = 2e-4
def sim(g, sg, i, ts, st, tg):
    c, h, l, m = g['c'], g['h'], g['l'], g['m']; p0 = c[i]; end = min(m[i]+ts, 385)
    last = p0
    for j in range(i+1, len(c)):
        if m[j] > end: break
        adv = sg*((l[j] if sg == 1 else h[j])-p0)/p0
        fav = sg*((h[j] if sg == 1 else l[j])-p0)/p0
        if st and adv <= -st: return -st-COST
        if tg and fav >= tg: return tg-COST
        last = c[j]
    return sg*(last-p0)/p0-COST
rows = []
for x in I.itertuples():
    g = S[(x.s, x.d)]
    for ts, st, tg in itertools.product([15, 30, 60, 385], [0, .0025, .005, .01], [0, .005, .01, .02]):
        rows.append((x.half, x.d, ts, st, tg, sim(g, x.sg, int(x.i), ts, st, tg)))
G = pd.DataFrame(rows, columns=['half', 'd', 'ts', 'st', 'tg', 'ret'])
pv = (G.groupby(['ts', 'st', 'tg', 'half']).ret.mean().unstack()*1e4).sort_values('FIT', ascending=False)
print('\nC2 variants', len(pv), 'both>0:', ((pv.FIT > 0) & (pv.TEST > 0)).sum(), ' FIT>0:', (pv.FIT > 0).sum(), ' TEST>0:', (pv.TEST > 0).sum())
print(pv.head(5).round(1)); print('best TEST:', pv.TEST.max().round(1))
G.to_parquet('review/my_grid.parquet'); R.to_parquet('review/my_real.parquet'); P.to_parquet('review/my_placebo.parquet')
