"use client";

import type { ReactNode } from "react";
import { NavHeader } from "@/components/NavHeader";
import { ageSeconds, isStale } from "~/lib/adapt";
import { useNow } from "~/lib/poll";

// The pages the demo has. Same labels and order as the dashboard's header, minus the pages that
// are not public (and "Trades" is honestly named: these are individual fills).
const LINKS = [
  { href: "/status/", label: "Status" },
  { href: "/portfolio/", label: "Portfolio" },
  { href: "/positions/", label: "Positions" },
  { href: "/trades/", label: "Fills" },
  { href: "/orders/", label: "Order History" },
];

export interface Stamp {
  label: string;
  at: string | null;
  cadence: number;
}

/** Header, the paper-account banner with data freshness, the page, and the disclaimer. */
export function DemoShell({ stamps, children }: { stamps: Stamp[]; children: ReactNode }) {
  const now = useNow();
  return (
    <>
      <NavHeader
        links={LINKS}
        accountBlock={
          <span className="rounded border border-slate-700 px-2 py-1 text-slate-300">
            Paper demo
          </span>
        }
      />
      <div className="border-b border-slate-800 bg-slate-900/60">
        <div className="mx-auto flex max-w-6xl flex-col gap-1 px-4 py-2 text-xs text-slate-400 sm:flex-row sm:items-center sm:justify-between">
          <span>
            <span className="font-medium text-slate-200">Paper account · test environment.</span>{" "}
            Simulated fills. New positions appear 15 minutes after they open.
          </span>
          <span className="flex flex-wrap gap-x-3">
            {stamps.map((s) => {
              const age = ageSeconds(s.at, now);
              const stale = isStale(age, s.cadence);
              return (
                <span key={s.label} className={stale ? "text-amber-300" : undefined}>
                  {s.label}:{" "}
                  {age === null ? "waiting for data" : stale ? `stale (${age}s old)` : `updated ${age}s ago`}
                </span>
              );
            })}
          </span>
        </div>
      </div>
      {children}
      <footer className="mx-auto max-w-6xl px-4 pb-8 pt-2 text-xs text-slate-500">
        This page shows a paper-trading account used for testing. Fills are simulated and results
        are not those of a real-money account. Nothing here is investment advice or an offer of any
        service, and past results do not predict future results.
      </footer>
    </>
  );
}

/** Shown under the banner until a page's first file arrives. */
export function Loading() {
  return (
    <main className="mx-auto max-w-6xl px-4 py-6 text-sm text-slate-400">Loading…</main>
  );
}
