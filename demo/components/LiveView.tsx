"use client";

import type { ReactNode } from "react";
import { DataTable, type Column } from "@/components/DataTable";
import { ContractChartLinks, ContractLink, occCompactParts } from "@/components/ContractLink";
import {
  LiveCostValue,
  LiveMarksFrame,
  LivePnlToday,
  LivePnlTotal,
  LivePremium,
  LiveUnderlying,
  type LiveMarksState,
} from "@/components/LiveMarks";
import { LocalTime } from "@/components/LocalTime";
import { Pnl, fmtCurrency } from "@/components/Pnl";
import { PositionsUnavailable } from "@/components/PositionsUnavailable";
import { num } from "@/lib/liveMarks";
import { tradeFill, tradePnl, type Trade } from "@/lib/trades";
import type { Order, Portfolio } from "@/lib/types";

// The demo's Live page: the read-only parts of the dashboard's /live, drawn from the same published
// files as the other demo pages. It has no buttons and sends nothing; every operator control on the
// real page (force exit, trim, trailing stop, manual entry, adopt) is simply not here.

const ACTIVITY_LIMIT = 5;

type Row = Record<string, unknown>;
const occOf = (row: Row) => String(row.contract_symbol ?? "");

function premium(row: Row, inline = false): ReactNode {
  return (
    <LivePremium
      occ={occOf(row)}
      entry={num(row.entry_premium)}
      serverMark={num(row.current_price)}
      inline={inline}
    />
  );
}

function underlying(row: Row, inline = false): ReactNode {
  return (
    <LiveUnderlying
      occ={occOf(row)}
      entry={num(row.underlying_spot_entry)}
      serverPrice={num(row.underlying_price)}
      ticker={occCompactParts(occOf(row))?.root ?? null}
      inline={inline}
    />
  );
}

function costValue(row: Row): ReactNode {
  return (
    <LiveCostValue
      occ={occOf(row)}
      qty={num(row.remaining_qty)}
      cost={row.open_notional as string | number | null}
      serverValue={marketValue(row)}
    />
  );
}

function pnlToday(row: Row, inline = false): ReactNode {
  return (
    <LivePnlToday
      occ={occOf(row)}
      qty={num(row.remaining_qty)}
      lastday={num(row.lastday_price)}
      serverValue={num(row.unrealized_intraday_pl)}
      inline={inline}
    />
  );
}

function pnlTotal(row: Row): ReactNode {
  return (
    <LivePnlTotal
      occ={occOf(row)}
      qty={num(row.remaining_qty)}
      entry={num(row.entry_premium)}
      serverValue={num(row.unrealized_pl)}
    />
  );
}

/** remaining_qty × mark × 100, or null for a position with no mark. */
function marketValue(row: Row): number | null {
  const qty = num(row.remaining_qty);
  const mark = num(row.current_price);
  return qty === null || mark === null ? null : qty * mark * 100;
}

const COLUMNS: Column[] = [
  {
    key: "contract_symbol",
    label: "Contract",
    render: (v) =>
      typeof v === "string" && v.trim() ? (
        <ContractLink occ={v} compact />
      ) : (
        <span className="text-slate-500">—</span>
      ),
  },
  { key: "remaining_qty", label: "Qty" },
  { key: "entry_premium", label: "Premium", render: (_v, row) => premium(row) },
  { key: "underlying_spot_entry", label: "Underlying", render: (_v, row) => underlying(row) },
  { key: "open_notional", label: "Cost → Value", render: (_v, row) => costValue(row) },
  { key: "unrealized_intraday_pl", label: "P&L (today)", render: (_v, row) => pnlToday(row) },
  { key: "unrealized_pl", label: "P&L (total)", render: (_v, row) => pnlTotal(row) },
];

function HoldingCard({ row }: { row: Row }) {
  return (
    <div className="rounded border border-slate-800 bg-slate-900 px-3 py-2 text-sm">
      <div className="flex items-baseline justify-between gap-2">
        <ContractLink occ={occOf(row)} compact stack={false} />
        <span className="shrink-0 font-medium text-slate-200">&times;{String(row.remaining_qty)}</span>
      </div>
      <dl className="mt-1 grid grid-cols-[max-content_minmax(0,1fr)] gap-x-3 gap-y-0.5 text-xs text-slate-400">
        <dt>premium</dt>
        <dd className="text-slate-200">{premium(row, true)}</dd>
        <dt>cost &rarr; value</dt>
        <dd className="text-slate-200">{costValue(row)}</dd>
        <dt>underlying</dt>
        <dd className="text-slate-200">{underlying(row, true)}</dd>
        <dt>P&amp;L</dt>
        <dd>{pnlTotal(row)}</dd>
        <dt>today</dt>
        <dd>{pnlToday(row, true)}</dd>
      </dl>
    </div>
  );
}

function fillLabel(t: Trade): ReactNode {
  const f = tradeFill(t);
  const parts: string[] = [];
  if (f.qty !== null) parts.push(`×${f.qty}`);
  if (f.avg_fill_price !== null) parts.push(`@ ${fmtCurrency(f.avg_fill_price)}`);
  const rest = parts.join(" ");
  if (!f.option_symbol) return rest || "—";
  return (
    <>
      <ContractChartLinks occ={f.option_symbol} />
      {rest && ` ${rest}`}
    </>
  );
}

function orderLabel(o: Order): ReactNode {
  const parts = [
    o.filled_qty != null && o.filled_qty !== o.qty ? `×${o.filled_qty}/${o.qty}` : `×${o.qty}`,
  ];
  if (o.avg_fill_price != null) parts.push(`@ ${fmtCurrency(o.avg_fill_price)}`);
  else if (o.limit_price != null) parts.push(`lmt ${fmtCurrency(o.limit_price)}`);
  return (
    <>
      {o.side} <ContractChartLinks occ={o.option_symbol} /> {parts.join(" ")}
    </>
  );
}

