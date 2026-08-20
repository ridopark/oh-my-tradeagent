"use client";

import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import type { TrailLivenessResponse } from "@/lib/bff";

// Matches LiveProximity's cadence. Deliberately NOT faster: the thing being observed is a 500ms
// server-side poll whose liveness is judged on a 10s staleness window, so polling the browser
// harder buys no earlier detection — it only multiplies BFF load by the number of open tabs.
const POLL_MS = 4000;

// How long the arrival flash stays lit. Long enough to notice on a glance, short enough that two
// ticks a second apart read as two blinks rather than one continuous glow.
const FLASH_MS = 700;

export type FeedStatus = "live" | "orphaned" | "unknown";

export interface TrailLivenessEntry {
  feedStatus: FeedStatus;
  ticksReceived: number;
  lastTickObservedAt: string | null;
  // Increments once per observed tick arrival. Consumers watch this rather than `ticksReceived` so
  // that the FIRST frame after mount does not flash: on mount there is no previous value to
  // compare against, and a page load is not a tick.
  pulseSeq: number;
}

const Ctx = createContext<Map<string, TrailLivenessEntry> | null>(null);

/**
 * Polls /api/trail-liveness and shares one frame across every trail badge on the page.
 *
 * <p>Holds the last good frame on error rather than clearing: a transient BFF blip must not make
 * every armed stop on the page flicker to "unknown". Only a successful response replaces state.
 */
export function TrailLivenessProvider({ children }: { children: ReactNode }) {
  const [entries, setEntries] = useState<Map<string, TrailLivenessEntry>>(new Map());
  const prevTicks = useRef<Map<string, number>>(new Map());

  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const res = await fetch("/api/trail-liveness", { cache: "no-store" });
        if (!res.ok) throw new Error(String(res.status));
        const json = (await res.json()) as TrailLivenessResponse;
        if (!active) return;
        setEntries((prev) => {
          const next = new Map<string, TrailLivenessEntry>();
          for (const p of json.positions ?? []) {
            const before = prevTicks.current.get(p.workflow_id);
            const seen = before !== undefined && p.ticks_received > before;
            const priorSeq = prev.get(p.workflow_id)?.pulseSeq ?? 0;
            next.set(p.workflow_id, {
              feedStatus: p.feed_status,
              ticksReceived: p.ticks_received,
              lastTickObservedAt: p.last_tick_observed_at,
              pulseSeq: seen ? priorSeq + 1 : priorSeq,
            });
            prevTicks.current.set(p.workflow_id, p.ticks_received);
          }
          return next;
        });
      } catch {
        // Keep the last good frame. See the class note above.
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

  return <Ctx.Provider value={entries}>{children}</Ctx.Provider>;
}

/** Liveness for one position, or null when the provider is absent / it has no entry yet. */
export function useTrailLiveness(workflowId: string): TrailLivenessEntry | null {
  const map = useContext(Ctx);
  return map?.get(workflowId) ?? null;
}

/**
 * The feed dot rendered beside "Trailing x%". Three states, never two — see lib/bff.ts.
 *
 * <p>The dot answers "is anything feeding this stop", which the badge text alone cannot: the text
 * is workflow state, and a workflow goes on believing it is trailing long after the premium
 * subscription behind it died (#717).
 */
export function TrailFeedDot({ entry }: { entry: TrailLivenessEntry | null }) {
  const [flash, setFlash] = useState(false);
  const seq = entry?.pulseSeq ?? 0;

  useEffect(() => {
    if (seq === 0) return;
    setFlash(true);
    const t = setTimeout(() => setFlash(false), FLASH_MS);
    return () => clearTimeout(t);
  }, [seq]);

  const status: FeedStatus = entry?.feedStatus ?? "unknown";
  const color =
    status === "live"
      ? "bg-emerald-400"
      : status === "orphaned"
        ? "bg-rose-500"
        : "bg-slate-500";
  const label =
    status === "live"
      ? `Feed live${entry ? ` · ${entry.ticksReceived} ticks` : ""}`
      : status === "orphaned"
        ? "NO FEED - this stop is not being watched (re-arm to resubscribe)"
        : "Feed liveness unknown - could not reach market-data";

  return (
    <span
      className="relative inline-flex h-2 w-2 shrink-0"
      title={label}
      aria-label={label}
      role="img"
    >
      {flash && (
        <span
          className={`absolute inline-flex h-full w-full animate-ping rounded-full opacity-75 motion-reduce:animate-none ${color}`}
        />
      )}
      <span className={`relative inline-flex h-2 w-2 rounded-full ${color}`} />
    </span>
  );
}
