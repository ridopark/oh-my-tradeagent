"use client";

import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
// Type-only import (erased at compile): lib/bff is server-only, only its shape crosses here.
import type { LiveConnection, LiveMark, LiveMarksResponse } from "@/lib/bff";
import { Pnl, fmtCurrency } from "@/components/Pnl";
import {
  allIn,
  bidUsability,
  flashDirection,
  liveValue,
  livePnlToday,
  livePnlTotal,
  occKey,
  pctMove,
  underlyingUsability,
  type FrameClock,
  type Usability,
} from "@/lib/liveMarks";

// PLAN-2026-10-01-live-realtime-holdings P4. Marks every 1s (cache-only on the server: the BFF and
// market-data never reach the broker trading API on this path), connection every 5s. Both stop
// while the tab is hidden and poll at once when it comes back.
const MARKS_POLL_MS = 1_000;
const CONNECTION_POLL_MS = 5_000;
const MARKS_FETCH_TIMEOUT_MS = 4_000;
const CONNECTION_FETCH_TIMEOUT_MS = 6_000;
const FLASH_MS = 600;

// A function, not an inline read: TypeScript narrows `document.visibilityState` across an await.
const tabHidden = () => document.visibilityState === "hidden";

interface Frame {
  marks: Map<string, LiveMark>;
  payloadNowMs: number;
  receivedAtMs: number;
}

export interface LiveMarksState {
  /** Client clock; null during the server render / before mount, when nothing may read as live. */
  nowMs: number | null;
  frame: Frame | null;
  failures: number;
  lastOkMs: number | null;
  conn: { data: LiveConnection; atMs: number } | null;
}

const Ctx = createContext<LiveMarksState | null>(null);

