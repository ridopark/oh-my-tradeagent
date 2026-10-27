# runs on homelab; reads JSON lines {"tag":..,"url":..,"params":{..}} from stdin; writes {"tag":..,"page":<json>} lines
import sys, os, json, requests, time
from concurrent.futures import ThreadPoolExecutor
H={'APCA-API-KEY-ID':os.environ['K'],'APCA-API-SECRET-KEY':os.environ['S']}
def run(req):
    out=[]; p=dict(req['params']); 
    while True:
        for a in range(6):
            r=requests.get(req['url'],params=p,headers=H,timeout=60)
            if r.status_code==429: time.sleep(2+a*2); continue
            break
        if r.status_code!=200:
            out.append(json.dumps({'tag':req['tag'],'err':r.status_code,'body':r.text[:300]})); break
        j=r.json(); out.append(json.dumps({'tag':req['tag'],'page':j}))
        t=j.get('next_page_token')
        if not t: break
        p['page_token']=t
    return out
reqs=[json.loads(l) for l in sys.stdin if l.strip()]
with ThreadPoolExecutor(int(os.environ.get('W','6'))) as ex:
    for lines in ex.map(run,reqs):
        for l in lines: print(l)
        sys.stdout.flush()
