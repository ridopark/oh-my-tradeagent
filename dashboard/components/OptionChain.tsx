import { useEffect, useRef, useState } from "react";
import {
  ideaExpiration,
  ideaLabel,
  ideaStatusText,
  type WatchlistIdea,
} from "@/lib/watchlist";

// The /live chain picker: one underlying's listed contracts, Robinhood-style — an expiration strip,
// Calls/Puts, and a strike ladder split by the spot line. It only PICKS a contract (onPick); the
// manual-entry panel re-quotes the pick before anything can be ordered.

export interface ChainRow {
  occ: string;
  strike: number;
  bid: number | null;
  mid: number | null;
  ask: number | null;
  iv: number | null;
  delta: number | null;
  volume: number | null;
}

type ReadFailure = { ok: false; kind: "invalid" | "unavailable" | "disabled" | "error" };

export type ExpirationsResult =
  | { ok: true; underlying: string; spot: number | null; expirations: string[] }
  | ReadFailure;

export type ChainResult = { ok: true; spot: number | null; contracts: ChainRow[] } | ReadFailure;

export interface PickedContract {
  row: ChainRow;
  underlying: string;
  expiration: string;
  right: "C" | "P";
  spot: number | null;
  /** The watchlist idea this exact contract is, if any. */
  idea: WatchlistIdea | null;
}

/** Where to land when the chain opens from a watchlist idea. */
export interface ChainFocus {
  right: "C" | "P";
  strike: number;
}

const FAILURE_TEXT: Record<ReadFailure["kind"], string> = {
  invalid: "That is not a ticker with listed options.",
  unavailable: "The option chain is unavailable right now.",
  disabled: "Manual entry is not enabled.",
  error: "Could not load the option chain.",
};

export function fmtPrice(n: number | null | undefined): string {
  return n == null ? "—" : `$${Number(n).toFixed(2)}`;
}

/** Breakeven at expiry for a buyer paying the ask. */
export function breakeven(strike: number, ask: number, right: "C" | "P"): number {
  return right === "C" ? strike + ask : strike - ask;
}

export function compactOcc(occ: string): string {
  return occ.replace(/\s+/g, "").toUpperCase();
}

/** Today's date in New York, as YYYY-MM-DD — the date expirations are counted from. */
function todayEt(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "America/New_York" }).format(new Date());
}

export function daysToExpiry(expiration: string): number {
  return Math.round((Date.parse(expiration) - Date.parse(todayEt())) / 86_400_000);
}

function chipLabel(expiration: string): string {
  const [y, m, d] = expiration.split("-").map(Number);
  const label = new Date(Date.UTC(y, m - 1, d)).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    timeZone: "UTC",
  });
  return y === Number(todayEt().slice(0, 4)) ? label : `${label} ’${String(y).slice(2)}`;
}

