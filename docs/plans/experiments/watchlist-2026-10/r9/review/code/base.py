import pandas as pd, numpy as np, json, sys
from zoneinfo import ZoneInfo
REV=sys.argv[1]; ET=ZoneInfo('America/New_York')
rows=[]
for l in open(f'{REV}/stock_bars.jsonl'):
    j=json.loads(l); tk,dt=j['tag'].split('|')
    for b in (j['page']['bars'] or {}).get(tk,[]): rows.append((tk,dt,b['t'],b['o'],b['c'],b['v']))
B=pd.DataFrame(rows,columns=['tk','date','t','o','c','v'])
B['et']=pd.to_datetime(B.t,utc=True).dt.tz_convert(ET); B['hm']=B.et.dt.strftime('%H:%M')
B=B[(B.hm>='09:30')&(B.hm<'16:00')&(B.et.dt.strftime('%Y-%m-%d')==B.date)].sort_values(['tk','date','et'])
B['m']=(B.et.dt.hour*60+B.et.dt.minute)-570  # minutes since 09:30
B.to_parquet(f'{REV}/rth_bars.parquet')
d=pd.read_csv(f'{REV}/legs_raw.psv',sep='|',header=None,names=['ts','tenant','tk','dir','trig'])
d['date']=d.ts.str[:10]; d['armed_hm']=d.ts.str[11:16]
L=d.drop_duplicates(['date','tk','dir','trig']).copy()
G={k:g for k,g in B.groupby(['tk','date'])}
out=[]
for r in L.itertuples():
    g=G.get((r.tk,r.date)); s=1 if r.dir=='ABOVE' else -1
    rec=dict(date=r.date,tk=r.tk,dir=r.dir,trig=r.trig,armed_hm=r.armed_hm,status='nobars')
    if g is None or g.m.iloc[0]!=0:
        rec['status']='nobars' if g is None else 'no0930bar'
        if g is None: out.append(rec); continue
    first=g.iloc[0]
    if s*(first.o-r.trig)>0: rec['status']='gap'; out.append(rec); continue
    hit=g[s*(g.c-r.trig)>0]
    if hit.empty: rec['status']='nofire'; out.append(rec); continue
    h=hit.iloc[0]; em=h.m+1  # entry at bar close, minutes since 09:30
    def px_at(mm):  # last close of bars ending at or before mm
        x=g[g.m+1<=mm]; return x.c.iloc[-1], x.m.iloc[-1]+1
    p60,m60=px_at(min(em+60,390)); pc,_=px_at(390)
    rec.update(status='fired',trig_m=em,trig_hm=f'{9+(30+em)//60:02d}:{(30+em)%60:02d}',entry=h.c,
               r60=s*(p60/h.c-1)*1e4,rclose=s*(pc/h.c-1)*1e4,r60_trunc=em+60>390)
    out.append(rec)
E=pd.DataFrame(out); E['half']=np.where(E.date<='2026-08-31','FIT','TEST')
E.to_parquet(f'{REV}/my_legs.parquet')
print(E.status.value_counts()); print(E[E.status=='no0930bar'][['date','tk']])
