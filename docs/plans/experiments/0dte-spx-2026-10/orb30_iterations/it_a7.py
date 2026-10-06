"""A7: ORB30 score==2, long 0DTE SPXW (ATM / ITM10), 120-min horizon, asymmetric SPX exits:
target +T bp / stop -S bp (bar with both -> stop), timeout at 120m. Exit px = option mark - h."""
import os,sys; exec(open(os.environ['CACHE']+'/iterate.py').read())
F=pd.read_parquet(f'{S}/orb30_feat.parquet'); F.index=F.index.astype(str)
SC=None
def trade(d,r,TS,strikes,day,sp,holdout=False):
    t0=T(d,r.sig_hm)+pd.Timedelta(minutes=1); dr=int(r.dir)
    if t0 not in sp.index: return []
    e=sp.at[t0,'o']; path=sp.loc[t0:t0+pd.Timedelta(minutes=119)]
    fav=((path.h/e-1) if dr>0 else (1-path.l/e))*1e4; adv=((1-path.l/e) if dr>0 else (path.h/e-1))*1e4
    out=[]
    for T_,S_ in TS:
        tx=path.index[-1]; how='time'
        for t in path.index:
            if adv[t]>=S_: tx=t; how='stop'; break
            if fav[t]>=T_: tx=t; how='target'; break
        atm=round(day.spx.at[t0]/5)*5; cp='C' if dr>0 else 'P'
        for sk,k in strikes(atm,dr):
            pe=day.px(cp,k,t0)
            if not np.isfinite(pe) or pe<0.5 or (cp,k) not in day.mark.columns: continue
            px=day.mark.at[tx,(cp,k)]
            if not np.isfinite(px): continue
            for mode,st,tag in FILLS:
                ent=pe+E.half(pe,st)+E.FEE; ex=max(px-E.half(px,st)-E.FEE,0)
                out.append(dict(d=d,variant=f'T{T_}/S{S_} {sk}',fill=tag,pnl=ex-ent,pnl_r=(ex-ent)/ent,how=how))
    return out
def fn(d):
    if d not in F.index or F.loc[d].score!=2: return []
    try: day=E.Day(d)
    except Exception: return []
    return trade(d,F.loc[d],((15,30),(20,30)),lambda a,dr:(('ATM',a),('ITM10',a-10*dr)),day,SPYB[d])
if __name__=='__main__':
    R=evaluate('A7 ORB30 score==2 asymmetric exits, 120m',fn); R.to_parquet(f'{S}/a7.parquet')
