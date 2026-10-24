# Does the H-a +17bps on decade NVDA survive removing the vol-filter look-ahead?
import sys, datetime as dt, pandas as pd, numpy as np
sys.path.insert(0, '.')
from hyp import barrier, volok
rng = np.random.default_rng(1)
for sym in sys.argv[1].split(','):
    df = pd.read_json(f'decade/{sym}.jsonl.gz', lines=True, compression='gzip')
    df['t'] = pd.to_datetime(df.t, utc=True).dt.tz_convert('America/New_York'); df['d'] = df.t.dt.date
    mm = df.t.dt.hour*60 + df.t.dt.minute
    pm = df[(mm >= 240) & (mm < 570)].groupby('d').agg(PMH=('h', 'max'), PML=('l', 'min'), n=('c', 'size'))
    pm = pm[(pm.n >= 10) & (pm.PMH > pm.PML)]
    rth = df[(mm >= 570) & (mm < 960)].copy(); rth['m'] = mm[(mm >= 570) & (mm < 960)] - 570
    sess = {(sym, d): g.sort_values('m').reset_index(drop=True) for d, g in rth.groupby('d')}
    vo = volok(sess)
    rows = []
    for d, p in pm.iterrows():
        if d >= dt.date(2026, 1, 1): continue
        g = sess.get((sym, d))
        if g is None or len(g) < 300: continue
        o, h, l, c, m = (g[k].values for k in 'ohlcm')
        for sg, lvl in ((1, p.PMH), (-1, p.PML)):
            if (o[0]-lvl)*sg > 0: continue
            b = (c-lvl)*sg > 0
            if not b.any(): continue
            i = int(np.argmax(b))
            if i >= len(c)-1: continue
            rows.append(dict(d=d, sg=sg, fm=int(m[i]), vol=vo.get((sym, d), False),
                             ret=barrier(h, l, c, m, i, sg, .005, .01, 60)))
    X = pd.DataFrame(rows); X['half'] = np.where(X.d >= dt.date(2022, 1, 1), 'TEST', 'FIT'); X['early'] = X.fm < 30
    out = X.groupby(['vol', 'early', 'half']).ret.agg(['size', 'mean']); out['mean'] *= 1e4
    print(sym, 'break of PMH/PML, st.5 tg1 ts60, bps'); print(out.round(1).unstack('half'))
