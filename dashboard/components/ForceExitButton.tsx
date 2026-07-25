"use client";

import { useEffect, useRef, useState, useTransition } from "react";

// Client island for the per-position "Force exit" button on /live. Modeled on AccountKillSwitchReset
// (useTransition + a server-action prop), but this is a per-ROW real-money control, so it uses an
// INLINE two-click confirm (no modal component exists in the repo): the first click arms a confirm
// state naming the symbol + qty and warning "sells at market"; the second click fires the action.
// The confirm auto-disarms after a few seconds or on blur so a stray first click never leaves a
// primed sell sitting in the table.

// Result the server action threads back so the row can show a terminal outcome inline. `ok` covers
// both a placed exit (202) and a benign no-op / phantom clear (200); `already-closed` is the
// render-vs-click race (the workflow terminated in between); `disabled` is the dark-flag-off 404
// (unreachable when the paired UI flag gates the button, handled for completeness); `error` is any
// other failure.
export type ForceExitActionResult =
  | { ok: true; exitSignalId?: string }
  | { ok: false; kind: "already-closed" | "disabled" | "error" };

// How long an armed confirm stays primed before auto-disarming (ms).
const CONFIRM_TIMEOUT_MS = 5000;

export function ForceExitButton({
  workflowId,
  symbol,
  qty,
  hasBrokerMark,
  action,
}: {
  workflowId: string;
  symbol: string;
  qty: number;
  // False when the broker carries no mark for this contract — i.e. a likely phantom (tracking says
  // we hold it, the broker shows nothing). The confirm state then explains the exit clears tracking.
  hasBrokerMark: boolean;
  action: (workflowId: string) => Promise<ForceExitActionResult>;
}) {
  const [pending, startTransition] = useTransition();
  const [confirming, setConfirming] = useState(false);
  const [result, setResult] = useState<ForceExitActionResult | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearTimer = () => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  };
  // Drop the auto-disarm timer if the row unmounts (revalidation drops the closed position).
  useEffect(() => clearTimer, []);

  const arm = () => {
    setResult(null);
    setConfirming(true);
    clearTimer();
    timerRef.current = setTimeout(() => setConfirming(false), CONFIRM_TIMEOUT_MS);
  };
  const disarm = () => {
    clearTimer();
    setConfirming(false);
  };
  const fire = () => {
    clearTimer();
    setConfirming(false);
    startTransition(async () => {
      const r = await action(workflowId);
      setResult(r);
    });
  };

  // Terminal result — a brief inline note in place of the button (the row itself disappears on the
  // next revalidation when the position actually closes).
  if (result) {
    if (result.ok) {
      return (
        <span className="text-xs font-medium text-emerald-300" role="status">
          Exit placed
        </span>
      );
    }
    if (result.kind === "already-closed") {
      return (
        <span className="text-xs font-medium text-amber-300" role="status">
          Already closed
        </span>
      );
    }
    return (
      <div className="flex items-center gap-2">
        <span className="text-xs font-medium text-rose-300" role="alert">
          {result.kind === "disabled" ? "Not enabled" : "Failed"}
        </span>
        <button
          type="button"
          onClick={() => setResult(null)}
          className="rounded border border-slate-600/60 px-2 py-0.5 text-xs text-slate-300 hover:bg-slate-700/30"
        >
          Retry
        </button>
      </div>
    );
  }

  if (pending) {
    return (
      <span
        className="text-xs font-medium text-slate-300"
        role="status"
        aria-live="polite"
      >
        Exiting…
      </span>
    );
  }

  if (confirming) {
    return (
      <div className="flex flex-col items-end gap-1" onBlur={disarm}>
        <div className="flex items-center gap-2">
          <button
            type="button"
            autoFocus
            onClick={fire}
            className="rounded border border-rose-500/60 bg-rose-600/20 px-2 py-1 text-xs font-medium text-rose-200 hover:bg-rose-600/30"
          >
            Confirm — sells {symbol} ×{qty} at market
          </button>
          <button
            type="button"
            onClick={disarm}
            className="rounded border border-slate-600/60 px-2 py-1 text-xs text-slate-300 hover:bg-slate-700/30"
          >
            Cancel
          </button>
        </div>
        {!hasBrokerMark && (
          <div className="text-[11px] text-amber-300/90">
            broker shows no position — this clears the tracking
          </div>
        )}
      </div>
    );
  }

  return (
    <button
      type="button"
      onClick={arm}
      className="rounded border border-rose-500/50 bg-rose-600/10 px-2 py-1 text-xs font-medium text-rose-300 hover:bg-rose-600/20"
    >
      Force exit
    </button>
  );
}
