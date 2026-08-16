"use client";

import { useEffect, useRef, useState } from "react";

// Client island for the per-position "Stop-loss" button on /live — arms the EXISTING chandelier
// trail (PositionWorkflowImpl's armChandelier path), it does not introduce a second stop mechanism.
// Same interaction model as TrimButton, with the same three states, because arming a stop needs a
// SIZE-equivalent choice (the trailing percentage) as well as a confirmation:
//   "Stop-loss" -> a row of trailing percentages labelled with the stop price each implies
//               -> "Set — trails 35%, stop now ≈ $1.63"
// The picker and the confirm both auto-disarm after a few seconds or on blur, so a stray click never
// leaves a primed action sitting in the table.
//
// THE STOP IS PEAK-ANCHORED AND MOVES UP, NEVER DOWN: fire = peak * (1 - giveback). Every label here
// says so. An operator who reads "stop $3.19" as a fixed floor they have set has misunderstood the
// instrument, and the UI is what teaches them — so the price is always rendered as "now ≈", never as
// a value they chose.

// Result the server action threads back so the row can show a terminal outcome inline. Mirrors
// TrimActionResult. `already-armed` is not a failure: the position already has a trail and this
// one did not loosen it. `rejected` carries the workflow's own reason (bad giveback, no resolvable
// anchor, market-data subscribe failed) — surfacing it matters more here than anywhere else on the
// page, because the failure mode being guarded against is an operator believing a stop exists when
// it does not.
export type StopLossActionResult =
  | { ok: true; givebackPct: number; stopPrice: number | null }
  | {
      ok: false;
      kind: "already-armed" | "rejected" | "disabled" | "error";
      reason?: string;
    };

// How long an armed picker/confirm stays primed before auto-disarming (ms). Matches TrimButton.
const CONFIRM_TIMEOUT_MS = 5000;

// The offered trailing percentages. Fractions (not prices) because that is what the workflow stores
// and what the trail is actually defined by — the price is a consequence, recomputed on every tick.
//
// Centred on 35%, NOT the 10-25% band a stock-trading instinct suggests. These are short-dated
// options, whose premium can move tens of percent on an underlying move of one or two: a 10% trail
// on a $2 contract fires at $1.80, which is inside ordinary intraday noise for this instrument. A
// stop that tight does not protect the position, it just sells it early and calls that protection —
// and the operator's first experience of the feature would be a runner cut for no reason.
//
// 0.5 (MAX_GIVEBACK) is deliberately not offered: it is the workflow's hard ceiling, and a control
// whose widest preset is also the absolute limit invites clicking straight to the edge.
const PRESET_GIVEBACKS = [0.25, 0.35, 0.45];

// Pre-selected preset — the one the picker focuses, so Enter arms it. Chosen for the volatility of
// the instrument, not as a midpoint of the list.
const DEFAULT_GIVEBACK = 0.35;

// PositionWorkflowImpl.MAX_GIVEBACK. The workflow REJECTS anything above this, and the
// strategy-config schema caps trail_giveback_pct at the same value. Offering a preset the workflow
// would refuse is how an operator learns to distrust the button, so this is asserted, not assumed.
const MAX_GIVEBACK = 0.5;

/**
 * The stop price a giveback implies at the given premium: `premium * (1 - giveback)`, rounded to a
 * cent. MUST match PositionWorkflowImpl's fire threshold so the confirm text is the truth and not an
 * estimate — the same discipline TrimButton applies to qtyForFraction.
 *
 * Returns null when there is no live premium to anchor against; the caller renders the preset
 * without a price rather than inventing one.
 */
export function stopPriceFor(
  premium: number | null | undefined,
  giveback: number,
): number | null {
  if (premium == null || !Number.isFinite(premium) || premium <= 0) return null;
  return Math.round(premium * (1 - giveback) * 100) / 100;
}

/** Presets worth offering: never above what the workflow will accept. */
export function usableGivebacks(): number[] {
  return PRESET_GIVEBACKS.filter((g) => g > 0 && g <= MAX_GIVEBACK);
}