/** Runs `poll` every `ms` while the tab is visible; immediately on mount and on becoming visible. */
function useVisiblePoll(poll: () => Promise<void>, ms: number) {
  const pollRef = useRef(poll);
  pollRef.current = poll;
  useEffect(() => {
    let active = true;
    let running = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      clearTimeout(timer);
      if (!active || running || tabHidden()) return;
      running = true;
      try {
        await pollRef.current();
      } finally {
        running = false;
      }
      if (active && !tabHidden()) {
        timer = setTimeout(tick, ms);
      }
    };
    const onVisibility = () => {
      if (tabHidden()) clearTimeout(timer);
      else void tick();
    };
    document.addEventListener("visibilitychange", onVisibility);
    void tick();
    return () => {
      active = false;
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [ms]);
}

/**
 * Polls /api/live-marks and /api/live-connection and shares one frame with every live cell and the
 * connection strip. Holds the last good frame on error — but the frame keeps AGEING on the client
 * clock, and three failures in a row make every mark unusable, so a dead poll can never leave a
 * frozen number looking live.
 */
export function LiveMarksProvider({ children }: { children: ReactNode }) {
  const [nowMs, setNowMs] = useState<number | null>(null);
  const [frame, setFrame] = useState<Frame | null>(null);
  const [failures, setFailures] = useState(0);
  const [lastOkMs, setLastOkMs] = useState<number | null>(null);
  const [conn, setConn] = useState<LiveMarksState["conn"]>(null);

  // Ages on the strip and stale cells must keep counting even when no poll completes (a hung
  // request, a stopped poll).
  useEffect(() => {
    setNowMs(Date.now());
    const t = setInterval(() => setNowMs(Date.now()), 1_000);
    return () => clearInterval(t);
  }, []);

  useVisiblePoll(async () => {
    try {
      const res = await fetch("/api/live-marks", {
        cache: "no-store",
        signal: AbortSignal.timeout(MARKS_FETCH_TIMEOUT_MS),
      });
      if (!res.ok) throw new Error(String(res.status));
      const json = (await res.json()) as LiveMarksResponse;
      const payloadNowMs = Date.parse(json.now);
      if (!Array.isArray(json.marks) || !Number.isFinite(payloadNowMs)) {
        throw new Error("malformed live marks");
      }
      const at = Date.now();
      setFrame({
        marks: new Map(json.marks.map((m) => [occKey(m.occ), m])),
        payloadNowMs,
        receivedAtMs: at,
      });
      setFailures(0);
      setLastOkMs(at);
      setNowMs(at);
    } catch {
      setFailures((f) => f + 1);
      setNowMs(Date.now());
    }
  }, MARKS_POLL_MS);

  useVisiblePoll(async () => {
    try {
      const res = await fetch("/api/live-connection", {
        cache: "no-store",
        signal: AbortSignal.timeout(CONNECTION_FETCH_TIMEOUT_MS),
      });
      if (!res.ok) throw new Error(String(res.status));
      const data = (await res.json()) as LiveConnection;
      setConn({ data, atMs: Date.now() });
    } catch {
      // Keep the last snapshot; partLight ages it out to "unknown" after 15s.
    }
  }, CONNECTION_POLL_MS);

  return (
    <Ctx.Provider value={{ nowMs, frame, failures, lastOkMs, conn }}>
      {children}
    </Ctx.Provider>
  );
}

export function useLiveMarks(): LiveMarksState | null {
  return useContext(Ctx);
}

/** The frame clock for usability checks, or null when there is no frame (or no provider). */
export function frameClock(s: LiveMarksState | null): FrameClock | null {
  if (s === null || s.frame === null || s.nowMs === null) return null;
  return {
    payloadNowMs: s.frame.payloadNowMs,
    elapsedMs: Math.max(0, s.nowMs - s.frame.receivedAtMs),
    failures: s.failures,
  };
}

function useMark(occ: string) {
  const s = useLiveMarks();
  const mark = s?.frame?.marks.get(occKey(occ));
  const clock = frameClock(s);
  return {
    bid: bidUsability(mark, clock),
    underlying: underlyingUsability(mark, clock),
  };
}

/** Background flash for ~600ms when a LIVE value changes; never on first sight or from stale. */
function useFlash(value: number | null): string {
  const prev = useRef<number | null | undefined>(undefined);
  const [flash, setFlash] = useState<{ dir: "up" | "down"; at: number } | null>(null);
  useEffect(() => {
    const dir = flashDirection(prev.current, value);
    prev.current = value;
    if (dir !== null) setFlash({ dir, at: Date.now() });
  }, [value]);
  useEffect(() => {
    if (flash === null) return;
    const t = setTimeout(() => setFlash(null), FLASH_MS);
    return () => clearTimeout(t);
  }, [flash]);
  if (flash === null) return "";
  // motion-reduce: the colour change IS the motion here, so it is dropped entirely.
  return flash.dir === "up"
    ? "bg-emerald-500/30 motion-reduce:bg-transparent"
    : "bg-rose-500/30 motion-reduce:bg-transparent";
}

/** A live value: flashes on change. */
function Live({ value, children }: { value: number | null; children: ReactNode }) {
  const flash = useFlash(value);
  return (
    <span className={`rounded-sm transition-colors duration-300 motion-reduce:transition-none ${flash}`}>
      {children}
    </span>
  );
}

/** The server-rendered broker value, muted (no label) so it is never mistaken for a live one. */
function Stale({ children }: { children: ReactNode }) {
  return (
    <span className="opacity-50" title="Not live: broker value from the last page render">
      {children}
    </span>
  );
}

function liveNumber(u: Usability): number | null {
  return u.usable ? u.value : null;
}

// The "(+7.4%)" move beside a paired price cell (moved here from app/live/page.tsx with the cells).
function moveLine(pct: number | null, inline = false): ReactNode {
  if (pct === null) {
    return null;
  }
  const tone = pct >= 0 ? "text-emerald-400" : "text-rose-400";
  const text = `(${pct >= 0 ? "+" : ""}${pct.toFixed(1)}%)`;
  return inline ? (
    <span className={`whitespace-nowrap text-xs ${tone}`}> {text}</span>
  ) : (
    <span className={`block text-xs ${tone}`}>{text}</span>
  );
}

/**
 * "$2.78 → $2.55 bid" — entry premium and the live option BID. The entry premium is a cost basis
 * and keeps its full precision (2.805); the server mark in the stale fallback renders as given.
 */
export function LivePremium({
  occ,
  entry,
  serverMark,
  inline = false,
}: {
  occ: string;
  entry: number | null;
  serverMark: number | null;
  inline?: boolean;
}) {
  const { bid } = useMark(occ);
  const live = liveNumber(bid);
  if (entry === null && live === null && serverMark === null) {
    return <span className="text-slate-500">—</span>;
  }
  const entryText = entry === null ? "—" : `$${entry}`;
  const wrap = inline ? undefined : "inline-block";
  const line = `whitespace-nowrap text-slate-200 ${inline ? "" : "block"}`;
  if (live === null) {
    return (
      <span className={wrap}>
        <Stale>
          <span className={line}>
            {entryText}
            <span className="text-slate-200"> → </span>
            {serverMark === null ? "—" : `$${serverMark}`}
          </span>
          {moveLine(pctMove(entry, serverMark), inline)}
        </Stale>
      </span>
    );
  }
  return (
    <span className={wrap}>
      <span className={line}>
        {entryText}
        <span className="text-slate-200"> → </span>
        <Live value={live}>${live.toFixed(2)}</Live>
        <span className="text-[10px] uppercase tracking-wide text-slate-500"> bid</span>
      </span>
      {moveLine(pctMove(entry, live), inline)}
    </span>
  );
}

/**
 * "$38.30 → $41.12 (+7.4%)" for the Underlying column, live from the mark's underlying price.
 * Links to the stock's quote page when the contract parses (ticker non-null).
 */
export function LiveUnderlying({
  occ,
  entry,
  serverPrice,
  ticker,
  inline = false,
}: {
  occ: string;
  entry: number | null;
  serverPrice: number | null;
  ticker: string | null;
  inline?: boolean;
}) {
  const { underlying } = useMark(occ);
  const live = liveNumber(underlying);
  const now = live ?? serverPrice;
  if (entry === null && now === null) {
    return <span className="text-slate-500">—</span>;
  }
  const prices = (
    <span className={`whitespace-nowrap ${inline ? "" : "block"}`}>
      {entry === null ? "—" : `$${entry.toFixed(2)}`}
      <span className="text-slate-200"> → </span>
      {live !== null ? (
        <Live value={live}>${live.toFixed(2)}</Live>
      ) : now === null ? (
        "—"
      ) : (
        `$${now.toFixed(2)}`
      )}
    </span>
  );
  const body = (
    <>
      {ticker === null ? (
        <span className="text-slate-200">{prices}</span>
      ) : (
        <a
          href={`https://finance.yahoo.com/quote/${encodeURIComponent(ticker)}`}
          target="_blank"
          rel="noopener noreferrer"
          title={`${ticker} on Yahoo Finance`}
          className="text-sky-400 hover:text-sky-300 hover:underline"
        >
          {prices}
        </a>
      )}
      {moveLine(pctMove(entry, now), inline)}
    </>
  );
  return (
    <span className={inline ? undefined : "inline-block"}>
      {live === null ? (
        <Stale>
          {body}
        </Stale>
      ) : (
        body
      )}
    </span>
  );
}

/** "$1,390.00 → $1,275.00" — cost basis against Value = bid × qty × 100. */
export function LiveCostValue({
  occ,
  qty,
  cost,
  serverValue,
}: {
  occ: string;
  qty: number | null;
  cost: number | string | null;
  serverValue: number | null;
}) {
  const { bid } = useMark(occ);
  const live = bid.usable ? liveValue(bid.value, qty) : null;
  return (
    <span className="whitespace-nowrap">
      <span className="text-slate-200">{fmtCurrency(cost)}</span>
      <span className="text-slate-200"> → </span>
      {live !== null ? (
        <Live value={live}>
          <span className="text-slate-200">{fmtCurrency(live)}</span>
        </Live>
      ) : serverValue === null ? (
        <span className="text-slate-500">—</span>
      ) : (
        <Stale>
          <span className="text-slate-200">{fmtCurrency(serverValue)}</span>
        </Stale>
      )}
    </span>
  );
}

function LivePnl({
  live,
  server,
}: {
  live: number | null;
  server: number | null;
}) {
  if (live !== null) {
    return (
      <Live value={live}>
        <Pnl value={live} />
      </Live>
    );
  }
  if (server === null) {
    return <Pnl value={null} />;
  }
  return (
    <Stale>
      <Pnl value={server} />
    </Stale>
  );
}

/** P&L today = (bid − lastday_price) × qty × 100; without lastday_price the server value, stale. */
export function LivePnlToday({
  occ,
  qty,
  lastday,
  serverValue,
  inline,
}: {
  occ: string;
  qty: number | null;
  lastday: number | null;
  serverValue: number | null;
  inline?: boolean;
}) {
  const { bid } = useMark(occ);
  const live = bid.usable ? livePnlToday(bid.value, lastday, qty) : null;
  return <LivePnl live={live} server={serverValue} />;
}

/** P&L total (unrealized) = (bid − entry_premium) × remaining_qty × 100. */
export function LivePnlTotal({
  occ,
  qty,
  entry,
  serverValue,
  inline,
}: {
  occ: string;
  qty: number | null;
  entry: number | null;
  serverValue: number | null;
  inline?: boolean;
}) {
  const { bid } = useMark(occ);
  const live = bid.usable ? livePnlTotal(bid.value, entry, qty) : null;
  return <LivePnl live={live} server={serverValue} />;
}

/** All-in = live unrealized + realized_pl. Only rendered where page.tsx's allInPl is non-null. */
export function LiveAllIn({
  occ,
  qty,
  entry,
  realized,
  serverValue,
  prefix,
  className,
}: {
  occ: string;
  qty: number | null;
  entry: number | null;
  realized: number | null;
  serverValue: number | null;
  prefix?: string;
  className: string;
}) {
  const { bid } = useMark(occ);
  const live = bid.usable ? allIn(livePnlTotal(bid.value, entry, qty), realized) : null;
  const value = live ?? serverValue;
  const tone =
    value === null ? "text-slate-500" : value >= 0 ? "text-emerald-400" : "text-rose-400";
  const text = (
    <span className={`${className} ${tone}`}>
      {prefix}
      {fmtCurrency(value)}
    </span>
  );
  if (live !== null) {
    return <Live value={live}>{text}</Live>;
  }
  return value === null ? (
    text
  ) : (
    <Stale>
      {text}
    </Stale>
  );
}
