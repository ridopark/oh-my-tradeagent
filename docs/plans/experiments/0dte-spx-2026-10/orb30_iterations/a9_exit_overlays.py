"""A9 (PRE-REGISTERED 2026-10-04, written before results): can exit overlays cut big losses / grow wins
on ORB30 without killing EV? Underlying-level, 1-min bars, data THROUGH 2026-03 (final holdout untouched).
Population: ORB30 first-breakout signals; subsets ALL and score==2.
Baseline B: target +15bp / stop -30bp / 120m timeout. Overlays (8 cells, all reported):
  BE8, BE10      : after favorable excursion >= 8/10 bp (minute close), stop moves to entry (0bp)
  EC15, EC30     : at minute 15/30, exit at close if signed move <= -10bp
  S20            : fixed stop -20bp instead of -30
  TR10, TR15     : no fixed target; after fav >= 15bp, trail = exit when close falls 10/15bp off peak close; stop -30 until then
  BE8+TR10       : both
Intrabar: minute h/l; target&stop both touched in one bar -> loss side (conservative).
Metrics: n, EV bp, win%, avg win, avg loss, P5, FIT(16-20)/TEST(21-26Q1) EV. PASS bar: EV >= baseline-0.3bp
in BOTH fit/test AND P5 improved AND same direction on QQQ & IWM.
Diagnostics: P(ever fav>=8 | stopped), P(ever fav>=8 | target), P(adv>=10 in first 15m | target/stopped)."""
import sys, pandas as pd, numpy as np
S=sys.argv[1]
def load(U):
    bars=pd.read_parquet(f'{S}/spy_1m.parquet' if U=='SPY' else f'{S}/etf/{U}_1m.parquet')
    bars=bars[bars.t<'2026-04-01']; bars['d']=bars.t.dt.date
    sig=pd.read_parquet(f'{S}/orb30_feat.parquet' if U=='SPY' else f'{S}/etf/{U}_orb30.parquet')
    sig=sig[pd.to_datetime(sig.index)<'2026-04-01']
    return bars,sig
def paths(bars,sig):
    G={d:g.set_index(g.t.dt.strftime('%H:%M')) for d,g in bars.groupby('d')}
    out=[]
    for d,r in sig.iterrows():
        g=G.get(d if not isinstance(d,str) else pd.Timestamp(d).date()) if False else G.get(pd.Timestamp(d).date() if isinstance(d,(str,)) else d)
        if g is None: continue
        idx=list(g.index)
        if r.sig_hm not in idx: continue
        i=idx.index(r.sig_hm)+1; p=g.iloc[i:i+120]
        if len(p)<20: continue
        e=p.o.iloc[0]; dr=int(r.dir)
        fav=(((p.h/e-1) if dr>0 else (1-p.l/e))*1e4).values
        adv=(((1-p.l/e) if dr>0 else (p.h/e-1))*1e4).values
        clo=((p.c/e-1)*dr*1e4).values
        out.append(dict(d=d,score=r.score,fit=pd.Timestamp(d).year<=2020,fav=fav,adv=adv,clo=clo))
    return out
def sim(tr,rule):
    fav,adv,clo=tr['fav'],tr['adv'],tr['clo']; n=len(fav)
    stop=20.0 if rule=='S20' else 30.0; be_at={'BE8':8.,'BE10':10.,'BE8+TR10':8.}.get(rule)
    trail={'TR10':10.,'TR15':15.,'BE8+TR10':10.}.get(rule); target=None if trail else 15.0
    ec_m={'EC15':15,'EC30':30}.get(rule)
    be=False; armed=False; peak=-1e9
    for m in range(n):
        eff_stop = 0.0 if be else stop
        if armed:
            peak=max(peak,clo[m-1] if m>0 else 0)
            if peak-clo[m]>=trail: return clo[m]
            if adv[m]>=eff_stop and not be: return -stop
            if be and adv[m]>=0: return 0.0
        else:
            if adv[m]>=eff_stop:
                if be and fav[m]>=15 and target: pass
                return -eff_stop if not be else 0.0
            if target and fav[m]>=target: return target
            if trail and fav[m]>=15: armed=True; peak=clo[m]
        if be_at and not be and fav[m]>=be_at: be=True
        if ec_m and m==ec_m and clo[m]<=-10: return clo[m]
    return clo[-1]
RULES=['B','BE8','BE10','EC15','EC30','S20','TR10','TR15','BE8+TR10']
rows=[]; diag=[]
for U in ('SPY','QQQ','IWM'):
    bars,sig=load(U); trs=paths(bars,sig)
    for tr in trs:
        base=None
        for rule in RULES:
            v=sim(tr,rule)
            if rule=='B': base=v
            rows.append(dict(U=U,score=tr['score'],fit=tr['fit'],rule=rule,bp=v))
        # diagnostics on baseline outcome
        fav,adv=tr['fav'],tr['adv']
        ever8=bool((fav>=8).any()); early_red=bool((tr['clo'][:16]<=-10).any()) if len(tr['clo'])>16 else False
        diag.append(dict(U=U,score=tr['score'],fit=tr['fit'],out='target' if base==15 else ('stop' if base<=-19 else 'time'),ever8=ever8,early_red=early_red,bp=base))
R=pd.DataFrame(rows); D=pd.DataFrame(diag)
R.to_parquet(f'{S}/a9.parquet'); pd.set_option('display.width',250)
def table(sub,label):
    g=sub.groupby(['U','rule']).bp
    t=g.agg(n='size',EV='mean',win=lambda x:(x>0).mean()*100,avg_win=lambda x:x[x>0].mean(),avg_loss=lambda x:x[x<0].mean(),P5=lambda x:x.quantile(.05))
    for f,lab in ((True,'EV_FIT'),(False,'EV_TEST')):
        t[lab]=sub[sub.fit==f].groupby(['U','rule']).bp.mean()
    print(f'\n==== {label}'); print(t.round(2).to_string())
table(R[R.score==2],'score==2')
table(R,'ALL signals')
print('\n==== diagnostics (baseline, score==2): can losers be told apart early?')
for U in ('SPY','QQQ','IWM'):
    x=D[(D.U==U)&(D.score==2)]
    st=x[x.out=='stop']; tg=x[x.out=='target']
    print(f"{U}: stopped {len(st)} — ever +8bp first: {st.ever8.mean()*100:.0f}% | red<=-10bp by m15: {st.early_red.mean()*100:.0f}%   ||  targets {len(tg)} — ever red<=-10bp by m15: {tg.early_red.mean()*100:.0f}%")
