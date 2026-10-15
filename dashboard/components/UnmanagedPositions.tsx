"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import type { AdoptResult, UnmanagedPosition } from "@/lib/bff";
import { ContractLink, occCompact } from "./ContractLink";
import { Modal } from "./Modal";
import { Pnl, fmtCurrency } from "./Pnl";
import { usePanelOpen } from "./LiveRefresh";

export type AdoptAction = (occ: string, strategyId: string) => Promise<AdoptResult>;

// Broker lots nothing manages — bought directly at the broker, typically. Without an owner no stop,
// trail, expiry close or operator button reaches them, and the account cap does not count them, so
// the section says so plainly. The Adopt button (its own dark flag) hands one to a PositionWorkflow.
export function UnmanagedPositions({
  positions,
  strategyIds,
  adoptAction,
}: {
  positions: UnmanagedPosition[];
  strategyIds: string[];
  // Null while the dashboard flag is off, or when the strategies could not be read: the list still
  // renders (it is information), just without the button.
  adoptAction: AdoptAction | null;
}) {
  const [picked, setPicked] = useState<UnmanagedPosition | null>(null);
  return (
    <section className="rounded-lg border border-amber-700/60 bg-amber-950/20 p-4">
      <h2 className="text-sm font-semibold text-amber-200">
        Not managed by the system ({positions.length})
      </h2>
      <p className="mt-1 text-xs text-slate-400">
        Your broker holds these, but nothing here manages them: no stop, trail, expiry close or
        Holdings button applies, and they do not count toward your daily-loss cap. Usually they were
        bought directly at the broker.
      </p>
      <ul className="mt-3 divide-y divide-slate-800">
        {positions.map((p) => (
          <li
            key={p.occ}
            className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 py-2 text-sm"
          >
            <span className="flex min-w-0 items-baseline gap-3">
              <ContractLink occ={p.occ} compact stack={false} />
              <span className="text-slate-300">×{p.qty}</span>
            </span>
            <span className="flex items-center gap-4">
              <span className="text-xs text-slate-400">
                Mark <span className="text-slate-200">{fmtCurrency(p.current_price)}</span>
              </span>
              <span className="text-xs text-slate-400">
                P&amp;L <Pnl value={p.unrealized_pl} />
              </span>
              {adoptAction && (
                <button
                  type="button"
                  onClick={() => setPicked(p)}
                  className="rounded border border-sky-700 px-2.5 py-1 text-xs font-medium text-sky-300 hover:bg-sky-950"
                >
                  Adopt
                </button>
              )}
            </span>
          </li>
        ))}
      </ul>
      {adoptAction && picked && (
        <AdoptDialog
          position={picked}
          strategyIds={strategyIds}
          action={adoptAction}
          onClose={() => setPicked(null)}
        />
      )}
    </section>
  );
}

const REFUSALS: Record<string, string> = {
  condor_leg:
    "It is one leg of a condor. Managing one leg alone could leave the other side naked.",
  open_sell_order:
    "A sell order for this contract is already working at your broker. Cancel it there first, or let it fill.",
  expired: "The contract is past its last trading moment.",
};

function outcomeText(
  r: AdoptResult,
  qty: number,
): { tone: "ok" | "bad" | "info"; text: string } {
  switch (r.outcome) {
    case "ADOPTED":
      return {
        tone: "ok",
        text: `Adopted ${r.qty ?? qty} contracts. They move to Holdings when you close this.`,
      };
    case "ALREADY_OWNED":
      return { tone: "info", text: "Something already manages this contract. Nothing changed." };
    case "PENDING":
      return {
        tone: "info",
        text: "Still working. Refresh in a few seconds to see whether it was adopted.",
      };
    case "REFUSED_NOT_HELD":
      return { tone: "bad", text: "Your broker no longer holds this contract. Nothing changed." };
    case "REFUSED_NO_ANCHOR":
      return {
        tone: "bad",
        text: "Your broker reports no average cost for this lot, so there is nothing to base stops and P&L on. Nothing changed.",
      };
    case "REFUSED_UNSAFE":
      return {
        tone: "bad",
        text: `Not adopted. ${REFUSALS[r.reason ?? ""] ?? "Taking it over could misfire."}`,
      };
    case "disabled":
      return { tone: "bad", text: "Adopting is turned off on the server." };
    default:
      return { tone: "bad", text: "Could not reach the server. Nothing was confirmed; try again." };
  }
}