function ActivityStrip({
  title,
  href,
  empty,
  rows,
}: {
  title: string;
  href: string;
  empty: string;
  rows: { primary: ReactNode; secondary: string; when: string; pnl?: number | null }[] | null;
}) {
  return (
    <section>
      <div className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-200">{title}</h2>
        <a href={href} className="text-xs text-slate-400 hover:text-white">
          View all →
        </a>
      </div>
      {rows === null ? (
        <p className="text-sm text-slate-500">Loading…</p>
      ) : rows.length === 0 ? (
        <p className="text-sm text-slate-500">{empty}</p>
      ) : (
        <ul className="divide-y divide-slate-800 rounded border border-slate-800 bg-slate-900">
          {rows.map((r, i) => (
            <li key={i} className="flex items-center justify-between px-3 py-2 text-sm">
              <div className="min-w-0">
                <div className="truncate text-slate-200">
                  {r.primary}
                  {r.pnl !== null && r.pnl !== undefined && (
                    <span className={r.pnl >= 0 ? " text-emerald-400" : " text-rose-400"}>
                      {" "}
                      {r.pnl >= 0 ? "+" : ""}
                      {fmtCurrency(r.pnl)}
                    </span>
                  )}
                </div>
                <div className="truncate text-xs text-slate-500">{r.secondary}</div>
              </div>
              <div className="shrink-0 pl-3 text-xs text-slate-500">
                <LocalTime iso={r.when} />
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Stat({ label, children, note }: { label: string; children: ReactNode; note?: string }) {
  return (
    <div className="rounded border border-slate-800 bg-slate-900 px-4 py-3">
      <div className="text-xs text-slate-400">{label}</div>
      <div className="mt-1 text-lg font-semibold text-slate-100">{children}</div>
      {note && <div className="mt-1 text-xs text-slate-500">{note}</div>}
    </div>
  );
}

/** Sum of a numeric field over the broker accounts; null when none of them carries it. */
function sumEquity(p: Portfolio, field: "equity" | "today_pl"): number | null {
  return p.account_equity.reduce<number | null>((sum, a) => {
    const n = num((a as unknown as Row)[field]);
    return n === null ? sum : (sum ?? 0) + n;
  }, null);
}

export function LiveView({
  p,
  marks,
  trades,
  orders,
}: {
  p: Portfolio;
  marks: LiveMarksState;
  trades: Trade[] | null;
  orders: Order[] | null;
}) {
  const degraded = p.open_positions_degraded === true;
  const count = p.open_positions_count;
  const rows = p.open_positions as unknown as Row[];
  const value = rows.reduce<number | null>((sum, row) => {
    const v = marketValue(row);
    return v === null ? sum : (sum ?? 0) + v;
  }, null);
  const accountValue = sumEquity(p, "equity");
  const todayPl = sumEquity(p, "today_pl");

  return (
    <LiveMarksFrame state={marks}>
      <main className="mx-auto flex max-w-6xl flex-col gap-8 px-4 py-6">
        <div>
          <h1 className="mb-1 text-xl font-semibold text-slate-100">Live</h1>
          <p className="text-sm text-slate-400">
            Open holdings re-marked at the live bid every few seconds, and the latest activity. A
            price that is not live (market closed, or the feed is behind) is shown dimmed.
          </p>
        </div>

        <section className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Stat label="Account value" note="Paper account. Shown 15 minutes late.">
            {fmtCurrency(accountValue)}
          </Stat>
          <Stat label="Today" note="Change since the previous close. Shown 15 minutes late.">
            <Pnl value={todayPl} />
          </Stat>
        </section>

        <section>
          <div className="mb-2 flex items-baseline justify-between">
            <h2 className="text-sm font-semibold text-slate-200">
              Holdings ({degraded ? "?" : count})
            </h2>
            {!degraded && count > 0 && (
              <span className="text-xs text-slate-400">
                Cost{" "}
                <span className="font-medium text-slate-200">
                  {fmtCurrency(p.sum_open_notional)}
                </span>
                {" · "}Value{" "}
                <span className="font-medium text-slate-200">{fmtCurrency(value)}</span>
              </span>
            )}
          </div>
          {degraded ? (
            <PositionsUnavailable />
          ) : (
            <>
              <div className="flex flex-col gap-3 xl:hidden">
                {count === 0 ? (
                  <p className="text-sm text-slate-400">No open positions.</p>
                ) : (
                  rows.map((row, i) => <HoldingCard key={`${occOf(row)}#${i}`} row={row} />)
                )}
              </div>
              <div className="hidden xl:block">
                <DataTable empty="No open positions." columns={COLUMNS} rows={p.open_positions} />
              </div>
            </>
          )}
        </section>

        <section className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          <ActivityStrip
            title="Recent fills"
            href="/trades/"
            empty="No fills yet."
            rows={
              trades &&
              trades.slice(0, ACTIVITY_LIMIT).map((t) => ({
                primary: fillLabel(t),
                secondary: `${t.kind} · ${t.strategy_id}`,
                when: t.occurred_at,
                pnl: tradePnl(t),
              }))
            }
          />
          <ActivityStrip
            title="Recent orders"
            href="/orders/"
            empty="No orders yet."
            rows={
              orders &&
              orders.slice(0, ACTIVITY_LIMIT).map((o) => ({
                primary: orderLabel(o),
                secondary: o.state,
                when: o.recorded_at,
              }))
            }
          />
        </section>
      </main>
    </LiveMarksFrame>
  );
}
