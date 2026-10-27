import json, pandas as pd, numpy as np
REV='/tmp/claude-1000/-home-ridopark-src-oh-my-tradeagent/de00d765-c1fe-46af-bdde-9acae03716fc/scratchpad/wl/r9/review'
def daily():
    rows=[]
    for l in open(f'{REV}/stock_daily.jsonl'):
        j=json.loads(l)
        for tk,bs in (j['page']['bars'] or {}).items():
            for b in bs: rows.append((tk,b['t'][:10],b['o'],b['c']))
    return pd.DataFrame(rows,columns=['tk','date','o','c'])
def occ(tk,E,r,k): return f"{tk}{E[2:4]}{E[5:7]}{E[8:10]}{r}{int(round(k*1000)):08d}"
def sessions_needed(S,D):
    need={}
    for r in S.itertuples():
        cal=sorted(D[D.tk==r.tk].date); i=cal.index(r.date)
        need.setdefault(r.tk,set()).update(cal[i-20:i+1])
    return need
