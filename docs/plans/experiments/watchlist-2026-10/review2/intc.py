import pandas as pd, datetime as dt
B = pd.read_parquet("rth_1m.parquet")
for d in [dt.date(2026,7,31), dt.date(2026,8,3)]:
    x = B[(B.s=="INTC")&(B.d==d)]
    if len(x): print(d, "open", x.o.iloc[0], "high", x.h.max(), "low", x.l.min(), "close", x.c.iloc[-1], "max before 14:15", x[x.t.dt.hour*60+x.t.dt.minute < 855].h.max())
    else: print(d, "no INTC bars")
