# Completeness cross-check: Benzinga results headlines ("<Name> Qn [Adj.] EPS ... Estimate") per ticker over the full
# window vs the Nasdaq-derived earnings_table. Prints any news report-day not within 1 calendar day of a table row.
import pandas as pd
H = ".."
t = pd.read_csv(f"{H}/earnings_table.csv", dtype=str)
n = pd.read_json(f"{H}/raw/news_full.jsonl.gz", lines=True, compression="gzip")
n["et"] = pd.to_datetime(n.created_at, utc=True).dt.tz_convert("America/New_York")
m = n[n.headline.str.contains(r"Q[1-4] (?:Adj\. |GAAP )?EPS .*Estimate") & ~n.headline.str.contains("CORRECTION|Earlier reported")]
# keep only headlines whose symbols list has <=2 tickers incl. the query (avoid roundups)
m = m[m.symbols.apply(len) <= 2]
out = []
for r in m.itertuples():
    day = r.et.normalize().tz_localize(None)
    known = pd.to_datetime(t[t.s == r.q].nasdaq_date)
    if not ((known - day).abs() <= pd.Timedelta(days=1)).any():
        out.append((r.q, r.et.strftime("%Y-%m-%d %H:%M"), r.symbols, r.headline[:110]))
for o in out: print(*o)
print("unmatched:", len(out), "| matched report headlines:", len(m) - len(out))
