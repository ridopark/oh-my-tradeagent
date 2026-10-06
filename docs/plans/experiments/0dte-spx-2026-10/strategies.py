"""SPXW 0DTE short-premium candidates (from candidates.md), PRE-REGISTERED 2026-10-04 before any result.
  C7  control: 10:00 ATM iron fly, wings = round(straddle/5)*5, target 25% of credit, stop at cost-to-close >= 1.5x credit, out 15:45
  C1A MEIC: ICs at 12:00,12:30,13:00,13:30,14:00,14:30; short strike = OTM strike whose 50-wide spread credit is closest to $1.25/side;
      per-side stop when that side's cost-to-close >= total IC credit; hold rest to settlement
  C1B MEIC hourly clock: 09:45,10:45,11:45,12:45,13:45,14:45; otherwise as C1A
  C4A ORB-60 credit spread: OR = 09:30-10:29 SPX high/low, range >= 0.2% of open, first 1-min close outside OR 10:30-11:59,
      skip FOMC days; up-break -> sell put spread short = floor(OR low/5)*5, long = short-15; down-break -> call spread
      short = ceil(OR high/5)*5, long = short+15; enter next minute; hold to settlement
  C4B as C4A with stop at cost-to-close >= 2x credit
  C5  METF: at 12:30,13:00,13:30,14:00,14:30,14:45 EMA20>EMA40 on 1-min SPX -> put spread else call spread, 30 wide,
      short = OTM strike with credit closest to $1.75; stop at cost-to-close >= 2x credit; hold to settlement
  C6o noise-area overlay: at each noise-area entry (SPY signal), sell ATM SPXW put (long signal) / call (short signal);
      buy back at the signal exit mark or settle at 16:00
Units: P&L / max risk (spreads: width - credit; single short option: / credit), summed per DAY (one obs per day).
Fill models: base (print +- half-spread +fees), stress (2x half-spread), cons (stops trigger+fill at short-leg bar HIGH
  and long-leg bar LOW, +1 tick; entries -1 tick/leg).
PASS: base pooled t >= 2.7 AND mean > 0 in both halves (H1 2024-03..2025-06, H2 2025-07..2026-10) AND stress mean > 0.
"""
import os, sys, pickle, pandas as pd, numpy as np
from concurrent.futures import ProcessPoolExecutor
S=sys.argv[1]; os.environ['CACHE']=S; sys.path.insert(0,S)
import engine as E
FOMC=set(open(f'{S}/fomc_dates.txt').read().split())
NA=pickle.load(open(f'{S}/noise_area.pkl','rb')); NA=NA.set_index(NA.d.astype(str)).ev.to_dict()
SPYB=pd.read_parquet(f'{S}/spy_1m.parquet'); SPYB=SPYB[SPYB.t>='2024-03-01']; SPYB['d']=SPYB.t.dt.date.astype(str)
SPYB={d:g.set_index('t') for d,g in SPYB.groupby('d')}
TICK=0.05
class Sim:
    def __init__(s,day,mode,stress): s.D=day; s.mode=mode; s.st=stress
    def h(s,p): return E.half(p,s.st)+E.FEE
    def entry_px(s,cp,k,t):
        return s.D.px(cp,k,t)
    def leg(s,cp,k,t,kind):
        """kind: 'mark' or 'worst_short' / 'worst_long' (cons mode stop evaluation)"""
        if (cp,k) not in s.D.mark.columns: return np.nan
        m=s.D.mark.at[t,(cp,k)]
        if s.mode=='cons' and kind!='mark':
            src=s.D.hi if kind=='worst_short' else s.D.lo
            v=src.at[t,(cp,k)]
            if not np.isnan(v): m=v
        return m
    def side_close_cost(s,side,t,worst=False):
        (cp,ks),lg=side['short'],side.get('long')
        ps=s.leg(cp,ks,t,'worst_short' if worst else 'mark')
        if np.isnan(ps): return np.nan
        c=ps+s.h(ps)+(TICK if (worst and s.mode=='cons') else 0)
        if lg:
            pl=s.leg(lg[0],lg[1],t,'worst_long' if worst else 'mark')
            if np.isnan(pl): pl=0.0
            c-=max(pl-s.h(pl)-(TICK if (worst and s.mode=='cons') else 0),0)
        return c
    def open_side(s,side,t):
        (cp,ks),lg=side['short'],side.get('long')
        ps=s.entry_px(cp,ks,t)
        if np.isnan(ps) or ps<=0: return None
        cr=ps-s.h(ps)-(TICK if s.mode=='cons' else 0)
        if lg:
            pl=s.entry_px(lg[0],lg[1],t)
            if np.isnan(pl): return None
            cr-=pl+s.h(pl)+(TICK if s.mode=='cons' else 0)
        side['credit']=cr; side['open']=True; side['pnl']=0.0
        return cr
    def intrinsic(s,cp,k): x=s.D.settle; return max(x-k,0) if cp=='C' else max(k-x,0)
    def run(s,sides,t0,side_stop=None,struct_stop=None,struct_target=None,exit_hm=None,exit_t=None):
        for sd in sides:
            if s.open_side(sd,t0) is None: return None
        credit=sum(sd['credit'] for sd in sides)
        for t in s.D.idx[s.D.idx>t0]:
            hm=t.strftime('%H:%M')
            live=[sd for sd in sides if sd['open']]
            if not live: break
            if (exit_hm and hm>=exit_hm) or (exit_t is not None and t>=exit_t):
                for sd in live:
                    c=s.side_close_cost(sd,t)
                    if np.isnan(c): continue
                    sd['pnl']=sd['credit']-c; sd['open']=False
                if not [sd for sd in sides if sd['open']]: break
                continue
            if side_stop is not None:
                for sd in live:
                    thr=side_stop(sd,credit); cw=s.side_close_cost(sd,t,worst=True)
                    if not np.isnan(cw) and cw>=thr:
                        fill=cw if s.mode=='cons' else s.side_close_cost(sd,t)
                        sd['pnl']=sd['credit']-fill; sd['open']=False
            if struct_stop or struct_target:
                cs=[s.side_close_cost(sd,t) for sd in live]
                if any(np.isnan(cs)): continue
                tot=sum(cs)
                hit=(struct_target and tot<=(1-struct_target)*credit) or (struct_stop and tot>=struct_stop*credit)
                if hit:
                    for sd,c in zip(live,cs): sd['pnl']=sd['credit']-c; sd['open']=False
        for sd in sides:
            if sd['open']:
                (cp,ks),lg=sd['short'],sd.get('long')
                v=-s.intrinsic(cp,ks)+(s.intrinsic(*lg) if lg else 0)
                sd['pnl']=sd['credit']+v; sd['open']=False
        pnl=sum(sd['pnl'] for sd in sides)
        widths=[abs(sd['short'][1]-sd['long'][1]) for sd in sides if sd.get('long')]
        risk=(max(widths)-credit) if widths else max(credit,0.05)
        return dict(pnl=pnl,credit=credit,risk=max(risk,0.05))
