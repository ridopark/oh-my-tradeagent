import _p, pandas as pd, numpy as np
L = pd.read_csv('legs_raw.psv', sep='|', header=None, names=['d','wf','ts','s','dir','lvl','mode','mult'])
print(len(L), L['mode'].value_counts().to_dict(), L.mult.value_counts().to_dict())
print(L.wf.str.split('/').str[0].value_counts().to_dict())
D = L.drop_duplicates(['d','s','dir','lvl'])
print('dedup', len(D), 'dup (d,s,dir) w/ different lvl:', D.duplicated(['d','s','dir']).sum())
t = pd.to_datetime(D.ts, utc=True).dt.tz_convert('America/New_York'); mm = t.dt.hour*60 + t.dt.minute
print('post time ET min-of-day quantiles', mm.quantile([0, .05, .5, .95, 1]).to_dict())
print('posted >=9:30:', (mm >= 570).sum(), ' >=9:25:', (mm >= 565).sum())
print('date mismatch', (t.dt.date.astype(str) != D.d).sum())
print(D.d.min(), D.d.max(), D.d.nunique(), D.s.nunique())
print(D.dir.value_counts().to_dict())
print('integer levels', (D.lvl % 1 == 0).mean(), ' x.5', ((D.lvl*2) % 1 == 0).mean())
print(D.groupby(['d','s','dir']).size().value_counts().to_dict())
late = D[mm.values >= 570]
print(late[['d','ts','s','dir','lvl']].head(20).to_string())
