import os,sys; exec(open(os.environ['CACHE']+'/iterate.py').read())
def fn(d):
    try: day=E.Day(d)
    except Exception: return []
    t=T(d,'15:00'); S0=day.spx.at[t]; o=day.spx.iloc[0]; up=S0>o; out=[]
    for mode,st,tag in FILLS:
        for otm in (5,10):
            kc=int(np.ceil((S0+otm)/5)*5); kp=int(np.floor((S0-otm)/5)*5)
            vs={'put':('P',kp),'call':('C',kc),'trend(put if up)':('P',kp) if up else ('C',kc),'counter(call if up)':('C',kc) if up else ('P',kp)}
            for v,(cp,k) in vs.items():
                r=spread(day,t,cp,k,10,mode,st)
                if r: out.append(dict(d=d,variant=f'{v} otm{otm}',fill=tag,pnl=r['pnl'],pnl_r=r['pnl']/r['risk']))
            r=Sim(day,mode,st).run([dict(short=('C',kc),long=('C',kc+10)),dict(short=('P',kp),long=('P',kp-10))],t)
            if r: out.append(dict(d=d,variant=f'IC otm{otm}',fill=tag,pnl=r['pnl'],pnl_r=r['pnl']/r['risk']))
    return out
if __name__=='__main__': evaluate('IT1 15:00 credit spread w10 hold-to-settle',fn)
