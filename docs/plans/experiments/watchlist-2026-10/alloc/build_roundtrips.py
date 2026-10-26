"""Build realized round trips + daily P&L from order_intent_journal (+ audit_log for expiries/condors).

Accounting: each exit fill realizes (exit_px - avg_entry_px) * qty * 100 on ITS OWN ET date.
Expiry-worthless residuals realize -avg_entry * qty * 100 on the PositionExpired date (flagged).
Condors: credit from the MLEG fill, settlement debit from CondorSettled (no exit fill exists).
"""
import json, re, sys
from datetime import date
import numpy as np, pandas as pd

OUT = sys.argv[1]
TODAY = date(2026, 10, 22)
ET = "America/New_York"


def load_journal():
    fr = []
    for db in ["exec_alpaca_live", "exec_alpaca_paper"]:
        d = pd.read_csv(f"{OUT}/raw/{db}.csv")
        d["db"] = db
        fr.append(d)
    j = pd.concat(fr, ignore_index=True)
    for c in ["filled_at", "recorded_at"]:
        j[c] = pd.to_datetime(j[c], utc=True, format="ISO8601")
    j["et_date"] = j.filled_at.dt.tz_convert(ET).dt.date
    return j


def entry_id(row):
    k = row.intent_key
    if "/wl/" in k:
        return row.signal_id
    m = re.match(r"^t-[^/]+/s-[^/]+/sig/(.+?):entry(:repeg-\d+)?$", k)
    return m.group(1) if m else None


def exit_parts(k):
    m = re.match(r"^t-[^/]+/s-[^/]+/pos/([^/]+)/(.+?):exit:(.+)$", k)
    return (m.group(1), m.group(2), m.group(3)) if m else (None, None, None)


def exit_reason(tail):
    if tail.startswith("flatten-"):
        return tail.split(":")[0][len("flatten-"):]
    if tail.startswith("trim:"):
        return "operator_trim"
    if tail.startswith("chat-messages-") or tail.startswith("manual"):
        return "author_stc"
    return "other:" + tail[:30]


def occ_expiry(sym):
    m = re.search(r"(\d{6})[CP]\d{8}$", sym.replace(" ", ""))
    return pd.to_datetime(m.group(1), format="%y%m%d").date() if m else None