export function OptionChain({
  underlying,
  heldOccs,
  signalledOccs,
  selectedOcc,
  ideas,
  watchlistDate,
  focus,
  expirationsAction,
  chainAction,
  onPick,
}: {
  underlying: string;
  /** COMPACT OCCs. */
  heldOccs: string[];
  /** COMPACT OCCs. */
  signalledOccs: string[];
  selectedOcc: string | null;
  /** Today's watchlist ideas for THIS underlying. */
  ideas: WatchlistIdea[];
  /** The watchlist's ET date, which fixes the expiry its ideas trade; null when none today. */
  watchlistDate: string | null;
  /** Land on this idea's expiry, side and strike instead of the nearest expiry at the money. */
  focus: ChainFocus | null;
  expirationsAction: (underlying: string) => Promise<ExpirationsResult>;
  chainAction: (underlying: string, expiration: string, right: "C" | "P") => Promise<ChainResult>;
  onPick: (picked: PickedContract) => void;
}) {
  const [expirations, setExpirations] = useState<
    { state: "loading" } | { state: "failed"; message: string } | { state: "ok"; dates: string[] }
  >({ state: "loading" });
  const [expiration, setExpiration] = useState<string | null>(null);
  const [right, setRight] = useState<"C" | "P">("C");
  const [chain, setChain] = useState<
    { state: "loading" } | { state: "failed"; message: string } | ({ state: "ok" } & {
      spot: number | null;
      contracts: ChainRow[];
    })
  >({ state: "loading" });
  const ladder = useRef<HTMLDivElement>(null);
  const spotRow = useRef<HTMLTableRowElement>(null);
  const focusRow = useRef<HTMLTableRowElement>(null);
  // The strike to scroll to: set by a watchlist landing, cleared by any manual expiry/side change.
  const [focusStrike, setFocusStrike] = useState<number | null>(focus?.strike ?? null);

  const dates = expirations.state === "ok" ? expirations.dates : [];
  const wlExpiry =
    watchlistDate !== null && ideas.length > 0 ? ideaExpiration(watchlistDate, dates) : null;

  const landOn = (f: ChainFocus, listed: string[]) => {
    const exp = watchlistDate === null ? null : ideaExpiration(watchlistDate, listed);
    setRight(f.right);
    setExpiration(exp ?? listed[0]);
    setFocusStrike(exp === null ? null : f.strike);
  };

  useEffect(() => {
    let live = true;
    setExpirations({ state: "loading" });
    setExpiration(null);
    void expirationsAction(underlying).then((r) => {
      if (!live) return;
      if (!r.ok) {
        setExpirations({ state: "failed", message: FAILURE_TEXT[r.kind] });
      } else if (r.expirations.length === 0) {
        setExpirations({ state: "failed", message: `No listed options found for ${underlying}.` });
      } else {
        setExpirations({ state: "ok", dates: r.expirations });
        if (focus) {
          landOn(focus, r.expirations);
        } else {
          setExpiration(r.expirations[0]);
        }
      }
    });
    return () => {
      live = false;
    };
    // focus is read once per underlying here; a new focus on the SAME underlying is the next effect.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [underlying, expirationsAction]);

  // A new watchlist pick on the underlying already open re-lands without reloading expirations.
  useEffect(() => {
    if (focus && dates.length > 0) {
      landOn(focus, dates);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focus]);

  useEffect(() => {
    if (expiration === null) return;
    let live = true;
    setChain({ state: "loading" });
    void chainAction(underlying, expiration, right).then((r) => {
      if (!live) return;
      setChain(
        r.ok
          ? { state: "ok", spot: r.spot, contracts: r.contracts }
          : { state: "failed", message: FAILURE_TEXT[r.kind] },
      );
    });
    return () => {
      live = false;
    };
  }, [underlying, expiration, right, chainAction]);

  // Open the ladder on the watchlist contract when landing on one, else at the money, the way a
  // broker app does, rather than at the deepest strike.
  useEffect(() => {
    const target = focusRow.current ?? spotRow.current;
    if (chain.state === "ok" && ladder.current && target) {
      ladder.current.scrollTop = target.offsetTop - ladder.current.clientHeight / 2;
    }
  }, [chain]);

  /** The idea that IS this row's contract: same side and strike, on the watchlist's expiry. */
  const ideaFor = (row: ChainRow): WatchlistIdea | null =>
    expiration !== null && expiration === wlExpiry
      ? (ideas.find((i) => i.right === right && i.strike === row.strike) ?? null)
      : null;

  const spot = chain.state === "ok" ? chain.spot : null;
  const held = new Set(heldOccs);
  const signalled = new Set(signalledOccs);

  return (
    <div className="mt-3 flex flex-col gap-3 border-t border-slate-800 pt-3">
      <div className="flex flex-wrap items-baseline gap-3">
        <span className="text-lg font-semibold text-slate-100">{underlying}</span>
        <span className="text-lg tabular-nums text-slate-100">{fmtPrice(spot)}</span>
      </div>

      {watchlistDate !== null && (
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="mr-0.5 text-[10px] uppercase tracking-wider text-violet-300">
            Watchlist
          </span>
          {ideas.length === 0 ? (
            <span className="text-[11px] text-slate-500">
              {underlying} is not on today&apos;s watchlist.
            </span>
          ) : (
            ideas.map((i) => (
              <button
                key={`${i.right}${i.strike}`}
                type="button"
                onClick={() => dates.length > 0 && landOn(i, dates)}
                className={`inline-flex items-center gap-2 rounded-full border py-0.5 pl-2.5 pr-1 text-xs tabular-nums text-slate-200 ${
                  i.status === "crossed"
                    ? "border-amber-400/60 bg-amber-500/10"
                    : "border-violet-400/50 bg-violet-500/10"
                }`}
              >
                {ideaLabel(i)}
                <IdeaStatusPill idea={i} />
              </button>
            ))
          )}
        </div>
      )}

      {expirations.state === "loading" && (
        <span className="text-xs text-slate-400">Loading expirations…</span>
      )}
      {expirations.state === "failed" && (
        <span className="text-xs font-medium text-rose-300" role="alert">
          {expirations.message}
        </span>
      )}

      {expirations.state === "ok" && (
        <>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="inline-flex overflow-hidden rounded border border-slate-700" role="group">
              {(["C", "P"] as const).map((r) => (
                <button
                  key={r}
                  type="button"
                  aria-pressed={right === r}
                  onClick={() => {
                    setRight(r);
                    setFocusStrike(null);
                  }}
                  className={`px-4 py-1 text-xs ${
                    right === r ? "bg-slate-700 text-slate-100" : "text-slate-400 hover:bg-slate-800"
                  }`}
                >
                  {r === "C" ? "Calls" : "Puts"}
                </button>
              ))}
            </div>
            <div className="flex flex-wrap gap-3 text-[11px] text-slate-500">
              {ideas.length > 0 && (
                <>
                  <span>
                    <span className="mr-1 inline-block h-2.5 w-2.5 rounded-sm bg-amber-400 align-[-1px]" />
                    Watchlist · triggered
                  </span>
                  <span>
                    <span className="mr-1 inline-block h-2.5 w-2.5 rounded-sm bg-violet-300 align-[-1px]" />
                    Watchlist · waiting
                  </span>
                </>
              )}
              <span>
                <span className="mr-1 inline-block h-2.5 w-2.5 rounded-sm bg-emerald-500/25 align-[-1px]" />
                In the money
              </span>
              <span>
                <Tag tone="sky">SIG</Tag> signalled recently
              </span>
              <span>
                <Tag tone="amber">HELD</Tag> you hold it
              </span>
            </div>
          </div>

          <div className="flex gap-1.5 overflow-x-auto pb-1" role="group" aria-label="Expiration">
            {expirations.dates.map((d) => (
              <button
                key={d}
                type="button"
                aria-pressed={expiration === d}
                onClick={() => {
                  setExpiration(d);
                  setFocusStrike(null);
                }}
                className={`relative flex-none rounded-full border px-3 py-1 text-xs tabular-nums ${
                  expiration === d
                    ? "border-emerald-500/50 bg-emerald-600/10 text-emerald-300"
                    : "border-slate-700 text-slate-400 hover:bg-slate-800"
                }`}
              >
                {chipLabel(d)}
                <span className="ml-1 text-slate-500">{daysToExpiry(d)}d</span>
                {d === wlExpiry && (
                  <span
                    title="Watchlist expiry"
                    className="absolute -right-0.5 -top-0.5 h-2 w-2 rounded-full border-2 border-slate-900 bg-violet-300"
                  />
                )}
              </button>
            ))}
          </div>

          {chain.state === "loading" && (
            <span className="text-xs text-slate-400">Loading contracts…</span>
          )}
          {chain.state === "failed" && (
            <span className="text-xs font-medium text-rose-300" role="alert">
              {chain.message}
            </span>
          )}
          {chain.state === "ok" && chain.contracts.length === 0 && (
            <span className="text-xs text-slate-400">No contracts listed for this expiration.</span>
          )}
          {chain.state === "ok" && chain.contracts.length > 0 && expiration !== null && (
            <div
              ref={ladder}
              className="relative max-h-[420px] overflow-auto rounded border border-slate-800"
            >
              <table className="w-full border-collapse text-xs tabular-nums">
                <thead className="sticky top-0 z-10 bg-slate-900 text-[11px] uppercase tracking-wide text-slate-500">
                  <tr className="border-b border-slate-800">
                    <th className="px-2 py-1.5 text-left font-medium sm:px-3">Strike</th>
                    <th className="px-2 py-1.5 text-right font-medium sm:px-3">To b/e</th>
                    <th className="hidden px-3 py-1.5 text-right font-medium sm:table-cell">Δ</th>
                    <th className="hidden px-3 py-1.5 text-right font-medium sm:table-cell">IV</th>
                    <th className="hidden px-3 py-1.5 text-right font-medium sm:table-cell">Vol</th>
                    <th className="hidden px-3 py-1.5 text-right font-medium sm:table-cell">Bid</th>
                    <th className="px-2 py-1.5 text-right font-medium sm:px-3">Ask</th>
                  </tr>
                </thead>
                <tbody>
                  {ladderRows(chain.contracts, spot).map((item) =>
                    item.kind === "spot" ? (
                      <tr key="spot" ref={spotRow}>
                        <td colSpan={7} className="px-3 py-0.5">
                          <div className="flex items-center gap-2 text-[11px] text-sky-300">
                            <span className="flex-1 border-t border-dashed border-sky-300/50" />
                            {underlying} {fmtPrice(spot)}
                            <span className="flex-1 border-t border-dashed border-sky-300/50" />
                          </div>
                        </td>
                      </tr>
                    ) : (
                      <ChainLadderRow
                        key={item.row.occ}
                        rowRef={item.row.strike === focusStrike ? focusRow : undefined}
                        row={item.row}
                        right={right}
                        spot={spot}
                        idea={ideaFor(item.row)}
                        selected={selectedOcc === item.row.occ}
                        held={held.has(item.row.occ)}
                        signalled={signalled.has(item.row.occ)}
                        onPick={() =>
                          onPick({
                            row: item.row,
                            underlying,
                            expiration,
                            right,
                            spot,
                            idea: ideaFor(item.row),
                          })
                        }
                      />
                    ),
                  )}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </div>
  );
}

/** The strike ladder with the spot divider placed above the first strike above spot. */
function ladderRows(
  contracts: ChainRow[],
  spot: number | null,
): ({ kind: "spot" } | { kind: "row"; row: ChainRow })[] {
  const out: ({ kind: "spot" } | { kind: "row"; row: ChainRow })[] = [];
  let placed = spot === null;
  for (const row of contracts) {
    if (!placed && row.strike > (spot as number)) {
      out.push({ kind: "spot" });
      placed = true;
    }
    out.push({ kind: "row", row });
  }
  if (!placed) out.push({ kind: "spot" });
  return out;
}

function ChainLadderRow({
  rowRef,
  row,
  right,
  spot,
  idea,
  selected,
  held,
  signalled,
  onPick,
}: {
  rowRef?: React.Ref<HTMLTableRowElement>;
  row: ChainRow;
  right: "C" | "P";
  spot: number | null;
  idea: WatchlistIdea | null;
  selected: boolean;
  held: boolean;
  signalled: boolean;
  onPick: () => void;
}) {
  const itm = spot !== null && (right === "C" ? row.strike < spot : row.strike > spot);
  const be = row.ask === null ? null : breakeven(row.strike, row.ask, right);
  const toBe = be === null || spot === null ? null : ((be - spot) / spot) * 100;
  const triggered = idea?.status === "crossed";
  // A watchlist contract outranks the ITM tint, and a triggered one outranks a waiting one; the
  // inset bar is the colour-independent cue next to the tag text.
  const bg = selected
    ? "bg-sky-500/10"
    : idea
      ? triggered
        ? "bg-amber-500/[.13] shadow-[inset_3px_0_0_theme(colors.amber.400)]"
        : "bg-violet-500/10 shadow-[inset_3px_0_0_theme(colors.violet.300)]"
      : itm
        ? "bg-emerald-500/5"
        : "";
  return (
    <tr
      ref={rowRef}
      tabIndex={0}
      onClick={onPick}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onPick();
        }
      }}
      className={`cursor-pointer border-b border-slate-800 hover:bg-slate-700/25 focus-visible:outline focus-visible:outline-2 focus-visible:outline-sky-400 ${bg}`}
    >
      <td className="whitespace-nowrap px-2 py-1.5 text-left sm:px-3">
        <span className="font-semibold text-slate-100">
          ${row.strike} {right === "C" ? "Call" : "Put"}
        </span>
        {idea && <Tag tone={triggered ? "amber" : "violet"}>{triggered ? "WL · TRIGGERED" : "WL"}</Tag>}
        {signalled && <Tag tone="sky">SIG</Tag>}
        {held && <Tag tone="amber">HELD</Tag>}
        <span className="block text-[11px] text-slate-500">
          {idea && `${idea.direction === "ABOVE" ? "above" : "below"} ${fmtPrice(idea.level)} · `}
          breakeven {fmtPrice(be)}
        </span>
      </td>
      <td className="px-2 py-1.5 text-right text-slate-400 sm:px-3">
        {toBe === null ? "—" : `${toBe >= 0 ? "+" : ""}${toBe.toFixed(2)}%`}
      </td>
      <td className="hidden px-3 py-1.5 text-right text-slate-400 sm:table-cell">
        {row.delta === null ? "—" : row.delta.toFixed(2)}
      </td>
      <td className="hidden px-3 py-1.5 text-right text-slate-400 sm:table-cell">
        {row.iv === null ? "—" : `${(row.iv * 100).toFixed(1)}%`}
      </td>
      <td className="hidden px-3 py-1.5 text-right text-slate-500 sm:table-cell">
        {row.volume === null ? "—" : row.volume.toLocaleString("en-US")}
      </td>
      <td className="hidden px-3 py-1.5 text-right text-slate-400 sm:table-cell">
        {fmtPrice(row.bid)}
      </td>
      <td className="px-2 py-1.5 text-right sm:px-3">
        <span className="inline-block min-w-[4.5rem] rounded border border-emerald-500/50 bg-emerald-600/10 px-2 py-0.5 text-right font-semibold text-emerald-300">
          {fmtPrice(row.ask)}
        </span>
      </td>
    </tr>
  );
}

/** The idea's status as a pill: amber "TRIGGERED 10:42", sky when within 1%, grey otherwise. */
export function IdeaStatusPill({ idea }: { idea: WatchlistIdea }) {
  const tone =
    idea.status === "crossed"
      ? "border-amber-400/60 bg-amber-500/10 text-amber-300"
      : idea.distance_pct !== null && Math.abs(idea.distance_pct) < 1
        ? "border-sky-300/45 text-sky-300"
        : "border-slate-600 text-slate-400";
  return (
    <span
      className={`whitespace-nowrap rounded-full border px-1.5 text-[10px] font-semibold tabular-nums ${tone}`}
    >
      {ideaStatusText(idea)}
    </span>
  );
}

function Tag({ tone, children }: { tone: "sky" | "amber" | "violet"; children: string }) {
  const cls =
    tone === "sky"
      ? "border-sky-300/40 text-sky-300"
      : tone === "violet"
        ? "border-violet-300/50 text-violet-300"
        : "border-amber-300/40 text-amber-300";
  return (
    <span className={`ml-1.5 rounded-sm border px-1 text-[10px] font-medium ${cls}`}>
      {children}
    </span>
  );
}
