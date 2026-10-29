import { ChartLink } from "@/components/ContractLink";
import {
  levelLabel,
  levelsState,
  PRINT_RETENTION_DAYS,
  type DarkpoolSymbolSummary,
} from "@/lib/darkpool";

/**
 * One symbol's strip of its top-3 large-print (≥ $200k) off-exchange price levels, plus the same
 * Yahoo chart link /live uses for an underlying (/live has no per-symbol view to deep-link into).
 *
 * Levels come from prints (90-day retention), the table's ratios from bars (400 days), so an empty
 * strip is labelled by WHY it is empty — an expired session never reads as "no large prints".
 */
export function DarkpoolLevels({
  row,
  date,
  today,
}: {
  row: DarkpoolSymbolSummary;
  date: string;
  today: string;
}) {
  const state = levelsState(row, date, today);
  return (
    <div className="flex flex-wrap items-center gap-1.5 text-xs">
      {state === "levels" &&
        row.levels.slice(0, 3).map((l, i) => (
          <span
            // Index too: adjacent 0.1% buckets can round to the same 2-dp price on a cheap name.
            key={`${l.price}-${i}`}
            className="whitespace-nowrap rounded bg-slate-800 px-1.5 py-0.5 text-slate-200"
          >
            {levelLabel(l)}
          </span>
        ))}
      {state === "expired" && (
        <span
          className="text-slate-500"
          title={`Off-exchange prints are kept ${PRINT_RETENTION_DAYS} days; the volume columns come from longer-lived 5-minute bars.`}
        >
          prints expired
        </span>
      )}
      {state === "none" && <span className="text-slate-500">no large prints</span>}
      {state === "missing" && (
        <span
          className="text-amber-400"
          title="The 5-minute bars counted large prints, but no print rows are stored for this session."
        >
          prints missing
        </span>
      )}
      <ChartLink symbol={row.symbol} title={`${row.symbol} chart on Yahoo Finance`}>
        chart
      </ChartLink>
    </div>
  );
}