function AdoptDialog({
  position,
  strategyIds,
  action,
  onClose,
}: {
  position: UnmanagedPosition;
  strategyIds: string[];
  action: AdoptAction;
  onClose: () => void;
}) {
  const [strategyId, setStrategyId] = useState(strategyIds[0] ?? "");
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<AdoptResult | null>(null);
  const router = useRouter();
  // Pauses /live's 15s auto-refresh while open: a refresh that drops the row would unmount this
  // dialog before the operator reads the outcome. Closing refreshes once, so the row moves now.
  usePanelOpen(true);
  const close = () => {
    if (submitting) return;
    if (result) router.refresh();
    onClose();
  };

  const submit = async () => {
    if (submitting || !strategyId) return;
    setSubmitting(true);
    try {
      setResult(await action(position.occ, strategyId));
    } catch {
      setResult({ outcome: "error" });
    } finally {
      setSubmitting(false);
    }
  };
  const done = result !== null && result.outcome !== "error";
  const msg = result ? outcomeText(result, position.qty) : null;

  return (
    <Modal
      open
      // Never close mid-request: the answer is the only place the operator learns the outcome.
      onClose={close}
      title={`Adopt ${occCompact(position.occ)} × ${position.qty}`}
      subtitle="Hand this broker lot to the system to manage"
    >
      <div className="flex flex-col gap-3 px-4 py-4 text-sm text-slate-300">
        <ul className="list-disc space-y-1.5 pl-5 text-xs">
          <li>
            The system starts managing all {position.qty} contracts at your broker&apos;s average
            cost. No order is placed now.
          </li>
          <li>Force exit, Trim and the trailing stop become available under Holdings.</li>
          <li>
            If the author posts a sell for this same contract, the system follows it for these
            contracts too.
          </li>
          <li>On its expiry day the system sells it before the close.</li>
          <li className="text-amber-200">
            It counts toward your daily-loss cap. If the cap trips, the system sells it along with
            your other positions.
          </li>
        </ul>
        {strategyIds.length > 1 && (
          <label className="flex items-center gap-2 text-xs text-slate-400">
            Manage under
            <select
              value={strategyId}
              onChange={(e) => setStrategyId(e.target.value)}
              disabled={submitting || done}
              className="rounded border border-slate-700 bg-slate-950 px-2 py-1 text-slate-200"
            >
              {strategyIds.map((id) => (
                <option key={id} value={id}>
                  {id}
                </option>
              ))}
            </select>
          </label>
        )}
        {msg && (
          <p
            role="status"
            className={
              msg.tone === "ok"
                ? "text-emerald-300"
                : msg.tone === "bad"
                  ? "text-rose-300"
                  : "text-slate-300"
            }
          >
            {msg.text}
          </p>
        )}
        <div className="flex justify-end gap-2 pt-1">
          <button
            type="button"
            onClick={close}
            disabled={submitting}
            className="rounded px-3 py-1.5 text-xs text-slate-300 hover:bg-slate-800 disabled:opacity-50"
          >
            {done ? "Close" : "Cancel"}
          </button>
          {!done && (
            <button
              type="button"
              onClick={submit}
              disabled={submitting || !strategyId}
              className="rounded bg-sky-700 px-3 py-1.5 text-xs font-medium text-white hover:bg-sky-600 disabled:opacity-50"
            >
              {submitting ? "Adopting…" : `Adopt ${position.qty} contracts`}
            </button>
          )}
        </div>
      </div>
    </Modal>
  );
}
