import pandas as pd, numpy as np
from zoneinfo import ZoneInfo
ET=ZoneInfo('America/New_York'); REV='..'
s=pd.read_csv(f'{REV}/sig_raw.psv',sep='|',header=None,names=['ts','tenant','sid','author','action','right','tk','posted'])
s=s[(s.action=='BTO')&(s.author!='TradingTheTrend')&~s.author.fillna('').str.startswith('tenant:')&~s.sid.str.startswith('manual')].drop_duplicates('sid')
s['pt']=pd.to_datetime(s.posted,utc=True,format='ISO8601'); s['rt']=pd.to_datetime(s.ts).dt.tz_localize(ET).dt.tz_convert('UTC')
print('independent BTOs',len(s)); print(s[['ts','author','tk','right','posted']].to_string())
E=pd.read_parquet(f'{REV}/my_legs.parquet'); F=E[E.status=='fired'].copy()
F['tt']=[pd.Timestamp(f'{d} 09:30',tz=ET).tz_convert('UTC')+pd.Timedelta(minutes=int(m)) for d,m in zip(F.date,F.trig_m)]
def tag(r,col):
    w=s[(s.tk==r.tk)&(s[col]<=r.tt)&(s[col]>=r.tt-pd.Timedelta(hours=24))]
    want='C' if r.dir=='ABOVE' else 'P'
    a=(w.right==want).any(); dz=(w.right!=want).any()
    return 'MIXED' if a and dz else 'AGREE' if a else 'DISAGREE' if dz else 'SILENT'
for col in ['pt','rt']:
    F['h91_'+col]=F.apply(tag,axis=1,col=col)
    print(col, F.groupby(['half','h91_'+col]).size().unstack(fill_value=0))
print(F[F.h91_pt!='SILENT'][['date','tk','dir','trig_hm','r60','h91_pt']])
# near misses: any same-ticker indep BTO on same date regardless of window
F.to_parquet(f'{REV}/my_legs_h91.parquet')
