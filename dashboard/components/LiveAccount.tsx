"use client";

import { useState } from "react";
import { AccountValueChart } from "@/components/AccountValueChart";
import { fmtCurrency } from "@/components/Pnl";
// Type-only import (erased at compile): lib/bff is server-only, only its shape crosses here.
import type { PortfolioHistory } from "@/lib/bff";

// Structure (a): the header and the chart share ONE fetch. AccountValueChart owns the history fetch
// and lifts each frame up via onData; the header below reads the SAME equity / profit_loss /
// base_value the chart drew, so the +$X (Y%) always matches the active range tab and baseline.
export function LiveAccount() {
  const [history, setHistory] = useState<PortfolioHistory | null>(null);

  return (
    <section className="flex flex-col gap-4">
      <AccountTotal history={history} />
      <AccountValueChart onData={setHistory} />
    </section>
  );
}

function AccountTotal({ history }: { history: PortfolioHistory | null }) {
  const equity =
    history && history.equity.length > 0
      ? history.equity[history.equity.length - 1]
      : null;
  const pl =
    history && history.profit_loss.length > 0
      ? history.profit_loss[history.profit_loss.length - 1]
      : null;
  const plPct =
    history && history.profit_loss_pct.length > 0
      ? history.profit_loss_pct[history.profit_loss_pct.length - 1]
      : null;

  const up = (pl ?? 0) >= 0;
  const changeCls = up ? "text-emerald-400" : "text-rose-400";
  const arrow = up ? "▲" : "▼";

  return (
    <div>
      <div className="text-xs uppercase tracking-wide text-slate-500">
        Total account value
      </div>
      <div className="mt-1 text-3xl font-semibold text-slate-100">
        {equity == null ? "—" : fmtCurrency(equity)}
      </div>
      {pl != null && (
        <div className={`mt-1 text-sm font-medium ${changeCls}`}>
          {arrow} {fmtCurrency(pl)}
          {/* Alpaca returns profit_loss_pct as a decimal fraction (0.0123 = 1.23%) → x100 for display. */}
          {plPct != null && <> ({(plPct * 100).toFixed(2)}%)</>}
          <span className="ml-1 text-slate-500">for the selected range</span>
        </div>
      )}
      <div className="mt-1 text-xs text-slate-500">
        {history?.account_scope ?? "account-level (shared)"}
      </div>
    </div>
  );
}
