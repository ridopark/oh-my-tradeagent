"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { fmtAge, freshness, isDisabled } from "@/lib/darkpool";

// Above lib/bff.ts's 12 s BFF_TIMEOUT_MS, so the proxy's own 502 arrives before the client gives up.
const FETCH_TIMEOUT_MS = 15_000;

/**
 * Client clock ticking every `tickMs`; null until mount so nothing claims an age during SSR.
 * The 1 s tick belongs in FreshnessNote ONLY — a table island re-rendering every second would
 * re-reconcile up to 200 rows for a label change. Tables use a coarse tick for today/poll-window.
 */
export function useNow(tickMs: number): number | null {
  const [now, setNow] = useState<number | null>(null);
  useEffect(() => {
    setNow(Date.now());
    const t = setInterval(() => setNow(Date.now()), tickMs);
    return () => clearInterval(t);
  }, [tickMs]);
  return now;
}

export interface DarkpoolReadState<T> {
  data: T | null;
  /** The proxy said the BFF has no /api/darkpool route (404). */
  disabled: boolean;
  /** The BFF rejected the query (400). */
  invalid: boolean;
  /** The last attempt was a 2xx with no usable JSON object. Also sets `failed`. */
  noData: boolean;
  /** The LAST attempt failed; `data` is the last good frame (or null). */
  failed: boolean;
  lastOkMs: number | null;
  loading: boolean;
  refresh: () => void;
}

/**
 * Reads `url` once whenever it changes, then every `pollMs` while the tab is visible (null = on
 * demand only, via `refresh`). Never polls a hidden tab. A response for a superseded url is dropped,
 * so a slow reply cannot paint the previous filter's rows under the new one.
 */
export function useDarkpoolRead<T>(url: string, pollMs: number | null): DarkpoolReadState<T> {
  const [data, setData] = useState<T | null>(null);
  const [disabled, setDisabled] = useState(false);
  const [invalid, setInvalid] = useState(false);
  const [noData, setNoData] = useState(false);
  const [failed, setFailed] = useState(false);
  const [lastOkMs, setLastOkMs] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const seq = useRef(0);

  const load = useCallback(async () => {
    const id = ++seq.current;
    setLoading(true);
    try {
      const res = await fetch(url, {
        cache: "no-store",
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      });
      if (id !== seq.current) return;
      if (res.status === 400) {
        setInvalid(true);
        setNoData(false);
        setFailed(false);
        return;
      }
      if (!res.ok) throw new Error(String(res.status));
      const body: unknown = await res.json();
      if (id !== seq.current) return;
      setInvalid(false);
      if (typeof body !== "object" || body === null) {
        // A 2xx that carries nothing is not a successful read: keep the last frame and say so.
        setNoData(true);
        setFailed(true);
        return;
      }
      setNoData(false);
      setFailed(false);
      setLastOkMs(Date.now());
      if (isDisabled(body)) {
        setDisabled(true);
        setData(null);
      } else {
        setDisabled(false);
        setData(body as T);
      }
    } catch {
      if (id === seq.current) {
        setNoData(false);
        setFailed(true);
      }
    } finally {
      if (id === seq.current) setLoading(false);
    }
  }, [url]);

  // A new url is a different question: drop the previous answer instead of showing it as this one.
  useEffect(() => {
    setData(null);
    setLastOkMs(null);
    setInvalid(false);
    setNoData(false);
    setFailed(false);
  }, [url]);

  // The first read for a url runs even in a background tab: one request, and the page must not
  // sit empty. Kept apart from the scheduler below so that auto-refresh switching on or off (the
  // clock crossing 09:30/16:03, or mounting) does not fire an extra read.
  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (pollMs === null) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const hidden = () => document.visibilityState === "hidden";
    const schedule = () => {
      clearTimeout(timer);
      if (active && !hidden()) {
        timer = setTimeout(async () => {
          await load();
          schedule();
        }, pollMs);
      }
    };
    const onVisibility = () => {
      if (hidden()) {
        clearTimeout(timer);
      } else {
        void load().then(schedule);
      }
    };
    document.addEventListener("visibilitychange", onVisibility);
    schedule();
    return () => {
      active = false;
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [load, pollMs]);

  return {
    data,
    disabled,
    invalid,
    noData,
    failed,
    lastOkMs,
    loading,
    refresh: () => void load(),
  };
}

/**
 * "updated 12s ago · auto-refresh 30s" — amber when stale, red when the last read failed or was
 * rejected. Owns the 1 s ticker so only this small node re-renders every second.
 */
export function FreshnessNote({
  read,
  pollMs,
}: {
  read: DarkpoolReadState<unknown>;
  pollMs: number | null;
}) {
  const nowMs = useNow(1_000);
  const age = nowMs !== null && read.lastOkMs !== null ? nowMs - read.lastOkMs : null;
  const state = freshness(age, read, pollMs);
  const tone =
    state === "fresh" ? "text-slate-500" : state === "stale" ? "text-amber-400" : "text-red-400";
  const since = age === null ? "" : ` — showing data from ${fmtAge(age)}`;
  const text =
    state === "rejected"
      ? "rejected by the BFF"
      : state === "empty"
        ? `no data in the response${since}`
        : state === "failed"
          ? `read failed${since}`
          : age === null
            ? "loading…"
            : `updated ${fmtAge(age)}${state === "stale" ? " (stale)" : ""}`;
  return (
    <span className={`flex items-center gap-2 text-xs ${tone}`}>
      <span>
        {text}
        {pollMs !== null ? ` · auto-refresh ${Math.round(pollMs / 1000)}s` : " · auto-refresh off"}
      </span>
      <button
        type="button"
        onClick={read.refresh}
        disabled={read.loading}
        className="rounded border border-slate-700 px-2 py-0.5 text-slate-300 hover:bg-slate-800 disabled:opacity-50"
      >
        {read.loading ? "Refreshing…" : "Refresh"}
      </button>
    </span>
  );
}

/** Shown in place of a table when the last read produced no rows to render. */
export function DarkpoolReadProblem({ read }: { read: DarkpoolReadState<unknown> }) {
  return (
    <p className="text-sm text-slate-400">
      {read.noData
        ? "The dashboard BFF returned an empty response. Retry with Refresh."
        : read.failed
          ? "Unable to reach the dashboard BFF. Retry with Refresh."
          : "Loading…"}
    </p>
  );
}

/**
 * The shared "nothing to show" copy. `disabled` = the BFF has no dark-pool route; `preOpen` = today's
 * session has not started, so emptiness is expected and the collector is not to blame.
 */
export function DarkpoolEmptyState({
  disabled = false,
  preOpen = false,
  what = "",
}: {
  disabled?: boolean;
  preOpen?: boolean;
  what?: string;
}) {
  if (disabled) {
    return (
      <p className="rounded border border-slate-700 bg-slate-800/60 px-3 py-2 text-sm text-slate-300">
        The dashboard BFF has no <code>/api/darkpool</code> route: either{" "}
        <code>DARKPOOL_ENABLED</code> is off on tenant-dashboard-bff, or the running BFF image
        predates dark-pool support.
      </p>
    );
  }
  if (preOpen) {
    return (
      <p className="text-sm text-slate-400">
        Pre-open: no session data yet. The summary covers 09:30–16:00 ET only.
      </p>
    );
  }
  return (
    <p className="text-sm text-slate-400">
      No {what}. Data comes from the <code>darkpool-collector</code> sidecar, which posts nothing
      while <code>DARKPOOL_DRY_RUN=true</code>; pre-launch, or on a weekend or holiday, this is
      expected.
    </p>
  );
}
