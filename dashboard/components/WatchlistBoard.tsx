"use client";

import { useEffect, useRef, useState } from "react";
// Type-only import (erased at compile): lib/bff is server-only, only its shape crosses here.
import type { WatchlistTodayResult } from "@/lib/bff";
import { ideaLabel } from "@/lib/watchlist";
import { IdeaStatusPill } from "./OptionChain";
import { fmtCurrency } from "./Pnl";

// The BFF caches bars 30s, so a faster poll would only re-read the same answer.
const POLL_MS = 60_000;

/**
 * Today's watchlist as /live's alternative to the account chart: each idea with its stock's last
 * price and whether it has triggered. Context only, like the picker's copy — it places nothing.
 * Polls only while mounted, i.e. while the operator has the Watchlist view open.
 */
export function WatchlistBoard({
  action,
}: {
  action: () => Promise<WatchlistTodayResult>;
}) {
  const [result, setResult] = useState<WatchlistTodayResult | null>(null);
  // /live's 15s refresh can hand a new action reference each time; read the latest through a ref so
  // a refresh does not restart the poll.
  const actionRef = useRef(action);
  actionRef.current = action;

  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      const r = await actionRef.current().catch(() => ({ ok: false, kind: "error" }) as const);
      if (!active) return;
      // Keep the last good list through a transient failure rather than blanking it.
      setResult((prev) => (r.ok || !prev?.ok ? r : prev));
      timer = setTimeout(poll, POLL_MS);
    };
    void poll();
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, []);

  return (
    <div className="min-h-64 w-full rounded border border-slate-800 bg-slate-900 p-2">
      {result === null ? (
        <Note>Loading today&apos;s watchlist...</Note>
      ) : !result.ok ? (
        <Note>
          {result.kind === "disabled"
            ? "The watchlist is not enabled for this account."
            : "The watchlist is unavailable right now."}
        </Note>
      ) : !result.posted ? (
        <Note>No watchlist has been posted today.</Note>
      ) : (
        <>
          <div className="px-1 pb-1 text-xs text-slate-500">
            Today&apos;s watchlist · {result.etDate} · price is the stock&apos;s last 1-minute close
          </div>
          <ul>
            {result.ideas.map((idea) => (
              <li
                key={`${idea.ticker}${idea.right}${idea.strike}`}
                className="grid grid-cols-[4.5rem_1fr_auto] items-center gap-2 border-t border-slate-800 px-1 py-1.5 text-sm"
              >
                <span className="flex flex-col leading-tight">
                  <span className="font-semibold text-slate-100">{idea.ticker}</span>
                  <span className="text-xs tabular-nums text-slate-400">
                    {fmtCurrency(idea.last)}
                  </span>
                </span>
                <span className="tabular-nums text-slate-300">{ideaLabel(idea)}</span>
                <IdeaStatusPill idea={idea} />
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

function Note({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-60 items-center justify-center text-sm text-slate-500">
      {children}
    </div>
  );
}
