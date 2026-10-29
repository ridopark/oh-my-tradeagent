"use client";

import { useId, useMemo, useState } from "react";
import {
  DarkpoolEmptyState,
  DarkpoolReadProblem,
  FreshnessNote,
  useDarkpoolRead,
  useNow,
} from "@/components/DarkpoolRead";
import {
  DEFAULT_MIN_NOTIONAL,
  etToday,
  fmtEtTime,
  fmtNotional,
  fmtPrice,
  fmtShares,
  isPollWindow,
  MIN_NOTIONAL_OPTIONS,
  minNotionalLabel,
  printsQuery,
  sidePillClass,
  SYMBOL_RE,
  type DarkpoolPrintsPage,
} from "@/lib/darkpool";

// The collector posts once per 60 s cycle; 30 s halves the worst-case display lag. These reads hit
// the dashboard DB only — never the broker — so they spend no Alpaca request budget.
const FEED_POLL_MS = 30_000;
// Coarse clock for today's date and the poll window; the 1 s age ticker lives in FreshnessNote.
const CLOCK_MS = 15_000;

/** Newest-first off-exchange prints, filterable by symbol and minimum notional. */
export function DarkpoolPrintsFeed() {
  const nowMs = useNow(CLOCK_MS);
  const now = nowMs === null ? null : new Date(nowMs);
  const today = etToday(now ?? undefined);
  const [symbolDraft, setSymbolDraft] = useState("");
  const [symbol, setSymbol] = useState("");
  const [minNotional, setMinNotional] = useState<number>(DEFAULT_MIN_NOTIONAL);
  const hintId = useId();

  const pollMs = now !== null && isPollWindow(now) ? FEED_POLL_MS : null;
  const read = useDarkpoolRead<DarkpoolPrintsPage>(
    `/api/darkpool/prints?${printsQuery(symbol, minNotional)}`,
    pollMs,
  );
  const draftInvalid = symbolDraft.trim() !== "" && !SYMBOL_RE.test(symbolDraft.trim());
  const items = useMemo(() => read.data?.items ?? [], [read.data]);

  // Memoized so the coarse clock re-render does not re-reconcile up to 200 rows.
  const body = useMemo(
    () =>
      items.map((p, i) => (
        <tr key={`${p.symbol}-${p.ts}-${p.trade_id ?? i}`} className="hover:bg-slate-800/50">
          <td className="whitespace-nowrap px-3 py-1.5 tabular-nums text-slate-300" title={p.ts}>
            {fmtEtTime(p.ts, today)}
          </td>
          <td className="px-3 py-1.5 font-medium text-slate-100">{p.symbol}</td>
          <td className="px-3 py-1.5 text-right tabular-nums text-slate-200">{fmtShares(p.size)}</td>
          <td className="px-3 py-1.5 text-right tabular-nums text-slate-100">{fmtNotional(p.notional)}</td>
          <td className="px-3 py-1.5 text-right tabular-nums text-slate-200">{fmtPrice(p.price)}</td>
          <td className="px-3 py-1.5">
            <span
              className={`rounded px-1.5 py-0.5 text-xs ${sidePillClass(p.side)}`}
              title={p.side === "unknown" ? "No NBBO quote within 5 s before the print" : undefined}
            >
              {p.side}
            </span>
          </td>
          <td className="whitespace-nowrap px-3 py-1.5 text-right tabular-nums text-slate-400">
            {p.bid === null && p.ask === null ? "—" : `${fmtPrice(p.bid)} × ${fmtPrice(p.ask)}`}
          </td>
        </tr>
      )),
    [items, today],
  );

  return (
    <section className="space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <form
          className="flex flex-wrap items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            if (!draftInvalid) setSymbol(symbolDraft.trim().toUpperCase());
          }}
        >
          <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-400">
            Off-exchange prints
          </h2>
          <input
            value={symbolDraft}
            onChange={(e) => setSymbolDraft(e.target.value)}
            onBlur={() => !draftInvalid && setSymbol(symbolDraft.trim().toUpperCase())}
            placeholder="Symbol (all)"
            aria-label="Symbol filter"
            aria-invalid={draftInvalid}
            aria-describedby={draftInvalid ? hintId : undefined}
            className={`w-32 rounded border bg-slate-900 px-2 py-1 text-sm uppercase text-slate-200 placeholder:normal-case ${
              draftInvalid ? "border-red-700" : "border-slate-700"
            }`}
          />
          <select
            value={minNotional}
            onChange={(e) => setMinNotional(Number(e.target.value))}
            aria-label="Minimum notional"
            className="rounded border border-slate-700 bg-slate-900 px-2 py-1 text-sm text-slate-200"
          >
            {MIN_NOTIONAL_OPTIONS.map((n) => (
              <option key={n} value={n}>
                {minNotionalLabel(n)}
              </option>
            ))}
          </select>
          {draftInvalid && (
            <span id={hintId} className="text-xs text-red-400">
              letters, digits, . or -
            </span>
          )}
        </form>
        <FreshnessNote read={read} pollMs={pollMs} />
      </div>

      {read.invalid ? (
        <p className="text-sm text-amber-400">The BFF rejected this filter.</p>
      ) : read.disabled ? (
        <DarkpoolEmptyState disabled />
      ) : read.data === null ? (
        <DarkpoolReadProblem read={read} />
      ) : items.length === 0 ? (
        <DarkpoolEmptyState
          what={`off-exchange prints${minNotional > 0 ? ` ≥ ${fmtNotional(minNotional)}` : ""}${
            symbol ? ` for ${symbol}` : ""
          }`}
        />
      ) : (
        <div className="max-h-[32rem] overflow-auto rounded border border-slate-800 bg-slate-900">
          <table className="min-w-full divide-y divide-slate-800 text-sm">
            <thead className="sticky top-0 bg-slate-800">
              <tr>
                <th scope="col" className="px-3 py-2 text-left font-medium text-slate-400">Time (ET)</th>
                <th scope="col" className="px-3 py-2 text-left font-medium text-slate-400">Symbol</th>
                <th scope="col" className="px-3 py-2 text-right font-medium text-slate-400">Size</th>
                <th scope="col" className="px-3 py-2 text-right font-medium text-slate-400">Notional</th>
                <th scope="col" className="px-3 py-2 text-right font-medium text-slate-400">Price</th>
                <th scope="col" className="px-3 py-2 text-left font-medium text-slate-400">Side</th>
                <th
                  scope="col"
                  className="px-3 py-2 text-right font-medium text-slate-400"
                  title="Last NBBO quote within 5 s before the print"
                >
                  NBBO at print
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800">{body}</tbody>
          </table>
        </div>
      )}
    </section>
  );
}
