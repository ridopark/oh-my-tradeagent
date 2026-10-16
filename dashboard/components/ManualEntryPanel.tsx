"use client";

import { useEffect, useRef, useState } from "react";
import { usePanelOpen } from "./LiveRefresh";
import { Modal } from "./Modal";
import { useRegisterBuyIdea } from "./WatchlistBuyBridge";
import { decideQuoteRefresh } from "@/lib/quoteRefresh";
import { ContractSearch, type Suggestion } from "./ContractSearch";
import { etClock, type WatchlistIdea } from "@/lib/watchlist";
import {
  OptionChain,
  IdeaStatusPill,
  type ChainFocus,
  breakeven,
  compactOcc,
  daysToExpiry,
  fmtPrice,
  type ChainResult,
  type ExpirationsResult,
  type PickedContract,
} from "./OptionChain";

// PLAN-2026-08-10-live-manual-bto: the /live "Manual entry" panel — the operator's way to OPEN a
// position by hand. Two ways in, one confirm flow, shown in a modal:
//
//   type a contract → [Buy] ─────────────────────────────┐
//   [Browse chain] → pick a strike → details → [Buy — get quote]
//                                                         ↓
//            quote + qty → [Confirm — BUY n at market] → submitting → outcome
//
// The chain only picks a contract; every order still goes through the fresh quote + confirm.
// Every hard-won detail from TrimButton is reused deliberately, not copied by habit:
//   * an explicit `submitting` lock rather than useTransition's `pending` — React 18.3.1 closes a
//     transition scope at the first await, which would re-expose the button mid-flight and let a
//     second real-money order through. The modal holds the same line: it refuses to close while
//     submitting, and a quote refresh that lands after Confirm is dropped (scheduleRefresh);
//   * NO retry affordance on failure. A timed-out submit may already have opened a position;
//     recovery is re-quote-and-look, never one-click repeat.
//
// The confirm step used to be inline, guarded by blur containment against the a48c665 bug
// (focusout unmounting the row before mouseup landed). In the modal that guard is gone: focus is
// trapped by the dialog, and closing it is an explicit Cancel.

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
  // The chain inside the modal, opened by a watchlist row's Buy (WatchlistBuyBridge).
  | { kind: "chain" }
  | { kind: "detail" }
  | { kind: "quoting" }
  | { kind: "confirm"; quote: QuoteView; idempotencyKey: string }
  | { kind: "submitting" }
  | { kind: "tracking"; signalId: string; status: StatusView | null }
  | { kind: "failed"; message: string; inModal: boolean };

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

/**
 * A contract the system recently signalled, with the context an operator needs to tell two similar
 * strikes apart: what the signal was priced at and how big it was sized. Built server-side on /live
 * from the accepted-signal feed; `refPremium`/`contracts` are nullable because the subject is audit
 * JSON, not a typed row.
 */
export interface RecentContract {
  occ: string;
  refPremium: number | null;
  contracts: number | null;
  /** MM-DD of the signal, the only part of the date worth the width here. */
  on: string;
}

/** Today's watchlist as the panel holds it; a read is refreshed after this long. */
const WATCHLIST_STALE_MS = 60_000;

type WatchlistResult =
  | { ok: true; etDate: string; posted: boolean; ideas: WatchlistIdea[] }
  | { ok: false; kind: "invalid" | "unavailable" | "disabled" | "error" };

type WatchlistState =
  | { state: "idle" | "loading" | "failed" }
  | { state: "ok"; etDate: string; posted: boolean; ideas: WatchlistIdea[]; readAt: number };

/**
 * The recent signal matching whatever OCC is currently typed, compared on the COMPACT form so a
 * pasted padded symbol and a hand-typed unpadded one both resolve. Null when the operator is
 * entering a contract the system never signalled — which is legitimate, so the absence of a hint is
 * not a warning.
 */
function matchingSignal(occ: string, recent: RecentContract[]): RecentContract | null {
  const key = occ.replace(/\s+/g, "").toUpperCase();
  if (!key) {
    return null;
  }
  return recent.find((c) => c.occ.replace(/\s+/g, "").toUpperCase() === key) ?? null;
}

