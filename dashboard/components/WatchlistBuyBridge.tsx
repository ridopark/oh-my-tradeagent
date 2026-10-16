"use client";

import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import type { WatchlistIdea } from "@/lib/watchlist";

type BuyIdea = (idea: WatchlistIdea) => void;

type Registration = { buy: BuyIdea; busy: boolean } | null;

const Ctx = createContext<{ reg: Registration; register: (r: Registration) => void }>({
  reg: null,
  register: () => {},
});

/**
 * Connects /live's watchlist view (in the account section) to the manual-entry panel further down,
 * so a watchlist row's Buy runs the panel's one buy flow — fresh quote, qty, confirm — rather than a
 * second copy of it. Nothing is registered while no panel is mounted (flag off, no strategies), and
 * the watchlist then shows no Buy button.
 */
export function WatchlistBuyBridge({ children }: { children: ReactNode }) {
  const [reg, setReg] = useState<Registration>(null);
  return <Ctx.Provider value={{ reg, register: setReg }}>{children}</Ctx.Provider>;
}

/**
 * Null when there is no panel to buy through. `busy` while the panel has an entry in flight: a new
 * Buy would replace the step and stop that entry's outcome poll, so the button waits instead.
 */
export function useBuyIdea(): { buy: BuyIdea; busy: boolean } | null {
  return useContext(Ctx).reg;
}

/** The panel registers its handler; the latest one is always called, so it may close over state. */
export function useRegisterBuyIdea(fn: BuyIdea, busy: boolean) {
  const { register } = useContext(Ctx);
  const latest = useRef(fn);
  latest.current = fn;
  useEffect(() => {
    register({ buy: (idea) => latest.current(idea), busy });
    return () => register(null);
  }, [register, busy]);
}
