"use client";

import { useEffect, useRef, useState } from "react";

// PLAN-2026-08-10-live-manual-bto: the /live "Manual entry" panel — the operator's way to OPEN a
// position by hand. Same interaction model as TrimButton (inline steps, no modal — the repo has no
// modal component), with the extra step a buy needs:
//
//   type a contract → [Buy] → quote + qty → [Confirm — BUY n at market] → submitting → outcome
//
// Every hard-won detail from TrimButton is reused deliberately, not copied by habit:
//   * an explicit `submitting` lock rather than useTransition's `pending` — React 18.3.1 closes a
//     transition scope at the first await, which would re-expose the button mid-flight and let a
//     second real-money order through;
//   * blur containment (`contains(relatedTarget)`) PLUS preventDefault on mousedown — the bug
//     fixed in a48c665, where every control but the autofocused one was unclickable because the
//     focusout bubbled and unmounted the row before mouseup landed;
//   * NO retry affordance on failure. A timed-out submit may already have opened a position;
//     recovery is re-quote-and-look, never one-click repeat.

export interface QuoteView {
  occ: string;
  underlying: string;
  expiry: string;
  strike: number;
  right: "C" | "P";
  bid: number | null;
  mid: number | null;
  ask: number;
  quoted_at: string;
}

export type QuoteActionResult =
  | { ok: true; quote: QuoteView }
  | { ok: false; kind: "invalid-occ"; detail: string }
  | { ok: false; kind: "unavailable" | "disabled" | "error" };

export type SubmitActionResult =
  | { ok: true; signalId: string; anchorAsk: number | null }
  | { ok: false; kind: "quote-moved"; confirmedAsk: number; currentAsk: number }
  | {
      ok: false;
      kind:
        | "quote-stale"
        | "quote-unavailable"
        | "duplicate"
        | "unknown-strategy"
        | "invalid-occ"
        | "disabled"
        | "error";
    };

export interface StatusView {
  state: "PENDING" | "REJECTED" | "SUBMITTED" | "FILLED" | "EXPIRED" | "ABORTED" | "FAILED";
  reason_code: string | null;
  reason_detail: string | null;
  option_symbol: string | null;
  contracts: number | null;
  filled_qty: number | null;
  avg_fill_price: string | number | null;
}

export interface StrategyOption {
  strategyId: string;
  enabled: boolean;
  // The workflow rejects MANUAL_QTY_OUT_OF_BOUNDS outside this range, so the picker offers exactly
  // it — the operator sees the ceiling rather than discovering it by being refused.
  minContracts: number;
  maxContracts: number;
}

// A quote goes stale server-side at 30s. Re-quote a little before that so the operator is not
// handed a refusal they could not have avoided.
const QUOTE_REFRESH_MS = 20_000;

// …but not forever. Each refresh is a server action → BFF hop → live Alpaca snapshot, so an
// operator who opens the confirm step and walks away would otherwise poll the market indefinitely.
// After this many refreshes the panel stops and makes them ask again explicitly.
const MAX_QUOTE_REFRESHES = 5;

// Poll cadence + ceiling for the outcome. The entry TTL is ~90s (pendingTtlSecs), so a poll window
// a bit past that covers "submitted → filled or expired" without spinning forever.
const POLL_INTERVAL_MS = 2_000;
const POLL_CEILING_MS = 120_000;

type Step =
  | { kind: "idle" }
  | { kind: "quoting" }
  | { kind: "confirm"; quote: QuoteView; idempotencyKey: string }
  | { kind: "submitting" }
  | { kind: "tracking"; signalId: string; status: StatusView | null }
  | { kind: "failed"; message: string };

function fmt(n: number | null | undefined): string {
  return n == null ? "—" : `$${Number(n).toFixed(2)}`;
}

/** Human-readable refusal. reason_code is the gate that refused; detail adds the numbers. */
function rejectionText(status: StatusView): string {
  const code = status.reason_code ?? "rejected";
  const detail = status.reason_detail;
  const friendly: Record<string, string> = {
    MANUAL_QTY_OUT_OF_BOUNDS: "Quantity outside this strategy's limits",
    NOTIONAL_CAP_EXCEEDED: "Notional cap has no room for this size",
    STRATEGY_DISABLED: "Strategy is disabled",
    CAPITAL_UNAVAILABLE: "Account cash unavailable",
    live_promotion_missing: "Live trading is not armed for this strategy",
  };
  const head = friendly[code] ?? code;
  return detail ? `${head} (${detail})` : head;
}

