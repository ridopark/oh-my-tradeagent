"use client";

import { useEffect, useRef, useState } from "react";

// Client island for the per-position "Trim" button on /live — the reduce-only sibling of
// ForceExitButton. Same interaction model (inline confirm, no modal component exists in the repo)
// with one extra step, because a trim needs a SIZE as well as a confirmation:
//   "Trim" -> a row of preset fractions labelled with the contracts they actually sell
//          -> "Confirm — sells N of M at market"
// The picker and the confirm both auto-disarm after a few seconds or on blur, so a stray click never
// leaves a primed real-money sell sitting in the table.

// Result the server action threads back so the row can show a terminal outcome inline. Mirrors
// ForceExitActionResult: `ok` covers a placed trim (202) and a benign no-op (200); `already-closed`
// is the render-vs-click race; `disabled` is the dark-flag-off 404; `error` is anything else.
export type TrimActionResult =
  | { ok: true }
  | { ok: false; kind: "already-closed" | "disabled" | "error" };

// How long an armed picker/confirm stays primed before auto-disarming (ms). Matches ForceExitButton.
const CONFIRM_TIMEOUT_MS = 5000;

// The offered trim sizes. Fractions (not contract counts) because that is what the workflow's
// partial_close Update takes; each is rendered with the qty it resolves to for THIS position.
const PRESET_FRACTIONS = [0.25, 0.5, 0.75];

// The contracts a given fraction actually sells. MUST match PositionWorkflowImpl.processOne's
// qtyToClose exactly — min(remaining, ceil(remaining * fraction)) — so the confirm text is the truth
// and not an estimate.
function qtyForFraction(remainingQty: number, fraction: number): number {
  return Math.min(remainingQty, Math.ceil(remainingQty * fraction));
}

// Presets worth offering for this position: a preset that resolves to 0 contracts does nothing, and
// one that resolves to the WHOLE position is a full exit (Force exit's job, and the workflow
// validator rejects fraction >= 1 anyway). Presets that collapse onto the same qty — 25% and 50% of
// a 2-lot are both 1 contract — are de-duplicated so the operator is never offered the same trade
// twice under two labels.
export function usablePresets(
  remainingQty: number,
): { fraction: number; qty: number }[] {
  const seen = new Set<number>();
  const out: { fraction: number; qty: number }[] = [];
  for (const fraction of PRESET_FRACTIONS) {
    const qty = qtyForFraction(remainingQty, fraction);
    if (qty < 1 || qty >= remainingQty || seen.has(qty)) continue;
    seen.add(qty);
    out.push({ fraction, qty });
  }
  return out;
}

export function TrimButton({
  workflowId,
  symbol,
  qty,
  action,
}: {
  workflowId: string;
  symbol: string;
  // Remaining contracts as of this render. A trim of a 1-lot is impossible (every fraction either
  // rounds to 0 or to the whole lot), so the button renders nothing at all in that case.
  qty: number;
  action: (workflowId: string, fraction: number) => Promise<TrimActionResult>;
}) {
  // Explicit in-flight lock rather than useTransition's `pending`: React 18.3.1 closes a transition
  // scope at the first `await`, which would re-expose a clickable button mid-flight and let an
  // operator fire a SECOND real-money sell. See ForceExitButton for the same reasoning.
  const [submitting, setSubmitting] = useState(false);
  const [picking, setPicking] = useState(false);
  const [confirming, setConfirming] = useState<{
    fraction: number;
    qty: number;
  } | null>(null);
  const [result, setResult] = useState<TrimActionResult | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearTimer = () => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  };
  // Drop the auto-disarm timer if the row unmounts (revalidation drops a closed position).
  useEffect(() => clearTimer, []);

  const disarm = () => {
    clearTimer();
    setPicking(false);
    setConfirming(null);
  };
  const armTimer = () => {
    clearTimer();
    timerRef.current = setTimeout(disarm, CONFIRM_TIMEOUT_MS);
  };
  const openPicker = () => {
    setResult(null);
    setConfirming(null);
    setPicking(true);
    armTimer();
  };
  const pick = (preset: { fraction: number; qty: number }) => {
    setPicking(false);
    setConfirming(preset);
    armTimer();
  };
  const fire = (fraction: number) => {
    clearTimer();
    setPicking(false);
    setConfirming(null);
    setSubmitting(true);
    action(workflowId, fraction)
      .then(setResult)
      .finally(() => setSubmitting(false));
  };

  const presets = usablePresets(qty);
  // Nothing sensible to offer (a 1-lot): render no control at all rather than a dead button. Force
  // exit remains the way out of the last contract.
  if (presets.length === 0) {
    return null;
  }

  // Terminal result — a brief inline note in place of the button. Unlike a force exit the row does
  // NOT disappear (the position lives on with fewer contracts); the qty column updates on the next
  // revalidation.
  if (result) {
    if (result.ok) {
      return (
        <span className="text-xs font-medium text-emerald-300" role="status">
          Trim placed
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
    // NO retry affordance here, deliberately — unlike ForceExitButton. A failure can mean the
    // request timed out AFTER the workflow accepted it, and a retried trim is a SECOND independent
    // sell of a fraction of what is left (retrying a force exit is harmless: the second flatten
    // no-ops). The operator must re-read the refreshed qty and decide again, so the recovery path
    // is a page refresh, not a one-click repeat.
    return (
      <span className="text-xs font-medium text-rose-300" role="alert">
        {result.kind === "disabled" ? "Not enabled" : "Failed — refresh to retry"}
      </span>
    );
  }

  // In-flight lock: no clickable trim/confirm button is reachable while the sell is running.
  if (submitting) {
    return (
      <span
        className="text-xs font-medium text-slate-300"
        role="status"
        aria-live="polite"
      >
        Trimming…
      </span>
    );
  }

  if (confirming) {
    return (
      <div className="flex items-center gap-2" onBlur={disarm}>
        <button
          type="button"
          autoFocus
          onClick={() => fire(confirming.fraction)}
          className="rounded border border-amber-500/60 bg-amber-600/20 px-2 py-1 text-xs font-medium text-amber-100 hover:bg-amber-600/30"
        >
          Confirm — sells {confirming.qty} of {qty} {symbol} at market
        </button>
        <button
          type="button"
          onClick={disarm}
          className="rounded border border-slate-600/60 px-2 py-1 text-xs text-slate-300 hover:bg-slate-700/30"
        >
          Cancel
        </button>
      </div>
    );
  }

  if (picking) {
    return (
      <div className="flex items-center gap-1" onBlur={disarm}>
        <span className="pr-1 text-[11px] text-slate-400">Trim</span>
        {presets.map((preset, i) => (
          <button
            key={preset.fraction}
            type="button"
            autoFocus={i === 0}
            onClick={() => pick(preset)}
            className="rounded border border-amber-500/50 bg-amber-600/10 px-2 py-1 text-xs font-medium text-amber-200 hover:bg-amber-600/20"
          >
            {Math.round(preset.fraction * 100)}% · {preset.qty}
          </button>
        ))}
        <button
          type="button"
          onClick={disarm}
          className="rounded border border-slate-600/60 px-2 py-1 text-xs text-slate-300 hover:bg-slate-700/30"
        >
          Cancel
        </button>
      </div>
    );
  }

  return (
    <button
      type="button"
      onClick={openPicker}
      className="rounded border border-amber-500/50 bg-amber-600/10 px-2 py-1 text-xs font-medium text-amber-300 hover:bg-amber-600/20"
    >
      Trim
    </button>
  );
}
