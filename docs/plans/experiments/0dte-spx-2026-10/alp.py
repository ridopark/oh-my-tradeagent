import os, json, time, urllib.request, urllib.parse, pathlib
ENV = (pathlib.Path(__file__).resolve().parents[4] / '.env').read_text().splitlines()
def env(k): return [l.split('=',1)[1].strip().strip('"\'') for l in ENV if l.startswith(k+'=')][-1]
H = {'APCA-API-KEY-ID': env('APCA_API_KEY_ID_DATA'), 'APCA-API-SECRET-KEY': env('APCA_API_SECRET_KEY_DATA')}
B = 'https://data.alpaca.markets'
def get(path, **q):
    url = B + path + '?' + urllib.parse.urlencode(q)
    for i in range(6):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=60) as r:
                return json.load(r)
        except Exception as e:
            if i == 5: raise
            time.sleep(2 ** i)
def paged(path, key, **q):
    out = []; tok = None
    while True:
        if tok: q['page_token'] = tok
        d = get(path, **q)
        v = d.get(key) or {}
        out.append(v)
        tok = d.get('next_page_token')
        if not tok: return out