export function ManualEntryPanel({
  strategies,
  heldOccs,
  quoteAction,
  submitAction,
  statusAction,
}: {
  strategies: StrategyOption[];
  // Compact OCCs the tenant already holds, so the confirm step can warn about adding to a position
  // rather than silently opening a second one (WorkflowIds.position keys on the entry signal id, so
  // a repeat entry is a SECOND PositionWorkflow — same as a repeated Discord BTO).
  heldOccs: string[];
  quoteAction: (occ: string) => Promise<QuoteActionResult>;
  submitAction: (
    occ: string,
    strategyId: string,
    qty: number,
    quotedAsk: number,
    quotedAt: string,
    idempotencyKey: string,
  ) => Promise<SubmitActionResult>;
  statusAction: (signalId: string, strategyId: string) => Promise<StatusView | null>;
}) {
  const [occ, setOcc] = useState("");
  const [qty, setQty] = useState(String(strategies[0]?.minContracts ?? 1));
  const [strategyId, setStrategyId] = useState(strategies[0]?.strategyId ?? "");
  const [step, setStep] = useState<Step>({ kind: "idle" });
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  // clearTimers() alone does NOT stop the loops: if the component unmounts while a poll tick or a
  // quote refresh is awaiting its server action, the cleanup has already run by the time that
  // promise resolves, and the resolution then arms a fresh timer nothing will ever clear. Checked
  // after every await before re-arming.
  const cancelled = useRef(false);

  const clearTimers = () => {
    timers.current.forEach(clearTimeout);
    timers.current = [];
  };
  useEffect(() => {
    cancelled.current = false;
    return () => {
      cancelled.current = true;
      clearTimers();
    };
  }, []);

  const reset = () => {
    clearTimers();
    setStep({ kind: "idle" });
  };

  const handleBlur = (e: React.FocusEvent<HTMLDivElement>) => {
    // Containment check — see the header note on a48c665. Only disarm the CONFIRM step; the quote
    // step holds typed input the operator would lose.
    if (step.kind === "confirm" && !e.currentTarget.contains(e.relatedTarget as Node | null)) {
      reset();
    }
  };
  const keepFocus = (e: React.MouseEvent) => e.preventDefault();

  const selectedStrategy =
    strategies.find((s) => s.strategyId === strategyId) ?? strategies[0];
  const minQty = selectedStrategy?.minContracts ?? 1;
  const maxQty = Math.max(minQty, selectedStrategy?.maxContracts ?? 1);
  const qtyChoices = Array.from({ length: maxQty - minQty + 1 }, (_, i) => minQty + i);

  const quantity = Number.parseInt(qty, 10);
  const qtyValid = Number.isInteger(quantity) && quantity >= minQty && quantity <= maxQty;

  const requestQuote = async (refreshesLeft = MAX_QUOTE_REFRESHES) => {
    clearTimers();
    setStep({ kind: "quoting" });
    const r = await quoteAction(occ);
    if (cancelled.current) return;
    if (!r.ok) {
      const messages: Record<string, string> = {
        "invalid-occ": r.kind === "invalid-occ" ? r.detail : "",
        unavailable: "No quote available for that contract right now.",
        disabled: "Manual entry is not enabled.",
        error: "Could not read a quote.",
      };
      setStep({ kind: "failed", message: messages[r.kind] || "Could not read a quote." });
      return;
    }
    // Mint the idempotency key when the confirm step OPENS, not on click, so a double-click is one
    // entry. Re-quoting mints a new one — that is a genuinely new decision.
    setStep({
      kind: "confirm",
      quote: r.quote,
      idempotencyKey: crypto.randomUUID(),
    });
    // Auto-refresh the quote just under the server's 30s staleness bound, bounded so an abandoned
    // confirm step stops polling the market. When the budget runs out the quote simply goes stale
    // and the server refuses the submit — which is the correct, fail-closed outcome.
    if (refreshesLeft > 0) {
      timers.current.push(
        setTimeout(() => void requestQuote(refreshesLeft - 1), QUOTE_REFRESH_MS),
      );
    }
  };

  const submit = async (quote: QuoteView, idempotencyKey: string) => {
    clearTimers();
    setStep({ kind: "submitting" });
    const r = await submitAction(
      quote.occ,
      strategyId,
      quantity,
      quote.ask,
      quote.quoted_at,
      idempotencyKey,
    );
    if (cancelled.current) return;
    if (!r.ok) {
      const messages: Record<string, string> = {
        "quote-moved":
          r.kind === "quote-moved"
            ? `Price moved: you confirmed ${fmt(r.confirmedAsk)}, it is now ${fmt(r.currentAsk)}. Re-quote to continue.`
            : "",
        "quote-stale": "That quote expired. Re-quote to continue.",
        "quote-unavailable": "No quote available right now — nothing was ordered.",
        duplicate: "That entry was already submitted.",
        "unknown-strategy": "That strategy does not belong to this account.",
        "invalid-occ": "That contract is not valid.",
        disabled: "Manual entry is not enabled.",
        error: "Submit failed — re-quote and check Holdings before retrying.",
      };
      setStep({
        kind: "failed",
        message: messages[r.kind] || "Submit failed — check Holdings before retrying.",
      });
      return;
    }
    setStep({ kind: "tracking", signalId: r.signalId, status: null });
    poll(r.signalId, Date.now());
  };

  const poll = (signalId: string, startedAt: number) => {
    const tick = async () => {
      const status = await statusAction(signalId, strategyId);
      if (cancelled.current) return;
      setStep((prev) =>
        prev.kind === "tracking" && prev.signalId === signalId ? { ...prev, status } : prev,
      );
      const terminal =
        status != null && status.state !== "PENDING" && status.state !== "SUBMITTED";
      if (!terminal && Date.now() - startedAt < POLL_CEILING_MS) {
        timers.current.push(setTimeout(tick, POLL_INTERVAL_MS));
      }
    };
    timers.current.push(setTimeout(tick, POLL_INTERVAL_MS));
  };

  const alreadyHeld =
    step.kind === "confirm" && heldOccs.includes(step.quote.occ.replace(/\s+/g, ""));

  return (
    <section>
      <div className="mb-2 flex items-baseline justify-between">
        <h2 className="text-sm font-semibold text-slate-200">Manual entry</h2>
        <span className="text-xs text-slate-500">
          Buys at market, capped by this strategy&apos;s slippage limit
        </span>
      </div>
      <div className="rounded border border-slate-800 bg-slate-900 px-3 py-3">
        {(step.kind === "idle" || step.kind === "quoting" || step.kind === "failed") && (
          <div className="flex flex-wrap items-center gap-2">
            <input
              type="text"
              value={occ}
              onChange={(e) => setOcc(e.target.value)}
              placeholder="NVDA 260821C00225000"
              spellCheck={false}
              className="w-64 rounded border border-slate-700 bg-slate-950 px-2 py-1 font-mono text-xs text-slate-100 placeholder:text-slate-600"
            />
            {strategies.length > 1 && (
              <select
                value={strategyId}
                onChange={(e) => setStrategyId(e.target.value)}
                className="rounded border border-slate-700 bg-slate-950 px-2 py-1 text-xs text-slate-100"
              >
                {strategies.map((s) => (
                  <option key={s.strategyId} value={s.strategyId}>
                    {s.strategyId}
                    {s.enabled ? "" : " (disabled)"}
                  </option>
                ))}
              </select>
            )}
            <button
              type="button"
              disabled={!occ.trim() || !strategyId || step.kind === "quoting"}
              onClick={() => void requestQuote()}
              className="rounded border border-emerald-500/50 bg-emerald-600/10 px-3 py-1 text-xs font-medium text-emerald-300 hover:bg-emerald-600/20 disabled:cursor-not-allowed disabled:border-slate-700 disabled:bg-transparent disabled:text-slate-600"
            >
              {step.kind === "quoting" ? "Quoting…" : "Buy"}
            </button>
            {step.kind === "failed" && (
              <span className="text-xs font-medium text-rose-300" role="alert">
                {step.message}
              </span>
            )}
          </div>
        )}

        {step.kind === "confirm" && (
          <div className="flex flex-col gap-2" onBlur={handleBlur} onMouseDown={keepFocus}>
            <div className="font-mono text-xs text-slate-200">
              {step.quote.occ.replace(/\s+/g, " ").trim()}
              <span className="ml-2 font-sans text-slate-400">
                {step.quote.underlying} · {step.quote.expiry} · ${step.quote.strike}{" "}
                {step.quote.right === "C" ? "Call" : "Put"}
              </span>
            </div>
            <div className="flex flex-wrap items-center gap-3 text-xs text-slate-300">
              <span>
                bid <span className="text-slate-100">{fmt(step.quote.bid)}</span>
              </span>
              <span>
                mid <span className="text-slate-100">{fmt(step.quote.mid)}</span>
              </span>
              <span>
                ask <span className="font-medium text-slate-100">{fmt(step.quote.ask)}</span>
              </span>
              <label className="flex items-center gap-1">
                qty
                <select
                  value={qty}
                  onChange={(e) => setQty(e.target.value)}
                  className="rounded border border-slate-700 bg-slate-950 px-2 py-1 text-xs text-slate-100"
                >
                  {qtyChoices.map((n) => (
                    <option key={n} value={n}>
                      {n}
                    </option>
                  ))}
                </select>
              </label>
              <span className="text-[11px] text-slate-500">
                max {maxQty} for {strategyId}
              </span>
              {qtyValid && (
                <span className="text-slate-400">
                  ≈ {fmt(step.quote.ask * quantity * 100)} at the ask
                </span>
              )}
            </div>
            {alreadyHeld && (
              <div className="text-xs text-amber-300">
                You already hold this contract — this opens a SECOND position, it does not add to
                the existing one.
              </div>
            )}
            <div className="flex items-center gap-2">
              <button
                type="button"
                autoFocus
                disabled={!qtyValid}
                onClick={() => void submit(step.quote, step.idempotencyKey)}
                className="rounded border border-emerald-500/60 bg-emerald-600/20 px-2 py-1 text-xs font-medium text-emerald-100 hover:bg-emerald-600/30 disabled:cursor-not-allowed disabled:border-slate-700 disabled:bg-transparent disabled:text-slate-600"
              >
                Confirm — BUY {qtyValid ? quantity : "?"} at market (limit ≥ {fmt(step.quote.ask)})
              </button>
              <button
                type="button"
                onClick={reset}
                className="rounded border border-slate-600/60 px-2 py-1 text-xs text-slate-300 hover:bg-slate-700/30"
              >
                Cancel
              </button>
            </div>
          </div>
        )}

        {step.kind === "submitting" && (
          <span className="text-xs font-medium text-slate-300" role="status" aria-live="polite">
            Submitting…
          </span>
        )}

        {step.kind === "tracking" && (
          <div className="flex flex-col gap-1 text-xs" role="status" aria-live="polite">
            <EntryOutcome status={step.status} />
            <button
              type="button"
              onClick={reset}
              className="self-start rounded border border-slate-600/60 px-2 py-1 text-xs text-slate-300 hover:bg-slate-700/30"
            >
              New entry
            </button>
          </div>
        )}
      </div>
    </section>
  );
}

