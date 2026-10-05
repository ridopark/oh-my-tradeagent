import os,sys; exec(open(os.environ['CACHE']+'/iterate.py').read())
def fn(d):
    try: day=E.Day(d)
    except Exception: return []
    out=[]
    for hm in ('14:00','14:30','15:00','15:30'):
        t=T(d,hm); S0=day.spx.at[t]
        for otm in (0,5,10):
            kc=int(np.ceil((S0+otm)/5)*5)
            for w in (5,10):
                for mode,st,tag in FILLS:
                    r=spread(day,t,'C',kc,w,mode,st)
                    if r: out.append(dict(d=d,variant=f'{hm} otm{otm:02d} w{w:02d}',fill=tag,pnl=r['pnl'],pnl_r=r['pnl']/r['risk']))
    return out
if __name__=='__main__':
    R=evaluate('IT2 call credit spread grid (time x otm x width), hold to settle',fn)
