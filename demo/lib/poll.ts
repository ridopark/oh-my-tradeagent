"use client";

import { useEffect, useRef, useState } from "react";
import type { Published } from "./adapt";

export interface Polled<T> {
  /** The last good payload; null until the first one arrives. */
  data: T | null;
  /** When the poller fetched that payload. */
  generatedAt: string | null;
  /** This browser's clock when that payload (by its `generatedAt`) first arrived. */
  receivedAtMs: number | null;
}

function isPublished(v: unknown): v is Published<unknown> {
  return (
    typeof v === "object" &&
    v !== null &&
    typeof (v as { generated_at?: unknown }).generated_at === "string" &&
    "data" in v
  );
}

/**
 * Polls one published file, `/data/<name>.json`, every `everyMs` while the tab is visible. A failed
 * or malformed fetch keeps the last good payload: the page then simply ages into "stale".
 */
export function usePolled<T>(name: string, everyMs: number): Polled<T> {
  const [state, setState] = useState<Polled<T>>({
    data: null,
    generatedAt: null,
    receivedAtMs: null,
  });
  const inFlight = useRef(false);

  useEffect(() => {
    let cancelled = false;
    async function tick() {
      if (inFlight.current || document.visibilityState === "hidden") {
        return;
      }
      inFlight.current = true;
      try {
        const res = await fetch(`/data/${name}.json`, { cache: "no-store" });
        if (res.ok) {
          const body: unknown = await res.json();
          if (!cancelled && isPublished(body)) {
            const generatedAt = body.generated_at;
            // A re-fetch of the same file must not make it look newly arrived.
            setState((prev) =>
              prev.generatedAt === generatedAt
                ? prev
                : { data: body.data as T, generatedAt, receivedAtMs: Date.now() },
            );
          }
        }
      } catch {
        // keep the last good payload
      } finally {
        inFlight.current = false;
      }
    }
    void tick();
    const id = setInterval(() => void tick(), everyMs);
    const onVisible = () => void tick();
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      cancelled = true;
      clearInterval(id);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [name, everyMs]);

  return state;
}

/** The browser clock, ticking once a second, for "updated N s ago". */
export function useNow(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  return now;
}
