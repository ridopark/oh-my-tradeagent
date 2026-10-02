"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  type ReactNode,
} from "react";
import { useRouter } from "next/navigation";

// The slower half of /live's refresh (PLAN-2026-10-01 P4): everything the 1s marks poll does not
// cover — Qty, all-in, the activity strips, the account header — comes from a full server re-render.
const REFRESH_MS = 15_000;

const PanelOpenCtx = createContext<{ open: () => void; close: () => void } | null>(null);

/**
 * Marks an action panel (Trim / Stop-loss / Force-exit / manual entry) as open while `open` is true.
 * While any panel is open the 15s refresh holds off: a re-render mid-confirm can remount the panel
 * and kill its in-flight poll (see the manual-entry note in app/live/page.tsx). No-op outside
 * LiveRefresh, so the components behave exactly as before anywhere else.
 */
export function usePanelOpen(open: boolean): void {
  const ctx = useContext(PanelOpenCtx);
  useEffect(() => {
    if (!open || ctx === null) return;
    ctx.open();
    return ctx.close;
  }, [open, ctx]);
}

/** Calls router.refresh() every 15s while the tab is visible and no action panel is open. */
export function LiveRefresh({ children }: { children: ReactNode }) {
  const router = useRouter();
  const openCount = useRef(0);
  const open = useCallback(() => {
    openCount.current += 1;
  }, []);
  const close = useCallback(() => {
    openCount.current -= 1;
  }, []);
  const ctx = useMemo(() => ({ open, close }), [open, close]);

  useEffect(() => {
    const t = setInterval(() => {
      if (document.visibilityState === "visible" && openCount.current === 0) {
        router.refresh();
      }
    }, REFRESH_MS);
    return () => clearInterval(t);
  }, [router]);

  return <PanelOpenCtx.Provider value={ctx}>{children}</PanelOpenCtx.Provider>;
}