/** The terminal (or in-flight) outcome of a submitted entry. */
function EntryOutcome({ status }: { status: StatusView | null }) {
  if (status == null || status.state === "PENDING") {
    return <span className="text-slate-300">Submitted · running the risk gates…</span>;
  }
  switch (status.state) {
    case "SUBMITTED":
      return (
        <span className="text-sky-300">
          Order placed{status.contracts != null ? ` (${status.contracts})` : ""} · waiting for the
          fill…
        </span>
      );
    case "FILLED":
      return (
        <span className="font-medium text-emerald-300">
          Filled {status.filled_qty ?? ""} @ {fmt(Number(status.avg_fill_price))} — the position is
          now in Holdings.
        </span>
      );
    case "REJECTED":
      return (
        <span className="font-medium text-rose-300">Rejected: {rejectionText(status)}</span>
      );
    case "EXPIRED":
      return (
        <span className="font-medium text-amber-300">
          Not filled before the entry timeout — nothing was bought.
        </span>
      );
    case "ABORTED":
      return (
        <span className="font-medium text-rose-300">
          Aborted by the kill switch{status.reason_detail ? `: ${status.reason_detail}` : ""}.
        </span>
      );
    case "FAILED":
    default:
      return (
        <span className="font-medium text-rose-300">
          Entry failed — check Holdings and the audit log before retrying.
        </span>
      );
  }
}
