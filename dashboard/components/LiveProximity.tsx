"use client";

import { useEffect, useState } from "react";
// Type-only import (erased at compile): lib/bff is server-only, so only its shapes cross here. The
// data itself arrives over the /api/proximity route handler, never a direct BFF call.
import type {
  FeedState,
  PositionProximity,
  ProximityResponse,
  WatchlistProximity,
} from "@/lib/bff";

const POLL_MS = 4000;

export function LiveProximity() {
  const [data, setData] = useState<ProximityResponse | null>(null);
  const [stale, setStale] = useState(false);

  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const res = await fetch("/api/proximity", { cache: "no-store" });
        if (!res.ok) {
          throw new Error(String(res.status));
        }
        const json = (await res.json()) as ProximityResponse;
        if (!active) return;
        setData(json);
        setStale(false);
      } catch {
        // Keep the last good frame; just flag the banner. Transient BFF/route failures must not
        // tear the view down.
        if (active) setStale(true);
      } finally {
        if (active) timer = setTimeout(poll, POLL_MS);
      }
    };
    poll();
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, []);

  if (!data) {
    return (
      <p className="text-sm text-slate-400">
        {stale ? "Unable to reach the live feed. Retrying..." : "Loading live data..."}
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      {stale && (
        <div className="rounded border border-amber-700/60 bg-amber-950/40 px-3 py-2 text-sm text-amber-300">
          Reconnecting - showing the last received data.
        </div>
      )}

      <LivenessStrip liveness={data.liveness} />
      <WatchlistTable rows={data.watchlist} />
      <PositionsTable rows={data.positions} />
    </div>
  );
}

function LivenessStrip({ liveness }: { liveness: ProximityResponse["liveness"] }) {
  const unknown = liveness.status !== "ok";
  return (
    <section>
      <h2 className="mb-2 text-sm font-semibold text-slate-200">Market-data feeds</h2>
      {unknown ? (
        <p className="text-sm text-slate-500">Feed status unavailable (market-data unreachable).</p>
      ) : (
        <div className="flex flex-wrap gap-3">
          <FeedChip label="Equity" feed={liveness.equity} />
          <FeedChip label="Options" feed={liveness.option} />
        </div>
      )}
    </section>
  );
}

function FeedChip({ label, feed }: { label: string; feed?: FeedState }) {
  const connected = feed?.connected ?? false;
  return (
    <div className="flex items-center gap-2 rounded border border-slate-700 bg-slate-900 px-3 py-2 text-sm">
      <span
        className={`h-2.5 w-2.5 rounded-full ${connected ? "bg-emerald-500" : "bg-rose-500"}`}
        aria-hidden
      />
      <span className="text-slate-200">{label}</span>
      <span className="text-slate-500">{connected ? "connected" : "down"}</span>
      <span className="text-slate-500">- {ageLabel(feed?.lastTickAgeMs)}</span>
    </div>
  );
}

function WatchlistTable({ rows }: { rows: WatchlistProximity[] }) {
  return (
    <section>
      <h2 className="mb-2 text-sm font-semibold text-slate-200">
        Watchlist entry proximity ({rows.length})
      </h2>
      {rows.length === 0 ? (
        <p className="text-sm text-slate-500">No live watchlist legs.</p>
      ) : (
        <Table head={["Ticker", "Dir", "Last", "Trigger", "Band", "To trigger", "State"]}>
          {rows.map((r) => (
            <tr key={r.workflow_id} className="border-t border-slate-800">
              <Td>{r.ticker}</Td>
              <Td>{r.direction}</Td>
              <Td>{num(r.last_price)}</Td>
              <Td>{num(r.trigger_level)}</Td>
              <Td>
                {num(r.band_low)} - {num(r.band_high)}
              </Td>
              <Td>{pct(r.distance_to_trigger_pct)}</Td>
              <Td>{r.state}</Td>
            </tr>
          ))}
        </Table>
      )}
    </section>
  );
}

function PositionsTable({ rows }: { rows: PositionProximity[] }) {
  return (
    <section>
      <h2 className="mb-2 text-sm font-semibold text-slate-200">
        Position exit proximity ({rows.length})
      </h2>
      {rows.length === 0 ? (
        <p className="text-sm text-slate-500">No armed positions.</p>
      ) : (
        <Table
          head={["Contract", "Bid", "Stop", "Target", "To stop", "To target", "Peak", "Trail"]}
        >
          {rows.map((r) => (
            <tr key={r.workflow_id} className="border-t border-slate-800">
              <Td>{r.contract_symbol}</Td>
              <Td>{num(r.last_bid)}</Td>
              <Td>{num(r.stop_level)}</Td>
              <Td>{num(r.target_level)}</Td>
              <Td>{pct(r.distance_to_stop_pct)}</Td>
              <Td>{pct(r.distance_to_target_pct)}</Td>
              <Td>{num(r.peak_premium)}</Td>
              <Td>{r.trailing_armed ? "armed" : "-"}</Td>
            </tr>
          ))}
        </Table>
      )}
    </section>
  );
}

function Table({ head, children }: { head: string[]; children: React.ReactNode }) {
  return (
    <div className="overflow-x-auto rounded border border-slate-800">
      <table className="w-full text-sm">
        <thead>
          <tr className="bg-slate-900 text-left text-slate-400">
            {head.map((h) => (
              <th key={h} className="px-3 py-2 font-medium">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="text-slate-200">{children}</tbody>
      </table>
    </div>
  );
}

function Td({ children }: { children: React.ReactNode }) {
  return <td className="px-3 py-2">{children}</td>;
}

function num(v: string | number | null | undefined): string {
  return v == null ? "-" : String(v);
}

function pct(v: number | null | undefined): string {
  return v == null ? "-" : `${v.toFixed(2)}%`;
}

function ageLabel(ms: number | undefined): string {
  if (ms == null || ms < 0) return "no tick";
  if (ms < 1000) return "just now";
  return `${Math.round(ms / 1000)}s ago`;
}
