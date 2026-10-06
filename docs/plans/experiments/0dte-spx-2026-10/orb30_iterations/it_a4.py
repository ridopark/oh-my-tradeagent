"""A4: long 0DTE SPXW on ORB30 score==2 days (score>=2 and all-ORB30 as comparators).
Entry: minute after signal, buy ATM / OTM1 at print + h. Exit: SPY-high/low touch of +X or -X bp from entry
(both in one bar = loss side), or 60 min; exit price = option mark at that minute - h. X in {20,30}."""
import os,sys; exec(open(os.environ['CACHE']+'/iterate.py').read())
F=pd.read_parquet(f'{S}/orb30_feat.parquet'); F.index=F.index.astype(str)
def fn(d):
    if d not in F.index: return []
    r=F.loc[d]
    try: day=E.Day(d)
    except Exception: return []
    sp=SPYB[d]; t0=T(d,r.sig_hm)+pd.Timedelta(minutes=1); dr=int(r.dir)
    if t0 not in sp.index: return []
    e_spy=sp.at[t0,'o']; out=[]
    groups=['ALL']+(['score>=2'] if r.score>=2 else [])+(['score==2'] if r.score==2 else [])
    for X in (20,30):
        path=sp.loc[t0:t0+pd.Timedelta(minutes=59)]
        fav=(path.h/e_spy-1)*1e4 if dr>0 else (1-path.l/e_spy)*1e4
        adv=(1-path.l/e_spy)*1e4 if dr>0 else (path.h/e_spy-1)*1e4
        hitf=fav>=X; hita=adv>=X
        tx=path.index[-1]; how='time'
        for t in path.index:
            if hita[t]: tx=t; how='stop'; break
            if hitf[t]: tx=t; how='target'; break
        atm=round(day.spx.at[t0]/5)*5; cp='C' if dr>0 else 'P'
        for sk,k in (('ATM',atm),('OTM1',atm+5*dr)):
            pe=day.px(cp,k,t0)
            if not np.isfinite(pe) or pe<0.5: continue
            px=day.mark.at[tx,(cp,k)] if (cp,k) in day.mark.columns else np.nan
            if not np.isfinite(px): continue
            for mode,st,tag in FILLS:
                h=E.half(pe,st)+E.FEE; hx=E.half(px,st)+E.FEE
                ent=pe+h; ex=max(px-hx,0); pnl=ex-ent
                for g in groups: out.append(dict(d=d,variant=f'{g} X{X} {sk}',fill=tag,pnl=pnl,pnl_r=pnl/ent,how=how))
    return out
if __name__=='__main__':
    R=evaluate('A4 ORB30 score-filter long 0DTE, exit SPX +-X bp or 60m',fn); R.to_parquet(f'{S}/a4.parquet')
