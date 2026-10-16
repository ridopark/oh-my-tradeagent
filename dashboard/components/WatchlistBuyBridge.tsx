"use client";

import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import type { WatchlistIdea } from "@/lib/watchlist";

type BuyIdea = (idea: WatchlistIdea) => void;

const Ctx = createContext<{ buyIdea: BuyIdea | null; register: (fn: BuyIdea | null) => void }>({
  buyIdea: null,
  register: () => {},
});

/**
 * Connects /live's watchlist view (in the account section) to the manual-entry panel further down,
 * so a watchlist row's Buy runs the panel's one buy flow — fresh quote, qty, confirm — rather than a
 * second copy of it. `buyIdea` is null while no panel is mounted (flag off, no strategies), and the
 * watchlist then shows no Buy button.
 */
export function WatchlistBuyBridge({ children }: { children: ReactNode }) {
  const [buyIdea, setBuyIdea] = useState<BuyIdea | null>(null);
  const register = useRef((fn: BuyIdea | null) => setBuyIdea(() => fn)).current;
  return <Ctx.Provider value={{ buyIdea, register }}>{children}</Ctx.Provider>;
}

export function useBuyIdea(): BuyIdea | null {
  return useContext(Ctx).buyIdea;
}

/** The panel registers its handler; the latest one is always called, so it may close over state. */
export function useRegisterBuyIdea(fn: BuyIdea) {
  const { register } = useContext(Ctx);
  const latest = useRef(fn);
  latest.current = fn;
  useEffect(() => {
    register((idea) => latest.current(idea));
    return () => register(null);
  }, [register]);
}
