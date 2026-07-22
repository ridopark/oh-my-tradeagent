"use client";

import { useState } from "react";
import { AccountValueChart } from "@/components/AccountValueChart";
import { fmtCurrency } from "@/components/Pnl";
// Type-only import (erased at compile): lib/bff is server-only, only its shape crosses here.
import type { PortfolioHistory } from "@/lib/bff";

// Structure (a): the chart owns the history fetch and lifts each frame up via onData; the header
// reads profit_loss[last]/profit_loss_pct[last] from that same frame. Alpaca returns portfolio
// history with per-day-reset P&L, so the last element is the LATEST TRADING DAY's P&L ("today"),
// NOT a delta over the active range — it is ~identical across 1D/1W/1M/3M. The headline TOTAL is
// the live account snapshot (accountValue, passed in from the server page), NOT the chart's last
// point — see live/page.tsx for why (Alpaca's portfolio-history lags a cash deposit). The note below
// explains the resulting headline-vs-chart gap.
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
  // Only surface the lag note for a DEPOSIT-SCALE gap. The two values are independent live reads at
  // different instants (SSR account snapshot vs. the client portfolio-history last bar, itself
  // minutes-stale and possibly a prior close), so during market hours they routinely differ by
  // ordinary intraday drift — an exact-dollar comparison would fire the note almost constantly. The
  // gap also absorbs a full day's market move when the last bar is stale, so the tolerance is
  // generous: flag only when the gap exceeds the larger of $500 or 5% of the account value.
  const lagsChart =
    accountValue != null &&
    chartEquity != null &&
    Math.abs(accountValue - chartEquity) > Math.max(500, accountValue * 0.05);

  const up = (pl ?? 0) >= 0;
  const changeCls = up ? "text-emerald-400" : "text-rose-400";
  const arrow = up ? "▲" : "▼";

  return (
    <div>
      <div className="text-xs uppercase tracking-wide text-slate-500">
        Total account value
      </div>
      <div className="mt-1 text-3xl font-semibold text-slate-100">
        {fmtCurrency(accountValue)}
      </div>
      {pl != null && (
        <div className={`mt-1 text-sm font-medium ${changeCls}`}>
          {arrow} {fmtCurrency(pl)}
          {/* Alpaca returns profit_loss_pct as a decimal fraction (0.0123 = 1.23%) → x100 for display. */}
          {plPct != null && <> ({(plPct * 100).toFixed(2)}%)</>}
          <span className="ml-1 text-slate-500">today</span>
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
