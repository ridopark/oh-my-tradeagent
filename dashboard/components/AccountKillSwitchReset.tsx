"use client";

import { useEffect, useState, useTransition } from "react";

// Client island for the tenant-self-service reset of the account daily-loss kill switch. Mirrors
// ActivateButton (useTransition + the writeEnabled dark-launch gate + a server-action prop), but adds
// an OBVIOUS live 15-minute circuit-breaker countdown: while resettableAt is in the future the reset
// button is disabled and shows "Reset available in MM:SS"; when it reaches 0 the button enables.
//
// The countdown, computed from resettableAt, is the live source of truth: a page that renders after
// the wait already elapsed is enabled immediately (0 remaining), and a mid-wait render shows the
// ticking clock. The server still authoritatively re-enforces the wait on the reset POST.

// Server action result shape. On success the action redirects (throws a Next redirect signal, so it
// never returns here). It returns a value ONLY on the circuit-breaker race — the wait wasn't actually
// elapsed server-side — so the client can resync its countdown from the authoritative resettableAt.
type ResetActionResult =
  | { circuitBreakerActive?: boolean; resettableAt?: string | null }
  | void;

// Milliseconds until `target`, floored at 0 (a past/null target means "no wait left").
function remainingMs(target: string | null): number {
  if (!target) return 0;
  const ms = new Date(target).getTime() - Date.now();
  return ms > 0 ? ms : 0;
}

// MM:SS from a ms remaining, rounding up so the last visible second is 00:01 not 00:00.
function fmtCountdown(ms: number): string {
  const totalSeconds = Math.ceil(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

export function AccountKillSwitchReset({
  trippedAt,
  resettableAt,
  action,
  writeEnabled,
}: {
  trippedAt: string | null;
  resettableAt: string | null;
  action: () => Promise<ResetActionResult>;
  writeEnabled: boolean;
}) {
  const [pending, startTransition] = useTransition();
  // The countdown target can be RESYNCED by the action if a click loses the circuit-breaker race.
  const [target, setTarget] = useState<string | null>(resettableAt);
  const [remaining, setRemaining] = useState<number>(() => remainingMs(resettableAt));

  useEffect(() => {
    setRemaining(remainingMs(target));
    if (!target) return;
    const id = setInterval(() => {
      const r = remainingMs(target);
      setRemaining(r);
      if (r <= 0) clearInterval(id); // stop ticking once the button is enabled.
    }, 1000);
    return () => clearInterval(id);
  }, [target]);

  const waiting = remaining > 0;
  const disabled = !writeEnabled || pending || waiting;

  const label = pending
    ? "Resetting…"
    : waiting
      ? `Reset available in ${fmtCountdown(remaining)}`
      : "Reset account kill switch";

  return (
    <div className="flex flex-col gap-2">
      {trippedAt && (
        <div className="text-xs text-red-200/70">
          Halted at {new Date(trippedAt).toLocaleString()}
        </div>
      )}
      {waiting && (
        <div
          className="text-sm font-medium text-red-200"
          role="status"
          aria-live="polite"
        >
          Circuit breaker: reset available in{" "}
          <span className="font-mono tabular-nums">{fmtCountdown(remaining)}</span>
        </div>
      )}
      <button
        type="button"
        disabled={disabled}
        aria-disabled={disabled}
        aria-label={
          waiting
            ? `Reset locked — available in ${fmtCountdown(remaining)}`
            : "Reset account kill switch"
        }
        onClick={() => {
          if (disabled) return;
          startTransition(async () => {
            const result = await action();
            // Lost the circuit-breaker race: server says the wait wasn't actually elapsed. Keep the
            // button disabled and resync the countdown from the authoritative resettableAt.
            if (result?.circuitBreakerActive && result.resettableAt) {
              setTarget(result.resettableAt);
            }
          });
        }}
        className={`self-start rounded border px-3 py-1.5 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
          disabled
            ? "border-slate-600/60 bg-slate-700/20 text-slate-300"
            : "border-red-500/60 bg-red-600/20 text-red-200 hover:bg-red-600/30"
        }`}
      >
        {label}
      </button>
      {!writeEnabled && (
        <div className="text-xs text-slate-500">Reset is not enabled.</div>
      )}
    </div>
  );
}