export function StopLossButton({
  workflowId,
  symbol,
  currentPrice,
  armedGivebackPct,
  action,
}: {
  workflowId: string;
  symbol: string;
  // Latest mark for this position, used ONLY to preview the stop price. The anchor the trail
  // actually arms at is resolved server-side from the workflow's own peak / a fresh quote, so a
  // stale or missing price here can mislead the preview but can never set the stop wrong.
  currentPrice: number | null;
  // Non-null when this position already has a trail armed — the control then reports it instead of
  // offering to arm again.
  //
  // NOTHING SUPPLIES THIS YET. /live cannot: the BFF's PositionsReader maps only
  // (contractSymbol, remainingQty, entryPremium) off the PositionState query, which carries no
  // trailing state, so surfacing it needs that query record, the BFF's reflective mapping, the API
  // shape and the page all extended — a separate change from this one. Stated here rather than left
  // implicit, because a prop that looks wired and is not is the same trap as the schema claiming a
  // "smoothed mid" that never existed.
  //
  // The degradation is safe, not silent: after a refresh an armed position renders as un-armed, so
  // an operator may arm again — and that returns ALREADY_ARMED (200), which this control shows as
  // "Already trailing" without touching the existing stop.
  armedGivebackPct?: number | null;
  action: (
    workflowId: string,
    givebackPct: number,
  ) => Promise<StopLossActionResult>;
}) {
  // Explicit in-flight lock rather than useTransition's `pending`: React 18.3.1 closes a transition
  // scope at the first `await`, which would re-expose a clickable button mid-flight. See
  // TrimButton/ForceExitButton for the same reasoning.
  const [submitting, setSubmitting] = useState(false);
  const [picking, setPicking] = useState(false);
  const [confirming, setConfirming] = useState<number | null>(null);
  const [result, setResult] = useState<StopLossActionResult | null>(null);
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

  // Disarm only when focus leaves the CONTROL, not when it moves between the buttons inside it.
  // React's onBlur is focusout, which BUBBLES: without the containment check, clicking any preset
  // other than the autofocused first one blurs that first button, bubbles to the container, and
  // disarms the picker on mousedown — so the picker unmounts before mouseup and the click never
  // lands. A null relatedTarget (focus left to nothing) is correctly treated as "left the control".
  // This is TrimButton's bug, already paid for once; copied deliberately rather than re-derived.
  const handleBlur = (e: React.FocusEvent<HTMLDivElement>) => {
    if (!e.currentTarget.contains(e.relatedTarget as Node | null)) {
      disarm();
    }
  };

  // Belt-and-braces for the same failure: preventDefault on mousedown stops the browser moving
  // focus on click at all, so no focusout fires for ANY mouse interaction inside the control.
  // Safari and Firefox do not focus a <button> on click — there relatedTarget would be null and the
  // containment check alone would still swallow every preset click. Keyboard focus/Tab is
  // unaffected, so the containment check remains the guard for keyboard users.
  const keepFocus = (e: React.MouseEvent) => e.preventDefault();

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
  const pick = (giveback: number) => {
    setPicking(false);
    setConfirming(giveback);
    armTimer();
  };
  const fire = (giveback: number) => {
    clearTimer();
    setPicking(false);
    setConfirming(null);
    setSubmitting(true);
    action(workflowId, giveback)
      .then(setResult)
      .finally(() => setSubmitting(false));
  };

  const presets = usableGivebacks();

  // Already trailing — report it rather than offering a second arm. Re-arming is the workflow's
  // decision (it refuses to LOOSEN an existing stop), so the UI does not present a control whose
  // outcome it cannot predict.
  if (armedGivebackPct != null) {
    const stop = stopPriceFor(currentPrice, armedGivebackPct);
    return (
      <span className="text-xs font-medium text-emerald-300" role="status">
        Trailing {Math.round(armedGivebackPct * 100)}%
        {stop != null && ` · stop now ≈ $${stop.toFixed(2)}`}
      </span>
    );
  }

  // Terminal result — a brief inline note in place of the button. The row does NOT disappear: a
  // stop is armed on a position that stays open.
  if (result) {
    if (result.ok) {
      return (
        <span className="text-xs font-medium text-emerald-300" role="status">
          Trailing {Math.round(result.givebackPct * 100)}% set
          {result.stopPrice != null &&
            ` · stop now ≈ $${result.stopPrice.toFixed(2)}`}
        </span>
      );
    }
    if (result.kind === "already-armed") {
      return (
        <span className="text-xs font-medium text-amber-300" role="status">
          Already trailing
        </span>
      );
    }
    // A retry affordance IS offered here, unlike TrimButton. Re-arming is not a second independent
    // trade — the workflow treats a repeat as ALREADY_ARMED and will not loosen an existing stop —
    // whereas a failed arm leaves the position UNPROTECTED, which is the state the operator was
    // trying to leave. Making them refresh to try again is the wrong trade-off here.
    return (
      <span className="flex items-center gap-2">
        <span className="text-xs font-medium text-rose-300" role="alert">
          {result.kind === "disabled"
            ? "Not enabled"
            : `No stop set${result.reason ? ` — ${result.reason}` : ""}`}
        </span>
        {result.kind !== "disabled" && (
          <button
            type="button"
            onClick={openPicker}
            className="rounded border border-slate-600/60 px-2 py-1 text-xs text-slate-300 hover:bg-slate-700/30"
          >
            Retry
          </button>
        )}
      </span>
    );
  }

  // In-flight lock: no clickable arm/confirm button is reachable while the request is running.
  if (submitting) {
    return (
      <span
        className="text-xs font-medium text-slate-300"
        role="status"
        aria-live="polite"
      >
        Setting stop…
      </span>
    );
  }

  if (confirming != null) {
    const stop = stopPriceFor(currentPrice, confirming);
    return (
      <div
        className="flex items-center gap-2"
        onBlur={handleBlur}
        onMouseDown={keepFocus}
      >
        <button
          type="button"
          autoFocus
          onClick={() => fire(confirming)}
          className="rounded border border-emerald-500/60 bg-emerald-600/20 px-2 py-1 text-xs font-medium text-emerald-100 hover:bg-emerald-600/30"
        >
          Set — trails {Math.round(confirming * 100)}% on {symbol}
          {stop != null
            ? `, stop now ≈ $${stop.toFixed(2)}`
            : ", stop set from the live quote"}
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
      <div
        className="flex items-center gap-1"
        onBlur={handleBlur}
        onMouseDown={keepFocus}
      >
        <span className="pr-1 text-[11px] text-slate-400">Trail</span>
        {presets.map((giveback) => {
          const stop = stopPriceFor(currentPrice, giveback);
          const isDefault = giveback === DEFAULT_GIVEBACK;
          return (
            <button
              key={giveback}
              type="button"
              // Focus the DEFAULT rather than the first preset: Enter should arm 35%, and the
              // autofocused button is also the one a mouse click cannot blur (see handleBlur).
              autoFocus={isDefault}
              onClick={() => pick(giveback)}
              className={
                isDefault
                  ? "rounded border border-emerald-400 bg-emerald-600/25 px-2 py-1 text-xs font-semibold text-emerald-100 hover:bg-emerald-600/35"
                  : "rounded border border-emerald-500/50 bg-emerald-600/10 px-2 py-1 text-xs font-medium text-emerald-200 hover:bg-emerald-600/20"
              }
            >
              {Math.round(giveback * 100)}%
              {stop != null && ` · $${stop.toFixed(2)}`}
            </button>
          );
        })}
        <span className="pl-1 text-[11px] text-slate-500">
          rises with the peak
        </span>
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
      className="rounded border border-emerald-500/50 bg-emerald-600/10 px-2 py-1 text-xs font-medium text-emerald-300 hover:bg-emerald-600/20"
    >
      Stop-loss
    </button>
  );
}
