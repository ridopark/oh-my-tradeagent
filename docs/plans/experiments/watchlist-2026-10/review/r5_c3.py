import pandas as pd, numpy as np
rng = np.random.default_rng(3)
X = pd.read_parquet('levels_features.parquet')
X['off_pm'] = X.lvl/X.PMC-1
print('n', len(X), ' |lvl/PMC-1|>10%:'); print(X[X.off_pm.abs() > .10][['d', 's', 'dir', 'lvl', 'PMC', 'PDC']].to_string())
print('wrong side of PMC at post (ABOVE lvl<PMC or BELOW lvl>PMC):', (X.dist_pm < 0).sum())
cands = ['PDH', 'PDL', 'PDC', 'PMH', 'PML', 'PWH', 'PWL']
tol = .0015
real_any = (X[['d_'+c for c in cands]].min(axis=1) <= tol).mean()
print('real ANY within 0.15%:', round(real_any, 3), {c: round((X['d_'+c] <= tol).mean(), 3) for c in cands})
# placebo: same reference frame, level replaced by PMC*(1+off) with off drawn from real offsets of same dir (shuffled)
pl = []
for _ in range(50):
    for dr in ['ABOVE', 'BELOW']:
        Y = X[X.dir == dr].copy(); Y['lvl'] = Y.PMC.values*(1+rng.permutation(Y.off_pm.values))
        pl.append((Y[cands].rdiv(Y.lvl, axis=0)-1).abs().min(axis=1) <= tol)
print('placebo (shuffled offsets) ANY within 0.15%:', round(pd.concat(pl).mean(), 3))
# placebo matched on roundness: shuffled offsets, then snapped to the same round step as real level
pl = []
for _ in range(50):
    for dr in ['ABOVE', 'BELOW']:
        Y = X[X.dir == dr].copy(); st = np.where(Y.lvl % 1 == 0, 1.0, np.where((Y.lvl*2) % 1 == 0, .5, .01))
        Y['lvl'] = np.round(Y.PMC.values*(1+rng.permutation(Y.off_pm.values))/st)*st
        pl.append((Y[cands].rdiv(Y.lvl, axis=0)-1).abs().min(axis=1) <= tol)
print('placebo (shuffled offsets, round-snapped) ANY within 0.15%:', round(pd.concat(pl).mean(), 3))

# simple rule family the original did not test: round-number ladders from PMC
def step_for(p):
    return 0.5 if p < 50 else 1.0 if p < 200 else 2.5 if p < 500 else 5.0
def rules(r, k):
    sg = 1 if r.dir == 'ABOVE' else -1; st = step_for(r.PMC); ref = r.PMC
    base = np.ceil(ref/st)*st if sg == 1 else np.floor(ref/st)*st
    return base + sg*k*st
for k in range(0, 3):
    hit = [abs(rules(r, k)/r.lvl-1) <= tol for r in X.itertuples()]
    print(f'rule: {k}-th round step beyond PMC (step by price) hit rate', round(np.mean(hit), 3))
# best of (any standard level, or round levels) - simple union
for steps in [(1.0,), (0.5, 1.0)]:
    hit = []
    for r in X.itertuples():
        sg = 1 if r.dir == 'ABOVE' else -1
        st = min(steps) if r.PMC < 100 else max(steps)
        nxt_round = np.ceil(r.PMC/st)*st if sg == 1 else np.floor(r.PMC/st)*st
        hit.append(abs(nxt_round/r.lvl-1) <= tol)
    print('nearest round beyond PMC, steps', steps, round(np.mean(hit), 3))
print('dist from PMC in bps (signed toward level) quantiles', (X.dist_pm.quantile([.1, .25, .5, .75, .9])*1e4).round(0).to_dict())
print('dist in bps by price bucket median', X.groupby(pd.cut(X.PMC, [0, 50, 200, 500, 1e5])).dist_pm.median().mul(1e4).round(0).to_dict())
