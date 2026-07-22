"use client";

import { useState } from "react";
import { AccountValueChart } from "@/components/AccountValueChart";
import { fmtCurrency } from "@/components/Pnl";
// Type-only import (erased at compile): lib/bff is server-only, only its shape crosses here.
import type { PortfolioHistory } from "@/lib/bff";

// Structure (a): the chart owns the history fetch and lifts each frame up via onData. The "today"
// line prefers the live intraday figure (todayPl = equity - last_equity, from the same live account
// snapshot as the headline TOTAL) and falls back to the chart frame's profit_loss[last] only when
// that intraday figure is unavailable. profit_loss[last] is Alpaca portfolio-history's LAST COMPLETED
// daily bar — for a daily-bar range that is YESTERDAY's session, not live intraday (the bug this
// fixes). The headline TOTAL is the live account snapshot (accountValue, passed in from the server
// page), NOT the chart's last point — see live/page.tsx (Alpaca portfolio-history lags a deposit).
export function LiveAccount({
  accountValue,
  accountScope,
  todayPl,
  todayPlPct,
}: {
  accountValue: number | null;
  accountScope: string;
  // Live intraday "today" P&L / pct (equity - last_equity), or null when last_equity is unavailable
  // (then the "today" line falls back to the chart's last completed daily bar).
  todayPl?: number | null;
  todayPlPct?: number | null;
}) {
  const [history, setHistory] = useState<PortfolioHistory | null>(null);

  return (
    <section className="flex flex-col gap-4">
      <AccountTotal
        history={history}
        accountValue={accountValue}
        accountScope={accountScope}
        todayPl={todayPl ?? null}
        todayPlPct={todayPlPct ?? null}
      />
      <AccountValueChart onData={setHistory} />
    </section>
  );
}

// Pure: formats a P&L ($, fraction) pair into a display change string. "▲ $X (Y%)" when both are
// present (up = pl >= 0), "▲ $X" when the $ is known but the % is not, and an em-dash placeholder
// only when the $ itself is unknown (isNull). The $-without-% case is real: the BFF nulls
// range_pl_pct alone whenever the Modified-Dietz denominator is undefined (base_value=0, a
// single-timestamp window), and the dollar figure is still a true number there — hiding it would
// throw away a good answer, and would be inconsistent with the Today line, which shows $ regardless.
// `up` is meaningful only when !isNull. Alpaca-style pct is a decimal fraction (0.0123 = 1.23%).
function formatChange(
  pl: number | null,
  plPct: number | null,
): { text: string; up: boolean; isNull: boolean } {
  if (pl == null) {
    return { text: "—", up: true, isNull: true };
  }
  const up = pl >= 0;
  const arrow = up ? "▲" : "▼";
  const pct = plPct == null ? "" : ` (${(plPct * 100).toFixed(2)}%)`;
  return {
    text: `${arrow} ${fmtCurrency(pl)}${pct}`,
    up,
    isNull: false,
  };
}

function AccountTotal({
  history,
  accountValue,
  accountScope,
  todayPl,
  todayPlPct,
}: {
  history: PortfolioHistory | null;
  accountValue: number | null;
  accountScope: string;
  todayPl: number | null;
  todayPlPct: number | null;
}) {
  // "today" = live intraday P&L (equity - last_equity from the account snapshot) when present; this
  // is the GENUINE today figure. Fall back to portfolio-history's last COMPLETED daily bar
  // (profit_loss[last]) ONLY when the intraday figure is unavailable (null last_equity). The %
  // follows the $: use todayPlPct with the intraday $, else the history bar's pct with its $.
  // TODO(#today): no dashboard test runner (no jest/vitest) — behaviors verified via tsc + next
  // build. Cases: (1) todayPl present -> "today" shows todayPl/todayPlPct (prod_real: -$1,782.50);
  // (2) todayPl null -> falls back to profit_loss[last]/profit_loss_pct[last]; (3) todayPl present
  // but todayPlPct null (last_equity <= 0) -> $ shown without %.
  const histPl =
    history && history.profit_loss.length > 0
      ? history.profit_loss[history.profit_loss.length - 1]
      : null;
  const histPlPct =
    history && history.profit_loss_pct.length > 0
      ? history.profit_loss_pct[history.profit_loss_pct.length - 1]
      : null;
  const pl = todayPl != null ? todayPl : histPl;
  const plPct = todayPl != null ? todayPlPct : histPlPct;

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

  // Deposit-adjusted trading return over the selected range (BFF-computed; null when cash flows are
  // unavailable or the denominator is undefined). Distinct from the per-day "today" number above.
  const rangeChange = formatChange(
    history ? history.range_pl : null,
    history ? history.range_pl_pct : null,
  );
  const rangeCls = rangeChange.isNull
    ? "text-slate-500"
    : rangeChange.up
      ? "text-emerald-400"
      : "text-rose-400";

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
      <div className="mt-1 text-sm font-medium">
        <span className="text-slate-500">This range (excl. deposits)</span>{" "}
        <span className={rangeCls}>{rangeChange.text}</span>
      </div>
      {rangeChange.isNull && (
        <div className="mt-0.5 text-xs text-slate-500">
          excludes deposits &amp; withdrawals; unavailable for this range
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
