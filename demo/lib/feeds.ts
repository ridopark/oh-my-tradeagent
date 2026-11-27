"use client";

import {
  toKillSwitch,
  toOrders,
  toPortfolio,
  toTrades,
  type DemoFill,
  type DemoMark,
  type DemoOrder,
  type DemoPortfolio,
  type DemoStatus,
} from "./adapt";
import { bidUsability, type MarkInput } from "@/lib/liveMarks";
import { useNow, usePolled } from "./poll";

interface MarksFile {
  now: string;
  marks: (MarkInput & { occ: string })[];
}

// How often the poller refreshes each file, in seconds. The page polls a little faster than the
// slowest of these so a new file is picked up promptly; "stale" is judged against these.
export const CADENCE = { marks: 3, fills: 5, orders: 5, portfolio: 30, status: 15 } as const;

/** Portfolio (30s) with live quotes (3s) laid over the open positions. */
export function usePortfolio() {
  const portfolio = usePolled<DemoPortfolio>("portfolio", 10_000);
  const marks = usePolled<MarksFile>("marks", 3_000);
  const now = useNow();
  // Only quotes the dashboard's own rule calls live (polled within 10s, not warming) are laid over
  // the rows. Anything older — the market is closed, the poller stopped — leaves the broker's
  // mark from the portfolio file in place rather than showing an old bid as current.
  const live: DemoMark[] = [];
  if (marks.data && marks.receivedAtMs !== null) {
    const clock = {
      payloadNowMs: Date.parse(marks.data.now),
      elapsedMs: Math.max(0, now - marks.receivedAtMs),
      failures: 0,
    };
    for (const m of marks.data.marks) {
      const u = bidUsability({ ...m, capped: m.capped ?? false }, clock);
      if (u.usable) {
        live.push({ occ: m.occ, bid: u.value });
      }
    }
  }
  return {
    p: portfolio.data ? toPortfolio(portfolio.data, live) : null,
    stamps: [
      { label: "Account", at: portfolio.generatedAt, cadence: CADENCE.portfolio },
      { label: "Prices", at: marks.generatedAt, cadence: CADENCE.marks },
    ],
  };
}

export function useOrders() {
  const orders = usePolled<{ items: DemoOrder[] }>("orders", 5_000);
  return {
    items: orders.data ? toOrders(orders.data.items) : null,
    stamps: [{ label: "Orders", at: orders.generatedAt, cadence: CADENCE.orders }],
  };
}

export function useFills() {
  const fills = usePolled<{ items: DemoFill[] }>("fills", 5_000);
  return {
    items: fills.data ? toTrades(fills.data.items) : null,
    stamps: [{ label: "Fills", at: fills.generatedAt, cadence: CADENCE.fills }],
  };
}

export function useKillSwitch() {
  const status = usePolled<DemoStatus>("status", 10_000);
  return {
    killSwitch: toKillSwitch(status.data),
    stamp: { label: "Status", at: status.generatedAt, cadence: CADENCE.status },
  };
}
