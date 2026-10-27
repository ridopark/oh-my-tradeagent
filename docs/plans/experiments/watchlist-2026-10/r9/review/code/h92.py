from common import *
import gzip, re
from zoneinfo import ZoneInfo
from stats import diff_ci
ET=ZoneInfo('America/New_York')
rows=[]
for l in gzip.open(f'{REV}/opt_bars.jsonl.gz','rt'):
    j=json.loads(l); tk,s=j['tag'].split('|')
    for sym,bs in (j['page'].get('bars') or {}).items():
        r=re.match(r'[A-Z]+\d{6}([CP])',sym).group(1)
        for b in bs: rows.append((tk,s,r,b['t'],b['v']))
O=pd.DataFrame(rows,columns=['tk','s','r','t','v'])
O['et']=pd.to_datetime(O.t,utc=True).dt.tz_convert(ET)
bad=(O.et.dt.strftime('%Y-%m-%d')!=O.s).sum(); print('bars off trade date:',bad)
O=O[O.et.dt.strftime('%Y-%m-%d')==O.s]; O['m']=O.et.dt.hour*60+O.et.dt.minute-570
O['sv']=np.where(O.r=='C',1,-1)*O.v
S=pd.read_csv(f'{REV}/h92_sample_keys.csv'); D=daily()
def flow(tk,s,endm): x=O[(O.tk==tk)&(O.s==s)&(O.m>=0)&(O.m<endm)]; return x.sv.sum(), x.v.sum()
out=[]
for r in S.itertuples():
    cal=sorted(D[D.tk==r.tk].date); i=cal.index(r.date); endm=min(r.trig_m,60)
    x,tot=flow(r.tk,r.date,endm); hist=np.array([flow(r.tk,s,endm)[0] for s in cal[i-20:i]])
    z=(x-hist.mean())/hist.std(ddof=1); zs=z if r.dir=='ABOVE' else -z
    out.append(dict(date=r.date,tk=r.tk,dir=r.dir,trig=r.trig,half=r.half,endm=endm,cmp=x,totvol=tot,z=z,
        tag='AGREE' if zs>=1 else 'DISAGREE' if zs<=-1 else 'SILENT'))
T=pd.DataFrame(out); T.to_csv(f'{REV}/my_h92_tags.csv',index=False)
print(T.groupby(['half','tag']).size().unstack(fill_value=0))
E=pd.read_parquet(f'{REV}/my_legs.parquet')
M=T.merge(E[['date','tk','dir','trig','r60','rclose']],on=['date','tk','dir','trig'])
for h in ['FIT','TEST','ALL']:
    x=M if h=='ALL' else M[M.half==h]
    for a,b in [('AGREE','SILENT'),('AGREE','DISAGREE')]:
        for col in ['r60','rclose']:
            d,lo,hi,na,nb=diff_ci(x,col,a,b); print(f'{h} {a}-{b} {col}: {d:+.1f} [{lo:+.1f},{hi:+.1f}] n={na}/{nb}')
M.to_csv(f'{REV}/my_h92_tags_outcomes.csv',index=False)
