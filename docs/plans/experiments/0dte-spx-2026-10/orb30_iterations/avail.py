import sys; sys.path.insert(0, sys.argv[1]); from alp import *
import datetime as dt
# weekdays in one test week (Mon..Fri) in 2024-04 and 2026-09; check ATM-ish 0DTE contract existence via daily bars
weeks={'2024-04':['2024-04-15','2024-04-16','2024-04-17','2024-04-18','2024-04-19'],'2026-09':['2026-09-14','2026-09-15','2026-09-16','2026-09-17','2026-09-18']}
syms=['SPY','QQQ','IWM','DIA','XSP','TQQQ','SQQQ','SPXL','UPRO','SPXU','SOXL','TNA','QLD','SSO']
for u in syms:
    line=[]
    for wk,days in weeks.items():
        s=''
        for d in days:
            b=get(f'/v2/stocks/{"SPY" if u=="XSP" else u}/bars',timeframe='1Day',start=d,end=d,feed='sip').get('bars') or []
            if not b: s+='?'; continue
            px=b[0]['o']*(1 if u!='XSP' else 1.003)
            yy=d[2:4]+d[5:7]+d[8:10]; found=False
            inc=1 if px<300 else 1 if u!='XSP' else 1
            for k in [round(px)+i for i in range(-3,4)]+[round(px*2)/2+i*0.5 for i in range(-3,4)]:
                sym=f'{u}{yy}C{int(round(k*1000)):08d}'
                r=get('/v1beta1/options/bars',symbols=sym,timeframe='1Day',start=d,end=d)['bars']
                if r.get(sym): found=True; break
            s+='Y' if found else '.'
        line.append(f'{wk}:{s}')
    print(f'{u:5s}', '  '.join(line))
