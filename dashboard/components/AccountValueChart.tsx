"use client";

import { useEffect, useState } from "react";
import {
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { TooltipContentProps } from "recharts";
// Type-only import (erased at compile): lib/bff is server-only, so only its shape crosses here. The
// data itself arrives over the /api/portfolio-history route handler, never a direct BFF call.
import type { PortfolioHistory } from "@/lib/bff";

// Range tabs in RH order. 1D is the default; only 1D polls (intraday), the others fetch once per
// selection (a 1M/1Y line does not move enough to justify polling).
const RANGES = ["1D", "1W", "1M", "3M", "YTD", "1Y"] as const;
type Range = (typeof RANGES)[number];

const POLL_MS = 15000;

// Axis ticks are drawn in market time, not the browser's: an intraday tick that reads 9:30 AM must
// mean the open regardless of where the operator is sitting.
const ET = "America/New_York";

const usdTick = (v: number) => `$${Math.round(v).toLocaleString("en-US")}`;

// 1D is intraday (clock time); the multi-day ranges label the day, and 1Y adds the year because it
// spans two of them.
const timeTick = (ms: number) =>
  new Date(ms).toLocaleTimeString("en-US", {
    timeZone: ET,
    hour: "numeric",
    minute: "2-digit",
  });
const dayTick = (ms: number) =>
  new Date(ms).toLocaleDateString("en-US", {
    timeZone: ET,
    month: "short",
    day: "numeric",
  });
const monthTick = (ms: number) =>
  new Date(ms).toLocaleDateString("en-US", {
    timeZone: ET,
    month: "short",
    year: "2-digit",
  });

const xTickFor = (range: Range) =>
  range === "1D" ? timeTick : range === "1Y" ? monthTick : dayTick;

// The tooltip spells the moment out in full (the axis tick is abbreviated to fit): 1D points are
// intraday so they carry a clock time, the other ranges are one bar per day.
const tooltipStamp = (range: Range, ms: number) => {
  const opts: Intl.DateTimeFormatOptions = {
    timeZone: ET,
    month: "short",
    day: "numeric",
    year: "numeric",
  };
  if (range === "1D") {
    opts.hour = "numeric";
    opts.minute = "2-digit";
    opts.timeZoneName = "short";
  }
  return new Date(ms).toLocaleString("en-US", opts);
};

const usdExact = (v: number) =>
  v.toLocaleString("en-US", { style: "currency", currency: "USD" });

function ChartTooltip({
  active,
  payload,
  range,
}: TooltipContentProps & { range: Range }) {
  const point = payload?.[0]?.payload as
    | { t: number; equity: number }
    | undefined;
  if (!active || !point) return null;
  return (
    <div className="rounded border border-slate-700 bg-slate-950/95 px-3 py-2 text-sm shadow-lg">
      <div className="font-semibold text-slate-100">
        {usdExact(point.equity)}
      </div>
      <div className="text-xs text-slate-400">
        {tooltipStamp(range, point.t)}
      </div>
    </div>
  );
}

export function AccountValueChart({
  onData,
}: {
  // Lift the fetched history to the parent so the header's +$X (Y%) reads the SAME profit_loss /
  // base_value the chart drew — one fetch, one baseline.
  onData?: (h: PortfolioHistory) => void;
}) {
  const [range, setRange] = useState<Range>("1D");
  const [data, setData] = useState<PortfolioHistory | null>(null);
  const [stale, setStale] = useState(false);

  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const res = await fetch(`/api/portfolio-history?range=${range}`, {
          cache: "no-store",
        });
        if (!res.ok) {
          throw new Error(String(res.status));
        }
        const json = (await res.json()) as PortfolioHistory;
        if (!active) return;
        setData(json);
        setStale(false);
        onData?.(json);
      } catch {
        // Keep the last good frame; just flag the banner. Transient BFF/route failures must not
        // tear the chart down.
        if (active) setStale(true);
      } finally {
        // Only the 1D tab polls (intraday); other ranges fetch once per selection.
        if (active && range === "1D") timer = setTimeout(poll, POLL_MS);
      }
    };
    poll();
    return () => {
      active = false;
      clearTimeout(timer);
    };
    // Re-fetch whenever the range changes. onData is a stable callback from the parent.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [range]);

  // Range-aware change vs base_value: drives the line color (emerald up / rose down), mirroring the
  // threshold style in Pnl.tsx. Uses the last profit_loss entry when present.
  const lastPl =
    data && data.profit_loss.length > 0
      ? data.profit_loss[data.profit_loss.length - 1]
      : 0;
  const up = lastPl >= 0;
  const lineColor = up ? "#10b981" : "#f43f5e";

  // Timestamps arrive as epoch SECONDS; the axis formatters take millis.
  const points =
    data?.timestamps.map((t, i) => ({ t: t * 1000, equity: data.equity[i] })) ??
    [];
  const hasData = points.length > 0;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap gap-1">
        {RANGES.map((r) => (
          <button
            key={r}
            type="button"
            onClick={() => setRange(r)}
            className={`rounded px-3 py-1 text-sm ${
              r === range
                ? "bg-slate-700 font-semibold text-slate-100"
                : "text-slate-400 hover:bg-slate-800 hover:text-slate-200"
            }`}
          >
            {r}
          </button>
        ))}
      </div>

      {stale && (
        <div className="rounded border border-amber-700/60 bg-amber-950/40 px-3 py-2 text-sm text-amber-300">
          Reconnecting - showing the last received data.
        </div>
      )}

      <div className="h-64 w-full rounded border border-slate-800 bg-slate-900 p-2">
        {!hasData ? (
          <div className="flex h-full items-center justify-center text-sm text-slate-500">
            {data ? "Account history unavailable." : "Loading account history..."}
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart
              data={points}
              margin={{ top: 8, right: 8, bottom: 0, left: 0 }}
            >
              <XAxis
                dataKey="t"
                tickFormatter={xTickFor(range)}
                tick={{ fill: "#64748b", fontSize: 11 }}
                tickLine={false}
                axisLine={{ stroke: "#334155" }}
                minTickGap={40}
              />
              <YAxis
                domain={["auto", "auto"]}
                tickFormatter={usdTick}
                tick={{ fill: "#64748b", fontSize: 11 }}
                tickLine={false}
                axisLine={false}
                width={72}
              />
              <Tooltip
                content={(props) => <ChartTooltip {...props} range={range} />}
                cursor={{ stroke: "#64748b", strokeDasharray: "3 3" }}
                isAnimationActive={false}
              />
              <ReferenceLine
                y={data?.base_value}
                stroke="#64748b"
                strokeDasharray="4 4"
              />
              <Line
                type="monotone"
                dataKey="equity"
                stroke={lineColor}
                strokeWidth={2}
                dot={false}
                activeDot={{
                  r: 4,
                  fill: lineColor,
                  stroke: "#0f172a",
                  strokeWidth: 2,
                }}
                isAnimationActive={false}
              />
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}
