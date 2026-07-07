"use client";

import { useState } from "react";
import { AccountValueChart } from "@/components/AccountValueChart";
import { fmtCurrency } from "@/components/Pnl";
// Type-only import (erased at compile): lib/bff is server-only, only its shape crosses here.
import type { PortfolioHistory } from "@/lib/bff";

// Structure (a): the chart owns the history fetch and lifts each frame up via onData; the header's
// "+$X (Y%) for the selected range" reads the SAME profit_loss the chart drew, so the range delta
// always matches the active tab. The headline TOTAL, however, is the live net-liquidation equity
// (accountValue, from GET /v2/account — passed in from the server page, same source as /status), NOT
// the chart's last equity point: Alpaca's portfolio-history lags a cash deposit by up to a trading
// day, so the chart's last point would show a stale total right after funding. The live snapshot
// reflects deposits immediately, at the cost of the headline sitting above the chart's right edge
// until portfolio-history catches up (the note below explains that gap).
export function LiveAccount({
  accountValue,
  accountScope,
}: {
  accountValue: number | null;
  accountScope: string;
}) {
  const [history, setHistory] = useState<PortfolioHistory | null>(null);

  return (
    <section className="flex flex-col gap-4">
      <AccountTotal
        history={history}
        accountValue={accountValue}
        accountScope={accountScope}
      />
      <AccountValueChart onData={setHistory} />
    </section>
  );
}

function AccountTotal({
  history,
  accountValue,
  accountScope,
}: {
  history: PortfolioHistory | null;
  accountValue: number | null;
  accountScope: string;
}) {
  const pl =
    history && history.profit_loss.length > 0
      ? history.profit_loss[history.profit_loss.length - 1]
      : null;
  const plPct =
    history && history.profit_loss_pct.length > 0
      ? history.profit_loss_pct[history.profit_loss_pct.length - 1]
      : null;

  // Chart's last equity point — used ONLY to flag the deposit-lag gap, not as the headline total.
  const chartEquity =
    history && history.equity.length > 0
      ? history.equity[history.equity.length - 1]
      : null;
  const lagsChart =
    accountValue != null &&
    chartEquity != null &&
    Math.round(accountValue) !== Math.round(chartEquity);

  const up = (pl ?? 0) >= 0;
  const changeCls = up ? "text-emerald-400" : "text-rose-400";
  const arrow = up ? "▲" : "▼";

  return (
    <div>
      <div className="text-xs uppercase tracking-wide text-slate-500">
        Total account value
      </div>
      <div className="mt-1 text-3xl font-semibold text-slate-100">
        {accountValue == null ? "—" : fmtCurrency(accountValue)}
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
        {accountScope}
      </div>
      {lagsChart && (
        <div className="mt-1 text-xs text-amber-400/80">
          Live account value; the chart reflects Alpaca&apos;s portfolio history, which can lag a
          deposit by up to a trading day.
        </div>
      )}
    </div>
  );
}
