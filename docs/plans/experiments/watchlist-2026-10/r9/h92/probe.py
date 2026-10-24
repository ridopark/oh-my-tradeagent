import os, requests, json
H={"APCA-API-KEY-ID":os.environ["KID"],"APCA-API-SECRET-KEY":os.environ["SEC"]}
for base in ["https://paper-api.alpaca.markets","https://api.alpaca.markets"]:
    r=requests.get(base+"/v2/options/contracts",headers=H,params={"underlying_symbols":"NVDA","status":"inactive","expiration_date_gte":"2026-06-01","expiration_date_lte":"2026-06-12","limit":10000},timeout=60)
    print(base, r.status_code, r.text[:300])
    if r.ok:
        c=r.json()["option_contracts"]; print(len(c), sorted({x["expiration_date"] for x in c}), sorted({float(x["strike_price"]) for x in c})[:60])