export function ManualEntryPanel({
  strategies,
  heldOccs,
  recentContracts,
  quoteAction,
  chainExpirationsAction,
  optionChainAction,
  submitAction,
  statusAction,
  watchlistAction,
}: {
  strategies: StrategyOption[];
  // Compact OCCs the tenant already holds, so the confirm step can warn about adding to a position
  // rather than silently opening a second one (WorkflowIds.position keys on the entry signal id, so
  // a repeat entry is a SECOND PositionWorkflow — same as a repeated Discord BTO).
  heldOccs: string[];
  /**
   * Contracts from recent entry signals (accepted, or received and then rejected — e.g. on
   * MAX_POSITIONS), newest first, offered as dropdown suggestions.
   * Suggestions only — the input still accepts anything typed by hand, which is the whole point of
   * a manual entry box. Empty when the signals read failed or the BFF predates /api/signals.
   */
  recentContracts: RecentContract[];
  quoteAction: (occ: string) => Promise<QuoteActionResult>;
  chainExpirationsAction: (underlying: string) => Promise<ExpirationsResult>;
  optionChainAction: (
    underlying: string,
    expiration: string,
    right: "C" | "P",
  ) => Promise<ChainResult>;
  submitAction: (
    occ: string,
    strategyId: string,
    qty: number,
    quotedAsk: number,
    quotedAt: string,
    idempotencyKey: string,
  ) => Promise<SubmitActionResult>;
  statusAction: (signalId: string, strategyId: string) => Promise<StatusView | null>;
  /** Today's shared watchlist, for the dropdown and the chain's highlights. Display-only. */
  watchlistAction: () => Promise<WatchlistResult>;
}) {
  const [occ, setOcc] = useState("");
  const [qty, setQty] = useState(String(strategies[0]?.minContracts ?? 1));
  const [strategyId, setStrategyId] = useState(strategies[0]?.strategyId ?? "");
  const [step, setStep] = useState<Step>({ kind: "idle" });
  // The step as of the last render, for async callbacks that must not act on a step the operator
  // has already moved past (see scheduleRefresh).
  const stepRef = useRef(step);
  stepRef.current = step;
  // Whether the step is being shown in the modal. Separate from the step on purpose: closing the
  // modal while an entry is being TRACKED must not lose the outcome — it moves to the panel instead.
  const [modalOpen, setModalOpen] = useState(false);
  // The underlying the chain drawer is showing (null = drawer closed), and the row picked from it.
  const [chainUnderlying, setChainUnderlying] = useState<string | null>(null);
  const [picked, setPicked] = useState<PickedContract | null>(null);
  // Set when the chain opens from a watchlist idea: land on its expiry, side and strike. A fresh
  // object per pick, so picking another idea on the same underlying re-lands.
  const [chainFocus, setChainFocus] = useState<ChainFocus | null>(null);
  const [watchlist, setWatchlist] = useState<WatchlistState>({ state: "idle" });
  // True while the chain is shown in the modal (a watchlist Buy) rather than inline in the panel.
  const [chainInModal, setChainInModal] = useState(false);
  usePanelOpen(step.kind !== "idle" || chainUnderlying !== null);
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

  /** The modal's chain (a watchlist Buy) ends with the modal; it must not reappear inline. */
  const endModalChain = () => {
    if (!chainInModal) return;
    setChainInModal(false);
    setChainUnderlying(null);
    setChainFocus(null);
  };

  const reset = () => {
    clearTimers();
    setStep({ kind: "idle" });
    setModalOpen(false);
    endModalChain();
  };

  /**
   * Esc, ✕ and a backdrop click all land here. While SUBMITTING the modal stays put: the order may
   * already be in flight and its outcome is about to be shown. While TRACKING it closes but the
   * outcome (and its poll) carries on in the panel. Anywhere else closing is Cancel.
   */
  const closeModal = () => {
    if (step.kind === "submitting") return;
    if (step.kind === "tracking") {
      setModalOpen(false);
      endModalChain();
      return;
    }
    reset();
  };

  const selectedStrategy =
    strategies.find((s) => s.strategyId === strategyId) ?? strategies[0];

  /**
   * Switching strategy changes the legal qty range, so the currently-picked qty has to come with
   * it. Without this, picking 30 on a max-50 strategy and switching to a max-5 one leaves qty=30:
   * the <select> has no matching option (React warns and renders blank) and Confirm is disabled
   * with nothing on screen explaining why. Every live tenant has both a copytrade (max 50) and a
   * watchlist (max 5) strategy, so this is reachable in one click, not a corner case.
   */
  const selectStrategy = (nextId: string) => {
    setStrategyId(nextId);
    const next = strategies.find((s) => s.strategyId === nextId);
    if (!next) return;
    const current = Number.parseInt(qty, 10);
    const clamped = Number.isInteger(current)
      ? Math.min(Math.max(current, next.minContracts), next.maxContracts)
      : next.minContracts;
    setQty(String(clamped));
  };
  const minQty = selectedStrategy?.minContracts ?? 1;
  const maxQty = Math.max(minQty, selectedStrategy?.maxContracts ?? 1);
  const qtyChoices = Array.from({ length: maxQty - minQty + 1 }, (_, i) => minQty + i);

  const quantity = Number.parseInt(qty, 10);
  const qtyValid = Number.isInteger(quantity) && quantity >= minQty && quantity <= maxQty;

  /**
   * `inModal` says where a refusal is shown: a typed contract that does not quote stays in the
   * panel next to the box it was typed in; a pick made in the modal is answered in the modal.
   */
  const requestQuote = async (target: string, inModal: boolean) => {
    clearTimers();
    if (!inModal) {
      // A typed contract that is not the picked one ends that pick, so "Back" never returns to
      // details of a different contract.
      setPicked((p) => (p && compactOcc(p.row.occ) === compactOcc(target) ? p : null));
    }
    setStep({ kind: "quoting" });
    const r = await quoteAction(target);
    if (cancelled.current) return;
    if (!r.ok) {
      setStep({ kind: "failed", message: quoteFailureText(r), inModal });
      return;
    }
    // Mint the idempotency key when the confirm step OPENS, not on click, so a double-click is one
    // entry. Re-quoting mints a new one — that is a genuinely new decision.
    setStep({ kind: "confirm", quote: r.quote, idempotencyKey: crypto.randomUUID() });
    setModalOpen(true);
    scheduleRefresh(target, MAX_QUOTE_REFRESHES);
  };

  /**
   * Auto-refresh the quote just under the server's 30s staleness bound, bounded so an abandoned
   * confirm step stops polling the market. When the budget runs out the quote simply goes stale and
   * the server refuses the submit — which is the correct, fail-closed outcome.
   *
   * A refresh replaces the quote IN PLACE (no "quoting" flash, which would blank the modal) and
   * only while the confirm step is still showing: if the operator clicked Confirm while the refresh
   * was in flight, the step is now `submitting` and must not be swapped back to a confirm step with
   * a fresh idempotency key — that would offer a second real-money order.
   */
  const scheduleRefresh = (target: string, refreshesLeft: number) => {
    if (refreshesLeft <= 0) return;
    timers.current.push(
      setTimeout(async () => {
        const r = await quoteAction(target);
        if (cancelled.current) return;
        // stepRef, not a setStep updater, decides: React may run an updater lazily at the next
        // render, too late to tell whether to re-arm. The updater re-checks anyway.
        const decision = decideQuoteRefresh(stepRef.current.kind, r);
        if (decision.kind === "drop") return;
        const next: Step =
          decision.kind === "replace"
            ? { kind: "confirm", quote: decision.quote, idempotencyKey: crypto.randomUUID() }
            : { kind: "failed", message: quoteFailureText(r as QuoteFailure), inModal: true };
        setStep((prev) => (prev.kind === "confirm" ? next : prev));
        if (decision.kind === "replace") scheduleRefresh(target, refreshesLeft - 1);
      }, QUOTE_REFRESH_MS),
    );
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
        inModal: true,
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

  /** (Re)load today's watchlist when it is first needed, failed, or older than a minute. */
  const loadWatchlist = () => {
    if (
      watchlist.state === "loading" ||
      (watchlist.state === "ok" && Date.now() - watchlist.readAt < WATCHLIST_STALE_MS)
    ) {
      return;
    }
    setWatchlist((w) => (w.state === "ok" ? w : { state: "loading" }));
    void watchlistAction().then((r) => {
      if (cancelled.current) return;
      setWatchlist(r.ok ? { state: "ok", ...r, readAt: Date.now() } : { state: "failed" });
    });
  };
  const watchlistIdeas = watchlist.state === "ok" ? watchlist.ideas : [];
  const watchlistNote =
    watchlist.state === "ok"
      ? watchlist.posted
        ? null
        : "No watchlist has been posted today."
      : watchlist.state === "failed"
        ? "The watchlist is unavailable right now."
        : "Loading the watchlist…";

  const pickSuggestion = (s: Suggestion) => {
    if (s.kind === "recent") {
      setOcc(s.contract.occ);
      return;
    }
    setOcc(s.idea.ticker);
    setChainUnderlying(s.idea.ticker);
    setChainFocus({ right: s.idea.right, strike: s.idea.strike });
  };

  const pickFromChain = (p: PickedContract) => {
    clearTimers();
    setPicked(p);
    setOcc(p.row.occ);
    if (chainInModal) {
      // From a watchlist Buy: the chain row already showed bid/ask, so go straight to the fresh
      // quote, qty and Confirm. "Back" returns to the chain.
      void requestQuote(p.row.occ, true);
      return;
    }
    setStep({ kind: "detail" });
    setModalOpen(true);
  };

  /** Back from the confirm step (or a refusal) to the picked contract's details, or the chain. */
  const backToDetail = () => {
    clearTimers();
    setStep(chainInModal ? { kind: "chain" } : { kind: "detail" });
  };

  // An entry whose outcome is not known yet. Replacing the step now would stop its outcome poll, so
  // a watchlist Buy waits; once the outcome is final, Buy may replace it.
  const entryInFlight =
    step.kind === "submitting" ||
    (step.kind === "tracking" &&
      (step.status === null || step.status.state === "PENDING" || step.status.state === "SUBMITTED"));

  /** A watchlist row's Buy: open the chain in the modal, landed on the idea's contract. */
  const buyFromWatchlist = (idea: WatchlistIdea) => {
    if (entryInFlight) return;
    clearTimers();
    setOcc(idea.ticker);
    setChainUnderlying(idea.ticker);
    setChainFocus({ right: idea.right, strike: idea.strike });
    setChainInModal(true);
    setStep({ kind: "chain" });
    setModalOpen(true);
    loadWatchlist();
  };
  useRegisterBuyIdea(buyFromWatchlist, entryInFlight);

  const alreadyHeld =
    step.kind === "confirm" && heldOccs.includes(step.quote.occ.replace(/\s+/g, ""));

  // Recomputed on every keystroke: the hint has to follow hand-typing, not just a dropdown pick.
  const matchedSignal = matchingSignal(occ, recentContracts);

  // The chain is browsed by the leading ticker of whatever is typed, so both "NVDA" and a full
  // OCC open the same underlying.
  const typedUnderlying = occ.trim().toUpperCase().match(/^[A-Z]{1,6}/)?.[0] ?? null;
  const toggleChain = () => {
    setChainFocus(null);
    setChainUnderlying((open) => (open !== null ? null : typedUnderlying));
    loadWatchlist();
  };

  // The picked row is only "Back"-able while the quote in hand is for that same contract.
  const pickedCompact = picked ? compactOcc(picked.row.occ) : null;
  const canGoBack =
    pickedCompact !== null &&
    (step.kind === "failed" ||
      (step.kind === "confirm" && compactOcc(step.quote.occ) === pickedCompact));

  const inlineFailure = step.kind === "failed" && !step.inModal ? step.message : null;
  const trackingInPanel = step.kind === "tracking" && !modalOpen;

  return (
    <section>
      <div className="mb-2 flex items-baseline justify-between">
        <h2 className="text-sm font-semibold text-slate-200">Manual entry</h2>
        <span className="text-xs text-slate-500">
          Buys at market, capped by this strategy&apos;s slippage limit
        </span>
      </div>
      <div className="rounded border border-slate-800 bg-slate-900 px-3 py-3">
        {trackingInPanel ? (
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
        ) : (
          <div className="flex flex-wrap items-center gap-2">
            {/* Suggestions only: anything typed by hand is still accepted, exactly as before. */}
            <ContractSearch
              value={occ}
              onChange={setOcc}
              ideas={watchlistIdeas}
              watchlistNote={watchlistNote}
              recent={recentContracts}
              onOpen={loadWatchlist}
              onPick={pickSuggestion}
            />
            {strategies.length > 1 && (
              <select
                value={strategyId}
                onChange={(e) => selectStrategy(e.target.value)}
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
              aria-expanded={chainUnderlying !== null}
              disabled={chainUnderlying === null && typedUnderlying === null}
              onClick={toggleChain}
              className="rounded border border-sky-400/45 bg-sky-500/10 px-3 py-1 text-xs font-medium text-sky-300 hover:bg-sky-500/20 disabled:cursor-not-allowed disabled:border-slate-700 disabled:bg-transparent disabled:text-slate-600"
            >
              {chainUnderlying !== null ? "Hide chain" : "Browse chain"}
            </button>
            <button
              type="button"
              disabled={!occ.trim() || !strategyId || step.kind === "quoting"}
              onClick={() => void requestQuote(occ, false)}
              className="rounded border border-emerald-500/50 bg-emerald-600/10 px-3 py-1 text-xs font-medium text-emerald-300 hover:bg-emerald-600/20 disabled:cursor-not-allowed disabled:border-slate-700 disabled:bg-transparent disabled:text-slate-600"
            >
              {step.kind === "quoting" && !modalOpen ? "Quoting…" : "Buy"}
            </button>
            {/* basis-full puts this on its own line under the input row, so the signal price stays
                visible after a contract is picked or typed. */}
            {matchedSignal !== null && (
              <span className="basis-full text-xs text-slate-500">
                signalled {matchedSignal.on} at{" "}
                <span className="text-slate-300">
                  {matchedSignal.refPremium === null
                    ? "an unrecorded price"
                    : `$${matchedSignal.refPremium.toFixed(2)}`}
                </span>
                {matchedSignal.contracts !== null &&
                  ` · sized ${matchedSignal.contracts}`}
              </span>
            )}
            {inlineFailure !== null && (
              <span className="text-xs font-medium text-rose-300" role="alert">
                {inlineFailure}
              </span>
            )}
          </div>
        )}

        {/* Hidden while an entry's outcome is shown in the panel: picking a new contract would
            replace the step and drop the outcome's poll. "New entry" brings it back. */}
        {chainUnderlying !== null && !trackingInPanel && !chainInModal && (
          <OptionChain
            underlying={chainUnderlying}
            heldOccs={heldOccs.map(compactOcc)}
            signalledOccs={recentContracts.map((c) => compactOcc(c.occ))}
            selectedOcc={modalOpen && picked ? picked.row.occ : null}
            ideas={watchlistIdeas.filter((i) => i.ticker === chainUnderlying)}
            watchlistDate={
              watchlist.state === "ok" && watchlist.posted ? watchlist.etDate : null
            }
            focus={chainFocus}
            expirationsAction={chainExpirationsAction}
            chainAction={optionChainAction}
            onPick={pickFromChain}
          />
        )}
      </div>

      <Modal
        open={modalOpen}
        onClose={closeModal}
        title={
          step.kind === "chain" ? `Buy ${chainUnderlying} · option chain` : modalTitle(step, picked)
        }
        subtitle={
          step.kind === "chain"
            ? "Pick a contract — the watchlist one is highlighted"
            : modalSubtitle(step, picked)
        }
        wide={step.kind === "chain"}
      >
        {step.kind === "chain" && chainUnderlying !== null && (
          <div className="px-4 pb-4">
            <OptionChain
              underlying={chainUnderlying}
              heldOccs={heldOccs.map(compactOcc)}
              signalledOccs={recentContracts.map((c) => compactOcc(c.occ))}
              selectedOcc={null}
              ideas={watchlistIdeas.filter((i) => i.ticker === chainUnderlying)}
              watchlistDate={
                watchlist.state === "ok" && watchlist.posted ? watchlist.etDate : null
              }
              focus={chainFocus}
              expirationsAction={chainExpirationsAction}
              chainAction={optionChainAction}
              onPick={pickFromChain}
            />
          </div>
        )}
        {step.kind === "detail" && picked && (
          <ContractDetail
            picked={picked}
            held={heldOccs.map(compactOcc).includes(compactOcc(picked.row.occ))}
            onClose={reset}
            onBuy={() => void requestQuote(picked.row.occ, true)}
          />
        )}

        {step.kind === "quoting" && (
          <div className="px-4 py-4 text-xs text-slate-300" role="status" aria-live="polite">
            Getting a fresh quote…
          </div>
        )}

        {step.kind === "confirm" && (
          <>
            <div className="flex flex-col gap-3 px-4 py-4 text-xs">
              <div className="flex flex-wrap items-center gap-3 text-slate-300">
                <span>
                  bid <span className="text-slate-100">{fmt(step.quote.bid)}</span>
                </span>
                <span>
                  mid <span className="text-slate-100">{fmt(step.quote.mid)}</span>
                </span>
                <span>
                  ask <span className="font-medium text-slate-100">{fmt(step.quote.ask)}</span>
                </span>
                <span className="text-[11px] text-slate-500">refreshes every 20s</span>
              </div>
              <div className="flex flex-wrap items-center gap-3 text-slate-300">
                {strategies.length > 1 && (
                  <label className="flex items-center gap-1">
                    strategy
                    <select
                      value={strategyId}
                      onChange={(e) => selectStrategy(e.target.value)}
                      className="rounded border border-slate-700 bg-slate-950 px-2 py-1 text-xs text-slate-100"
                    >
                      {strategies.map((s) => (
                        <option key={s.strategyId} value={s.strategyId}>
                          {s.strategyId}
                          {s.enabled ? "" : " (disabled)"}
                        </option>
                      ))}
                    </select>
                  </label>
                )}
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
                <div className="text-amber-300">
                  You already hold this contract — this opens a SECOND position, it does not add
                  to the existing one.
                </div>
              )}
              {canGoBack && picked?.idea?.status === "crossed" && picked.idea.crossed_at && (
                <div className="text-slate-400">
                  Watchlist idea triggered at {etClock(picked.idea.crossed_at)} ET
                  {picked.idea.distance_pct !== null &&
                    `; ${picked.idea.ticker} is now ${signedPct(picked.idea.distance_pct)} from the level`}
                  .
                </div>
              )}
            </div>
            <ModalFooter>
              <button
                type="button"
                onClick={canGoBack ? backToDetail : reset}
                className="rounded border border-slate-600/60 px-2 py-1 text-xs text-slate-300 hover:bg-slate-700/30"
              >
                {canGoBack ? "← Back" : "Cancel"}
              </button>
              <button
                type="button"
                disabled={!qtyValid}
                onClick={() => void submit(step.quote, step.idempotencyKey)}
                className="rounded border border-emerald-500/60 bg-emerald-600/20 px-2 py-1 text-xs font-medium text-emerald-100 hover:bg-emerald-600/30 disabled:cursor-not-allowed disabled:border-slate-700 disabled:bg-transparent disabled:text-slate-600"
              >
                Confirm — BUY {qtyValid ? quantity : "?"} at market (limit ≥ {fmt(step.quote.ask)})
              </button>
            </ModalFooter>
          </>
        )}

        {step.kind === "submitting" && (
          <div
            className="px-4 py-4 text-xs font-medium text-slate-300"
            role="status"
            aria-live="polite"
          >
            Submitting…
          </div>
        )}

        {step.kind === "tracking" && (
          <>
            <div className="px-4 py-4 text-xs" role="status" aria-live="polite">
              <EntryOutcome status={step.status} />
            </div>
            <ModalFooter>
              <button
                type="button"
                onClick={reset}
                className="rounded border border-slate-600/60 px-2 py-1 text-xs text-slate-300 hover:bg-slate-700/30"
              >
                New entry
              </button>
            </ModalFooter>
          </>
        )}

        {step.kind === "failed" && step.inModal && (
          <>
            <div className="px-4 py-4 text-xs font-medium text-rose-300" role="alert">
              {step.message}
            </div>
            <ModalFooter>
              <button
                type="button"
                onClick={canGoBack ? backToDetail : reset}
                className="rounded border border-slate-600/60 px-2 py-1 text-xs text-slate-300 hover:bg-slate-700/30"
              >
                {canGoBack ? "← Back" : "Close"}
              </button>
            </ModalFooter>
          </>
        )}
      </Modal>
    </section>
  );
}

type QuoteFailure = Exclude<QuoteActionResult, { ok: true }>;

function quoteFailureText(r: QuoteFailure): string {
  const messages: Record<string, string> = {
    "invalid-occ": r.kind === "invalid-occ" ? r.detail : "",
    unavailable: "No quote available for that contract right now.",
    disabled: "Manual entry is not enabled.",
    error: "Could not read a quote.",
  };
  return messages[r.kind] || "Could not read a quote.";
}

function prettyOcc(occ: string): string {
  return occ.replace(/\s+/g, " ").trim();
}

function contractName(underlying: string, strike: number, right: "C" | "P", expiry: string) {
  const [y, m, d] = expiry.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1, d)).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
    timeZone: "UTC",
  });
  return `${underlying} $${strike} ${right === "C" ? "Call" : "Put"} · ${date}`;
}