def main():
    j = load_journal()
    a = pd.read_csv(f"{OUT}/raw/audit.csv")
    a["occurred_at"] = pd.to_datetime(a.occurred_at, utc=True, format="ISO8601")
    a["et_date"] = a.occurred_at.dt.tz_convert(ET).dt.date
    a["subj"] = a.subject.map(json.loads)
    flags = []

    single = j[j.strategy_id != "gated_condor"]
    fills = single[single.state == "FILLED"].copy()
    ent = fills[fills.side == "BUY"].copy()
    ent["eid"] = ent.apply(entry_id, axis=1)
    ex = fills[fills.side == "SELL"].copy()
    parts = ex.intent_key.map(exit_parts)
    ex["sym_k"] = parts.map(lambda p: p[0]); ex["eid"] = parts.map(lambda p: p[1])
    ex["reason"] = parts.map(lambda p: exit_reason(p[2]) if p[2] else None)
    assert ent.eid.notna().all() and ex.eid.notna().all(), "unparsed key"
    assert (ex.sym_k == ex.option_symbol).all()

    key = ["tenant_id", "strategy_id", "option_symbol", "eid"]
    ent["cost"] = ent.avg_fill_price * ent.filled_qty * 100
    eg = ent.groupby(key).agg(entry_qty=("filled_qty", "sum"), entry_cost=("cost", "sum"),
                              entry_time=("filled_at", "min"), entry_date=("et_date", "min"),
                              n_entry_fills=("intent_key", "size"), db=("db", "first")).reset_index()
    eg["entry_px"] = eg.entry_cost / eg.entry_qty / 100

    # Expiries (dedupe per workflow: first event)
    pe = a[a.kind == "PositionExpired"].copy()
    pe["option_symbol"] = pe.subj.map(lambda s: s["option_symbol"])
    pe["eid"] = pe.subj.map(lambda s: s["entry_signal_id"])
    pe["exp_qty"] = pe.subj.map(lambda s: s["remaining_qty_before"])
    ndup = pe.duplicated(["tenant_id", "strategy_id", "option_symbol", "eid"]).sum()
    pe = pe.sort_values("occurred_at").drop_duplicates(["tenant_id", "strategy_id", "option_symbol", "eid"])
    if ndup:
        flags.append(f"PositionExpired: {ndup} duplicate events collapsed to first per position")

    # Exits with no matching entry fill: recon adoption re-keys the workflow under the EXIT signal id.
    # Re-attribute to the unique same-(tenant,strategy,symbol) position entered before the exit.
    known = set(map(tuple, eg[key].values))
    rekeyed = []
    for i, r in ex.iterrows():
        if tuple(r[c] for c in key) in known:
            continue
        cand = eg[(eg.tenant_id == r.tenant_id) & (eg.strategy_id == r.strategy_id)
                  & (eg.option_symbol == r.option_symbol) & (eg.entry_time <= r.filled_at)]
        if len(cand) == 1:
            ex.at[i, "eid"] = cand.eid.iloc[0]
            rekeyed.append(r.intent_key[:100])
    if rekeyed:
        flags.append(f"{len(rekeyed)} exit fills keyed to an adoption-minted workflow id were re-attributed "
                     "to the unique same-symbol entry: " + "; ".join(rekeyed))
    ex["rekeyed"] = ex.intent_key.isin([k for k in ex.intent_key if k[:100] in rekeyed])
    exk = ex.merge(eg[key + ["entry_px"]], on=key, how="left")
    orphan = exk[exk.entry_px.isna()]
    if len(orphan):
        flags.append(f"{len(orphan)} FILLED exit rows have no FILLED entry in journal (excluded): "
                     + "; ".join(orphan.intent_key.str.slice(0, 110)))

    ev = []  # realized events
    for _, r in exk[exk.entry_px.notna()].iterrows():
        ev.append(dict(**{k: r[k] for k in key}, date=r.et_date, ts=r.filled_at, qty=r.filled_qty,
                       px=r.avg_fill_price, pnl=(r.avg_fill_price - r.entry_px) * r.filled_qty * 100,
                       cost=r.entry_px * r.filled_qty * 100, reason=r.reason, kind="fill"))
    ev = pd.DataFrame(ev)

    rts = []
    exq = ev.groupby(key).qty.sum() if len(ev) else pd.Series(dtype=float)
    pe_idx = pe.set_index(["tenant_id", "strategy_id", "option_symbol", "eid"])
    exp_events = []
    for _, e in eg.iterrows():
        k = tuple(e[c] for c in key)
        xq = exq.get(k, 0)
        resid = e.entry_qty - xq
        expiry = occ_expiry(e.option_symbol)
        fl = []
        if e.n_entry_fills > 1:
            fl.append(f"{e.n_entry_fills}_entry_fills")
        if ex[(ex.tenant_id == k[0]) & (ex.strategy_id == k[1]) & (ex.option_symbol == k[2]) & (ex.eid == k[3])].rekeyed.any():
            fl.append("HAS_REKEYED_ADOPTION_EXIT")
        status = "CLOSED"
        if resid < 0:
            fl.append(f"OVER_EXIT({-resid})")
        if resid > 0:
            if k in pe_idx.index:
                p = pe_idx.loc[k]
                exp_events.append(dict(**dict(zip(key, k)), date=p.et_date, ts=p.occurred_at, qty=resid, px=0.0,
                                       pnl=-e.entry_px * resid * 100, cost=e.entry_px * resid * 100,
                                       reason="expired_worthless", kind="expiry"))
                status = "CLOSED_EXPIRED" if xq == 0 else "CLOSED_PARTIAL_EXPIRED"
                fl.append("EXPIRY_MARKED_0")
                if p.exp_qty != resid:
                    fl.append(f"EXPIRY_QTY_MISMATCH(audit {p.exp_qty} vs journal resid {resid})")
            elif expiry and expiry < TODAY:
                exp_events.append(dict(**dict(zip(key, k)), date=expiry, ts=pd.Timestamp(expiry, tz=ET), qty=resid,
                                       px=0.0, pnl=-e.entry_px * resid * 100, cost=e.entry_px * resid * 100,
                                       reason="expired_unexplained", kind="expiry"))
                status = "CLOSED_RESID_EXPIRED_NO_AUDIT"
                fl.append("RESID_PAST_EXPIRY_NO_PositionExpired_MARKED_0")
            else:
                status = "OPEN" if xq == 0 else "PARTIAL_OPEN"
        rts.append(dict(**dict(zip(key, k)), db=e.db, entry_date=e.entry_date, entry_qty=e.entry_qty,
                        entry_px=round(e.entry_px, 4), entry_cost=round(e.entry_cost, 2), exit_qty=xq,
                        resid_qty=resid, status=status, flags=";".join(fl)))
    ev = pd.concat([ev, pd.DataFrame(exp_events)], ignore_index=True)
    rt = pd.DataFrame(rts)

    agg = ev.groupby(key).agg(realized_pnl=("pnl", "sum"), realized_cost=("cost", "sum"),
                              exit_date=("date", "max"), n_exit_events=("qty", "size")).reset_index()
    rsn = (ev.groupby(key + ["reason"]).qty.sum().reset_index().sort_values("qty", ascending=False)
           .groupby(key).agg(primary_reason=("reason", "first"),
                             reasons=("reason", lambda s: "|".join(sorted(set(s))))).reset_index())
    rt = rt.merge(agg, on=key, how="left").merge(rsn, on=key, how="left")
    rt["pct_of_premium"] = rt.realized_pnl / rt.realized_cost

    # Condors
    cj = j[(j.strategy_id == "gated_condor") & (j.state == "FILLED")]
    cs = a[a.kind == "CondorSettled"]
    cond_rows, cond_ev = [], []
    for _, r in cj.iterrows():
        d = r.signal_id
        s = cs[cs.workflow_id.str.endswith("/condor/" + d)]
        cf = a[(a.kind == "CondorEntryFilled") & a.subj.map(lambda x: x.get("hold_workflow_id", "").endswith("/condor/" + d))]
        mr = cf.subj.iloc[0]["max_risk_per_contract"] if len(cf) else np.nan
        credit = -r.avg_fill_price  # journal stores credit as a negative price
        qty = r.filled_qty
        base = dict(tenant_id=r.tenant_id, strategy_id=r.strategy_id, option_symbol="XSP condor " + d, eid=d)
        if len(s):
            sj = s.subj.iloc[0]
            pnl = (credit - sj["settlement_debit"]) * qty * 100
            if abs(pnl - sj["expected_pnl"]) > 0.01:
                flags.append(f"condor {d}: journal pnl {pnl} != audit expected_pnl {sj['expected_pnl']}")
            cond_ev.append(dict(**base, date=s.et_date.iloc[0], ts=s.occurred_at.iloc[0], qty=qty, px=sj["settlement_debit"],
                                pnl=pnl, cost=mr * qty, reason="settled_" + sj["outcome"], kind="settle"))
            st, ed = "CLOSED_SETTLED", s.et_date.iloc[0]
        else:
            pnl, st, ed = np.nan, "OPEN", None
        cond_rows.append(dict(**base, db=r.db, entry_date=r.et_date, entry_qty=qty, entry_px=-credit,
                              entry_cost=mr * qty, exit_qty=qty if len(s) else 0, resid_qty=0 if len(s) else qty,
                              status=st, flags="capital=max_risk*qty;credit_is_negative_px", realized_pnl=pnl,
                              realized_cost=mr * qty, exit_date=ed, n_exit_events=1 if len(s) else 0,
                              primary_reason=cond_ev[-1]["reason"] if len(s) else None,
                              reasons=cond_ev[-1]["reason"] if len(s) else None,
                              pct_of_premium=pnl / (mr * qty) if len(s) else np.nan))
    rt = pd.concat([rt, pd.DataFrame(cond_rows)], ignore_index=True)
    ev = pd.concat([ev, pd.DataFrame(cond_ev)], ignore_index=True)
    ev["live"] = ~ev.tenant_id.isin(["staging_paper", "dev"])

    # ---- Cross-check journal exits vs audit PartialExitFilled (by broker_order_id) ----
    pef = a[a.kind == "PartialExitFilled"].copy()
    pef["boid"] = pef.subj.map(lambda s: s.get("broker_order_id"))
    pef["q"] = pef.subj.map(lambda s: s.get("qty_filled"))
    pef["p"] = pef.subj.map(lambda s: s.get("avg_fill_price"))
    aq = pef.groupby(["tenant_id", "boid"]).agg(aq=("q", "sum"), ap=("p", "last"), n=("q", "size")).reset_index()
    jx = j[(j.side == "SELL") & (j.strategy_id != "gated_condor")]
    cmp_ = aq.merge(jx[["tenant_id", "broker_order_id", "state", "filled_qty", "avg_fill_price", "intent_key", "et_date"]],
                    left_on=["tenant_id", "boid"], right_on=["tenant_id", "broker_order_id"], how="outer", indicator=True)
    cmp_.to_csv(f"{OUT}/raw/xcheck_exits.csv", index=False)
    rt.to_csv(f"{OUT}/roundtrips.csv", index=False)
    ev.to_csv(f"{OUT}/raw/events.csv", index=False)
    with open(f"{OUT}/raw/build_flags.txt", "w") as f:
        f.write("\n".join(flags))
    print("\n".join(flags))
    print(rt.groupby(["tenant_id", "strategy_id", "status"]).size().to_string())


if __name__ == "__main__":
    main()
