"use client";

import { useMemo, useState } from "react";
import { DarkpoolLevels } from "@/components/DarkpoolLevels";
import {
  DarkpoolEmptyState,
  DarkpoolReadProblem,
  FreshnessNote,
  useDarkpoolRead,
  useNow,
} from "@/components/DarkpoolRead";
import {
  etToday,
  fmtRatio,
  fmtVolume,
  isPollWindow,
  isPreOpen,
  sortSummary,
  type DarkpoolSummary,
  type SummarySortKey,
} from "@/lib/darkpool";

// Bars land once per collector cycle (60 s), so re-reading faster only re-reads the same rows.
const SUMMARY_POLL_MS = 60_000;
// Coarse clock for today's date and the poll window; the 1 s age ticker lives in FreshnessNote.
const CLOCK_MS = 15_000;

const SORTABLE: { key: SummarySortKey; label: string; title: string }[] = [
  { key: "dp_volume", label: "Off-exch vol", title: "Off-exchange share volume, RTH" },
  { key: "dp_share", label: "Off-exch share", title: "Off-exchange volume / total volume, RTH" },
  {
    key: "buy_ratio",
    label: "Buy ratio",
    title: "Off-exchange buy volume / (buy + sell); mid and unknown prints are excluded",
  },
  {
    key: "late_buy_ratio",
    label: "Late buy ratio",
    title: "Buy ratio over the 14:00–15:30 ET buckets",
  },
  { key: "large_count", label: "Large prints", title: "Off-exchange prints ≥ $200k notional" },
];

/** Per-symbol off-exchange summary for one ET session (RTH only), with a date picker and sorting. */
export function DarkpoolSummaryTable() {
  const nowMs = useNow(CLOCK_MS);
  const now = nowMs === null ? null : new Date(nowMs);
  const today = etToday(now ?? undefined);
  const [date, setDate] = useState(() => etToday());
  const [sort, setSort] = useState<{ key: SummarySortKey; dir: "asc" | "desc" }>({
    key: "dp_share",
    dir: "desc",
  });

  // Only today's session changes under us, and only inside the poll window.
  const pollMs = now !== null && date === today && isPollWindow(now) ? SUMMARY_POLL_MS : null;
  const preOpen = now !== null && date === today && isPreOpen(now);
  const read = useDarkpoolRead<DarkpoolSummary>(
    `/api/darkpool/summary?date=${encodeURIComponent(date)}`,
    pollMs,
  );
  const sessionDate = read.data?.date ?? date;
  const rows = useMemo(
    () => (read.data ? sortSummary(read.data.symbols, sort.key, sort.dir) : []),
    [read.data, sort],
  );
  // Memoized so the coarse clock re-render does not re-reconcile every row.
  const body = useMemo(
    () =>
      rows.map((r) => (
        <tr key={r.symbol} className="hover:bg-slate-800/50">
          <td className="px-3 py-2 align-top font-medium text-slate-100">{r.symbol}</td>
          <td
            className="px-3 py-2 text-right align-top tabular-nums text-slate-200"
            title={`of ${fmtVolume(r.total_volume)} total`}
          >
            {fmtVolume(r.dp_volume)}
          </td>
          <td className="px-3 py-2 text-right align-top tabular-nums text-slate-200">
            {fmtRatio(r.dp_share)}
          </td>
          <td className="px-3 py-2 text-right align-top tabular-nums text-slate-200">
            {fmtRatio(r.buy_ratio)}
          </td>
          <td className="px-3 py-2 text-right align-top tabular-nums text-slate-200">
            {fmtRatio(r.late_buy_ratio)}
          </td>
          <td className="px-3 py-2 text-right align-top tabular-nums text-slate-200">
            {r.large_count}
          </td>
          <td className="px-3 py-2 align-top">
            <DarkpoolLevels row={r} date={sessionDate} today={today} />
          </td>
        </tr>
      )),
    [rows, sessionDate, today],
  );

  const onSort = (key: SummarySortKey) =>
    setSort((s) => (s.key === key ? { key, dir: s.dir === "desc" ? "asc" : "desc" } : { key, dir: "desc" }));

  return (
    <section className="space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-400">
            Session summary
          </h2>
          <input
            type="date"
            value={date}
            max={today}
            onChange={(e) => e.target.value && setDate(e.target.value)}
            className="rounded border border-slate-700 bg-slate-900 px-2 py-1 text-sm text-slate-200"
            aria-label="Session date (ET)"
          />
          <span className="text-xs text-slate-500">ET session, 09:30–16:00 only</span>
        </div>
        <FreshnessNote read={read} pollMs={pollMs} />
      </div>

      {read.invalid ? (
        <p className="text-sm text-amber-400">The BFF rejected the date {date}.</p>
      ) : read.disabled ? (
        <DarkpoolEmptyState disabled />
      ) : read.data === null ? (
        <DarkpoolReadProblem read={read} />
      ) : rows.length === 0 ? (
        <DarkpoolEmptyState
          preOpen={preOpen}
          what={`off-exchange data for the ${read.data.date} session`}
        />
      ) : (
        <div className="overflow-x-auto rounded border border-slate-800 bg-slate-900">
          <table className="min-w-full divide-y divide-slate-800 text-sm">
            <thead className="bg-slate-800/50">
              <tr>
                <th scope="col" className="px-3 py-2 text-left font-medium text-slate-400">
                  Symbol
                </th>
                {SORTABLE.map((c) => {
                  const active = sort.key === c.key;
                  return (
                    <th
                      key={c.key}
                      scope="col"
                      aria-sort={active ? (sort.dir === "desc" ? "descending" : "ascending") : "none"}
                      className="px-3 py-2 text-right font-medium text-slate-400"
                    >
                      <button
                        type="button"
                        title={c.title}
                        onClick={() => onSort(c.key)}
                        className={`whitespace-nowrap hover:text-slate-200 ${
                          active ? "text-slate-100" : ""
                        }`}
                      >
                        {c.label}
                        {active ? (sort.dir === "desc" ? " ▼" : " ▲") : ""}
                      </button>
                    </th>
                  );
                })}
                <th scope="col" className="px-3 py-2 text-left font-medium text-slate-400">
                  Large-print levels (top 3)
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