function modalTitle(step: Step, picked: PickedContract | null): string {
  if (step.kind === "confirm") {
    const q = step.quote;
    return `Confirm buy · ${contractName(q.underlying, q.strike, q.right, q.expiry)}`;
  }
  if (picked) {
    return contractName(picked.underlying, picked.row.strike, picked.right, picked.expiration);
  }
  return "Manual entry";
}

function modalSubtitle(step: Step, picked: PickedContract | null): string | undefined {
  const occ = step.kind === "confirm" ? step.quote.occ : picked?.row.occ;
  if (!occ) return undefined;
  const expiry = step.kind === "confirm" ? step.quote.expiry : picked?.expiration;
  return expiry ? `${prettyOcc(occ)} · ${daysToExpiry(expiry)} DTE` : prettyOcc(occ);
}

function ModalFooter({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-end gap-2 border-t border-slate-800 px-4 py-3">
      {children}
    </div>
  );
}

/** The picked chain row, before any quote: what Robinhood shows on tapping a strike. */
function ContractDetail({
  picked,
  held,
  onClose,
  onBuy,
}: {
  picked: PickedContract;
  held: boolean;
  onClose: () => void;
  onBuy: () => void;
}) {
  const { row, right } = picked;
  const be = row.ask === null ? null : breakeven(row.strike, row.ask, right);
  const spreadPct =
    row.ask !== null && row.bid !== null && row.mid ? ((row.ask - row.bid) / row.mid) * 100 : null;
  const stats: [string, string][] = [
    ["Bid", fmtPrice(row.bid)],
    ["Mid", fmtPrice(row.mid)],
    ["Ask", fmtPrice(row.ask)],
    ["Spread", spreadPct === null ? "—" : `${spreadPct.toFixed(1)}%`],
    ["Breakeven", fmtPrice(be)],
    ["Delta", row.delta === null ? "—" : row.delta.toFixed(2)],
    ["Impl. vol", row.iv === null ? "—" : `${(row.iv * 100).toFixed(1)}%`],
    ["Volume", row.volume === null ? "—" : row.volume.toLocaleString("en-US")],
    [`${picked.underlying} last`, fmtPrice(picked.spot)],
  ];
  return (
    <>
      <div className="flex flex-col gap-3 px-4 py-4 text-xs">
        {picked.idea && <WatchlistBox idea={picked.idea} />}
        <div className="flex items-baseline gap-2">
          <span className="text-xl font-semibold tabular-nums text-emerald-300">
            {fmtPrice(row.ask)}
          </span>
          <span className="text-slate-400">
            {row.ask === null ? "no offer right now" : `ask · ≈ ${fmtPrice(row.ask * 100)} per contract`}
          </span>
        </div>
        <dl className="grid grid-cols-3 gap-x-4 gap-y-2">
          {stats.map(([k, v]) => (
            <div key={k} className="flex flex-col">
              <dt className="text-[10px] uppercase tracking-wide text-slate-500">{k}</dt>
              <dd className="tabular-nums text-slate-100">{v}</dd>
            </div>
          ))}
        </dl>
        {held && (
          <div className="text-amber-300">
            You already hold this contract. Buying opens a second position.
          </div>
        )}
        {spreadPct !== null && spreadPct > 15 && (
          <div className="text-amber-300">
            Wide spread: a market buy may fill well above the mid.
          </div>
        )}
        <div className="text-[11px] text-slate-500">
          The next step gets a fresh quote. Nothing is ordered yet.
        </div>
      </div>
      <ModalFooter>
        <button
          type="button"
          onClick={onClose}
          className="rounded border border-slate-600/60 px-2 py-1 text-xs text-slate-300 hover:bg-slate-700/30"
        >
          Close
        </button>
        <button
          type="button"
          onClick={onBuy}
          className="rounded border border-emerald-500/50 bg-emerald-600/10 px-3 py-1 text-xs font-medium text-emerald-300 hover:bg-emerald-600/20"
        >
          Buy — get quote
        </button>
      </ModalFooter>
    </>
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

function signedPct(n: number): string {
  return `${n >= 0 ? "+" : ""}${n.toFixed(2)}%`;
}

/**
 * The watchlist idea this contract is: the plan, whether and when the stock crossed, and where it is
 * now. Context, not a verdict — the box says so, because a triggered idea is not a reason to buy on
 * its own.
 */
function WatchlistBox({ idea }: { idea: WatchlistIdea }) {
  const crossed = idea.status === "crossed" && idea.crossed_at !== null;
  const where =
    idea.last === null || idea.distance_pct === null
      ? null
      : `${idea.ticker} last ${fmtPrice(idea.last)}, ${signedPct(idea.distance_pct)} from the level`;
  const facts = crossed
    ? `Crossed at ${etClock(idea.crossed_at as string)} ET.${where ? ` ${where}.` : ""}`
    : idea.status === "unknown"
      ? `Today's prices for ${idea.ticker} could not be read, so whether it crossed is unknown.`
      : `${where ? `${where}. ` : ""}Not crossed today.`;
  return (
    <div
      className={`flex flex-col gap-1.5 rounded-md border px-3 py-2.5 ${
        crossed ? "border-amber-400/60 bg-amber-500/10" : "border-violet-400/50 bg-violet-500/10"
      }`}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="text-[10px] uppercase tracking-wider text-violet-300">
          Today&apos;s watchlist
        </span>
        <IdeaStatusPill idea={idea} />
      </div>
      <div className="text-slate-100">
        Buy the {idea.strike}
        {idea.right} when {idea.ticker} breaks {idea.direction === "ABOVE" ? "above" : "below"}{" "}
        {fmtPrice(idea.level)}.
      </div>
      <div className="tabular-nums text-slate-400">{facts}</div>
      <div className="text-[11px] text-slate-500">
        From the author&apos;s daily list. Information only, not a recommendation. Nothing is bought
        automatically.
      </div>
    </div>
  );
}
