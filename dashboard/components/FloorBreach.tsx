"use client";

import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import type { FloorBreachPosition, FloorBreachResponse } from "@/lib/bff";

// Issue #779. Deliberately SLOWER than TrailLiveness's 4s: each server-side frame costs one
// market-data quote HTTP call per open position, and a floor breach is a state that moves over
// hours — 30s is unmissable without multiplying load per open tab.
const POLL_MS = 30_000;

const Ctx = createContext<Map<string, FloorBreachPosition> | null>(null);

/**
 * Polls /api/floor-breach and shares one frame across every floor badge on the page.
 *
 * Holds the last good frame on FETCH error (a transient dashboard/BFF blip must not flicker every
 * row) — but note the asymmetry: a held frame is stale UI truth, while a SERVER-reported quote
 * failure arrives as "unknown" rows inside a good frame, which DO replace state.
 */
export function FloorBreachProvider({ children }: { children: ReactNode }) {
  const [entries, setEntries] = useState<Map<string, FloorBreachPosition>>(new Map());

  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const res = await fetch("/api/floor-breach", { cache: "no-store" });
        if (!res.ok) throw new Error(String(res.status));
        const json = (await res.json()) as FloorBreachResponse;
        if (!active) return;
        const next = new Map<string, FloorBreachPosition>();
        for (const p of json.positions ?? []) {
          next.set(p.workflow_id, p);
        }
        setEntries(next);
      } catch {
        // Keep the last good frame. See the component note above.
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

/**
 * The per-row floor indicator, rendered beside the contract symbol on /live. Three states:
 *   breach  → solid red "FLOOR BREACH -NN%" badge, visible without hover
 *   unknown → grey "FLOOR ?" badge with a quote-unavailable explanation on hover
 *   ok      → renders nothing
 * ALERT-ONLY: a badge, never a button, never a pre-filled action. The row already carries the
 * existing Trim / Force-exit / stop-loss controls.
 */
export function FloorBreachBadge({ workflowId }: { workflowId: string }) {
  const map = useContext(Ctx);
  const entry = map?.get(workflowId) ?? null;
  if (!entry || entry.floor_status === "ok") {
    return null;
  }
  if (entry.floor_status === "unknown") {
    return (
      <span
        className="inline-flex shrink-0 items-center rounded bg-slate-700 px-1.5 py-0.5 text-[10px] font-semibold text-slate-300"
        title="Floor state unknown — the option quote is unavailable, so this position cannot be checked against its entry floor."
      >
        FLOOR ?
      </span>
    );
  }
  const lossPct =
    entry.loss_pct == null ? null : Math.round(entry.loss_pct * 100);
  return (
    <span
      className="inline-flex shrink-0 animate-pulse items-center rounded bg-rose-600 px-1.5 py-0.5 text-[10px] font-bold text-white motion-reduce:animate-none"
      title={`Bid ${entry.current_bid ?? "?"} is at/below the ${entry.floor_line ?? "?"} floor line (entry ${entry.entry_premium ?? "?"}). Alert only — nothing is auto-sold.`}
    >
      FLOOR BREACH{lossPct == null ? "" : ` -${lossPct}%`}
    </span>
  );
}
