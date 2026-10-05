"""A8 calibration (DEV ONLY, holdout untouched): premium-based exit proxy for A7.
Same entries (ORB30 score==2, ITM10, SPXW dev 2024-03..2026-03). Exits on the OPTION MARK:
target mark>=entry*(1+T), stop mark<=entry*(1-S), timeout 120m. Grid T{10,15,20} x S{25,30,35}.
Goal: pick the cell closest to A7's underlying-exit behavior (dev +6.0%/trade, win 67.7%), freeze it."""
import os,sys; exec(open(os.environ['CACHE']+'/iterate.py').read())
F=pd.read_parquet(f'{S}/orb30_feat.parquet'); F.index=F.index.astype(str)
def fn(d):
    if d not in F.index or F.loc[d].score!=2: return []
    r=F.loc[d]
    try: day=E.Day(d)
    except Exception: return []
    t0=T(d,r.sig_hm)+pd.Timedelta(minutes=1); dr=int(r.dir)
    atm=round(day.spx.at[t0]/5)*5; cp='C' if dr>0 else 'P'; k=atm-10*dr
    pe=day.px(cp,k,t0)
    if not np.isfinite(pe) or pe<0.5 or (cp,k) not in day.mark.columns: return []
    marks=day.mark[(cp,k)]
    path=marks.loc[t0:t0+pd.Timedelta(minutes=119)]
    out=[]
    for Tg in (0.10,0.15,0.20):
        for St in (0.25,0.30,0.35):
            tx=path.index[-1]; how='time'
            for t,m in path.items():
                if not np.isfinite(m): continue
                if m<=pe*(1-St): tx=t; how='stop'; break
                if m>=pe*(1+Tg): tx=t; how='target'; break
            px=marks.loc[:tx].ffill(limit=15).iloc[-1]
            if not np.isfinite(px): continue
            for mode,st,tag in FILLS:
                ent=pe+E.half(pe,st)+E.FEE; ex=max(px-E.half(px,st)-E.FEE,0)
                out.append(dict(d=d,variant=f'T{int(Tg*100)}/S{int(St*100)}',fill=tag,pnl=ex-ent,pnl_r=(ex-ent)/ent,how=how))
    return out
if __name__=='__main__':
    R=evaluate('A8 premium-exit calibration (DEV, SPXW ITM10)',fn); R.to_parquet(f'{S}/a8.parquet')