def credit_strike(day,cp,t,width,target):
    S0=day.spx.at[t]; atm=round(S0/5)*5; best=None
    for i in range(1,31):
        k=atm+5*i if cp=='C' else atm-5*i
        kl=k+width if cp=='C' else k-width
        ps,pl=day.px(cp,k,t),day.px(cp,kl,t)
        if np.isnan(ps) or np.isnan(pl): continue
        cr=ps-pl
        if best is None or abs(cr-target)<abs(best[1]-target): best=(k,cr)
    return best[0] if best else None
def T(d,hm): return pd.Timestamp(f'{d} {hm}',tz='America/New_York')
def day_results(d):
    try: day=E.Day(d)
    except Exception: return []
    out=[]
    for mode,stress in (('base',False),('base',True),('cons',False)):
        tag='stress' if stress else mode
        def rec(strat,r):
            if r: out.append(dict(d=d,strat=strat,fill=tag,ret=r['pnl']/r['risk'],pnl=r['pnl'],credit=r['credit']))
        sim=lambda: Sim(day,mode,stress)
        # C7
        t=T(d,'10:00'); iv,st,atm=day.iv(t)
        if np.isfinite(st):
            w=max(5,round(st/5)*5)
            rec('C7 ATM iron fly 10:00',sim().run([dict(short=('C',atm),long=('C',atm+w)),dict(short=('P',atm),long=('P',atm-w))],t,
                struct_stop=1.5,struct_target=0.25,exit_hm='15:45'))
        # C1A/C1B
        for name,clock in (('C1A MEIC 12:00-14:30',['12:00','12:30','13:00','13:30','14:00','14:30']),('C1B MEIC hourly',['09:45','10:45','11:45','12:45','13:45','14:45'])):
            tot=None
            for hm in clock:
                t=T(d,hm); kc=credit_strike(day,'C',t,50,1.25); kp=credit_strike(day,'P',t,50,1.25)
                if kc is None or kp is None: continue
                r=sim().run([dict(short=('C',kc),long=('C',kc+50)),dict(short=('P',kp),long=('P',kp-50))],t,side_stop=lambda sd,cr: cr)
                if r: tot=dict(pnl=(tot or {}).get('pnl',0)+r['pnl'],risk=(tot or {}).get('risk',0)+r['risk'],credit=(tot or {}).get('credit',0)+r['credit'])
            rec(name,tot)
        # C4A/C4B
        if d not in FOMC:
            sp=SPYB[d]; rt=E.ratio[d]; orb=sp.between_time('09:30','10:29'); H,L=orb.h.max()*rt,orb.l.min()*rt
            if (H-L)/(sp.o.iloc[0]*rt)>=0.002:
                sig=None
                for t,c in (day.spx.between_time('10:30','11:59')).items():
                    if c>H: sig=(t,1);break
                    if c<L: sig=(t,-1);break
                if sig:
                    t=sig[0]+pd.Timedelta(minutes=1)
                    side=dict(short=('P',int(np.floor(L/5)*5)),long=('P',int(np.floor(L/5)*5)-15)) if sig[1]>0 else dict(short=('C',int(np.ceil(H/5)*5)),long=('C',int(np.ceil(H/5)*5)+15))
                    rec('C4A ORB60 credit spread',sim().run([dict(side)],t))
                    rec('C4B ORB60 credit spread stop2x',sim().run([dict(side)],t,side_stop=lambda sd,cr: 2*cr))
        # C5
        ema20=day.spx.ewm(span=20,adjust=False).mean(); ema40=day.spx.ewm(span=40,adjust=False).mean(); tot=None
        for hm in ['12:30','13:00','13:30','14:00','14:30','14:45']:
            t=T(d,hm); cp='P' if ema20.at[t]>ema40.at[t] else 'C'; k=credit_strike(day,cp,t,30,1.75)
            if k is None: continue
            r=sim().run([dict(short=(cp,k),long=(cp,k+30 if cp=='C' else k-30))],t,side_stop=lambda sd,cr: 2*cr)
            if r: tot=dict(pnl=(tot or {}).get('pnl',0)+r['pnl'],risk=(tot or {}).get('risk',0)+r['risk'],credit=0)
        rec('C5 METF',tot)
        # C6 overlay
        tot=None
        for m,dr,xm in NA.get(d,[]):
            t=T(d,m); atm=round(day.spx.at[t]/5)*5; cp='P' if dr>0 else 'C'
            r=sim().run([dict(short=(cp,atm))],t,exit_t=T(d,xm) if xm else None)
            if r: tot=dict(pnl=(tot or {}).get('pnl',0)+r['pnl'],risk=(tot or {}).get('risk',0)+r['risk'],credit=0)
        rec('C6o noise-area short-ATM overlay',tot)
    return out
if __name__=='__main__':
    days=sorted(k for k in E.ratio.index)
    with ProcessPoolExecutor(8) as ex: res=[r for x in ex.map(day_results,days,chunksize=4) for r in x]
    R=pd.DataFrame(res); R.to_parquet(f'{S}/strategies.parquet'); print(len(R),'rows')
