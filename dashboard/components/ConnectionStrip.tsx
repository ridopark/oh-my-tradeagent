"use client";

import { frameClock, useLiveMarks } from "@/components/LiveMarks";
import {
  bidUsability,
  fmtAge,
  occKey,
  optionLight,
  partLight,
  serverLight,
  type Light,
  type Tone,
} from "@/lib/liveMarks";

const DOT: Record<Tone, string> = {
  green: "bg-emerald-400",
  amber: "bg-amber-400",
  red: "bg-rose-500",
  grey: "bg-slate-500",
};
const TEXT: Record<Tone, string> = {
  green: "text-slate-300",
  amber: "text-amber-300",
  red: "text-rose-300",
  grey: "text-slate-500",
};

/**
 * The /live pipeline strip (PLAN-2026-10-01 P4): Server (dashboard↔BFF, from the 1s marks poll),
 * Stock stream, Option prices, Broker (fill stream) and Discord watcher — each a coloured light with
 * an age. Anything the page cannot vouch for reads "unknown", never green; outside regular hours
 * the two market feeds read grey "market closed" rather than red.
 */
export function ConnectionStrip({ occs }: { occs: string[] }) {
  const s = useLiveMarks();
  const nowMs = s?.nowMs ?? null;
  const conn = s?.conn ?? null;
  const connAgeS =
    conn !== null && nowMs !== null ? Math.max(0, (nowMs - conn.atMs) / 1000) : null;
  const marketOpen = conn?.data.market_open ?? null;
  const clock = frameClock(s);
  const held = occs.map((o) => bidUsability(s?.frame?.marks.get(occKey(o)), clock));

  const items: { label: string; light: Light; reason?: string | null; hint?: string }[] = [
    {
      label: "Server",
      light:
        nowMs === null
          ? { tone: "amber", text: "unknown", ageS: null }
          : serverLight(s?.lastOkMs ?? null, nowMs, s?.failures ?? 0),
    },
    {
      label: "Stock stream",
      light: partLight(conn?.data.market_data?.equity, connAgeS, true, marketOpen),
      reason: conn?.data.market_data?.equity?.reason,
    },
    {
      label: "Option prices",
      light: optionLight(held, conn?.data.market_data?.option, connAgeS, marketOpen),
      reason: conn?.data.market_data?.option?.reason,
    },
    {
      label: "Broker",
      light: partLight(conn?.data.broker, connAgeS, false, marketOpen),
      reason: conn?.data.broker?.reason,
    },
    {
      label: "Discord watcher",
      light: partLight(conn?.data.discord, connAgeS, false, marketOpen),
      reason: conn?.data.discord?.reason,
      hint: "watch loop alive — does not prove the Discord session can read messages",
    },
  ];

  return (
    <div
      className="flex flex-wrap items-center gap-x-5 gap-y-1.5 rounded border border-slate-800 bg-slate-900 px-3 py-2 text-xs"
      aria-label="Pipeline connection status"
    >
      {items.map(({ label, light, reason, hint }) => {
        const age = light.ageS === null ? "" : ` · ${fmtAge(light.ageS)}`;
        const title = `${label}: ${light.text}${age}${reason ? ` (${reason})` : ""}${hint ? ` — ${hint}` : ""}`;
        return (
          <span key={label} className="inline-flex items-center gap-1.5" title={title}>
            <span className={`inline-block h-2 w-2 shrink-0 rounded-full ${DOT[light.tone]}`} />
            <span className="text-slate-400">{label}</span>
            <span className={`whitespace-nowrap ${TEXT[light.tone]}`}>
              {light.text}
              {age}
            </span>
          </span>
        );
      })}
    </div>
  );
}
