# H8.1 trade leg: buy ATM straddle at 09:31, sell 15:55, Alpaca 1m option bars, Roll half-spread (floor 1%).
import pandas as pd, numpy as np, datetime as dt, os
O = os.path.dirname(os.path.abspath(__file__))
B = pd.read_json(f"{O}/opt_bars.jsonl.gz", lines=True, dtype={"d": str})
B["et"] = pd.to_datetime(B.t, utc=True).dt.tz_convert("America/New_York")
B = B[B.et.dt.strftime("%Y-%m-%d") == B.d]                       # date-mixing guard
B["hm"] = B.et.dt.strftime("%H:%M")
B["u"] = B.s.str[:-15]; B["exp"] = pd.to_datetime(B.s.str[-15:-9], format="%y%m%d").dt.strftime("%Y-%m-%d")
B["cp"] = B.s.str[-9]; B["K"] = B.s.str[-8:].astype(int) / 1000
B = B.sort_values(["d", "s", "et"])
def roll_hs(c):
    dc = np.diff(c.values)
    if len(dc) < 3: return 0.01
    cv = np.cov(dc[1:], dc[:-1])[0, 1]
    return max(0.01, np.sqrt(-cv) / np.median(c.values) if cv < 0 else 0.0)
g = B.groupby(["d", "s"])
leg = pd.DataFrame({
    "entry": B[(B.hm >= "09:31") & (B.hm <= "09:35")].groupby(["d", "s"]).o.first(),
    "exit": B[(B.hm >= "15:30") & (B.hm <= "15:54")].groupby(["d", "s"]).c.last(),
    "exit_stale": B[B.hm <= "15:54"].groupby(["d", "s"]).c.last(),
    "hs": g.c.apply(roll_hs),
}).reset_index()
leg = leg.merge(B.drop_duplicates("s")[["s", "u", "exp", "cp", "K"]], on="s")
leg = leg[leg.entry.notna()]
sp = pd.read_csv(f"{O}/straddle_spots.csv", dtype={"d": str})
out = []
for d, u, S in sp.itertuples(index=False):
    x = leg[(leg.d == d) & (leg.u == u) & (leg.exp >= (dt.date.fromisoformat(d) + dt.timedelta(days=2)).isoformat())]
    pr = x.pivot_table(index=["exp", "K"], columns="cp", values="s", aggfunc="first").reindex(columns=["C", "P"]).dropna()
    if pr.empty: out.append({"d": d, "u": u, "ok": False}); continue
    e = pr.index.get_level_values(0).min(); ks = pr.loc[e].index
    K = ks[np.argmin(np.abs(ks - S))]
    c = x[x.s == pr.loc[(e, K), "C"]].iloc[0]; p = x[x.s == pr.loc[(e, K), "P"]].iloc[0]
    stale = pd.isna(c.exit) or pd.isna(p.exit)
    cx = c.exit if not pd.isna(c.exit) else c.exit_stale; px = p.exit if not pd.isna(p.exit) else p.exit_stale
    cost_in = c.entry * (1 + c.hs) + p.entry * (1 + p.hs); val_out = cx * (1 - c.hs) + px * (1 - p.hs)
    gross = (cx + px) / (c.entry + p.entry) - 1
    out.append({"d": d, "u": u, "ok": True, "S": S, "exp": e, "K": K, "moneyness": K / S - 1, "dte": (pd.Timestamp(e) - pd.Timestamp(d)).days,
                "c_in": c.entry, "p_in": p.entry, "c_out": cx, "p_out": px, "hs_c": c.hs, "hs_p": p.hs, "stale_exit": stale,
                "gross": gross, "net": val_out / cost_in - 1})
R = pd.DataFrame(out); R.to_csv(f"{O}/straddle_trades.csv", index=False)
print(R.ok.value_counts()); print(R[R.ok].describe().T.to_string())
