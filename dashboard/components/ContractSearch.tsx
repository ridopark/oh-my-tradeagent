import { useId, useState } from "react";
import { ideaLabel, type WatchlistIdea } from "@/lib/watchlist";
import { IdeaStatusPill } from "./OptionChain";
import type { RecentContract } from "./ManualEntryPanel";

// The manual-entry contract box: free typing exactly as before (a full OCC, or a ticker to browse),
// plus a dropdown of today's watchlist ideas and recently signalled contracts. It replaces the old
// <datalist>, which could not show a status pill, did not follow the dark theme, and would have
// opened on top of a second dropdown.

export type Suggestion =
  | { kind: "idea"; idea: WatchlistIdea }
  | { kind: "recent"; contract: RecentContract };

/** "$2.09 · 27x · 09-28" — a recent signal's price, size and date. */
function recentLabel(c: RecentContract): string {
  const parts: string[] = [];
  if (c.refPremium !== null) parts.push(`$${c.refPremium.toFixed(2)}`);
  if (c.contracts !== null) parts.push(`${c.contracts}x`);
  parts.push(c.on);
  return parts.join(" · ");
}

const MAX_RECENT = 8;

export function ContractSearch({
  value,
  onChange,
  ideas,
  watchlistNote,
  recent,
  onOpen,
  onPick,
}: {
  value: string;
  onChange: (value: string) => void;
  /** Today's ideas; empty while loading, when none were posted, or when the read failed. */
  ideas: WatchlistIdea[];
  /** Shown in place of ideas: "Loading…", "No watchlist posted today", "Watchlist unavailable". */
  watchlistNote: string | null;
  recent: RecentContract[];
  /** Fired when the dropdown opens, so the caller can (re)load the watchlist. */
  onOpen: () => void;
  onPick: (s: Suggestion) => void;
}) {
  const listId = useId();
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);

  const q = value.replace(/\s+/g, "").toUpperCase();
  const qTicker = q.match(/^[A-Z]*/)?.[0] ?? "";
  const shownIdeas = ideas.filter((i) => i.ticker.startsWith(qTicker));
  const shownRecent = recent
    .filter((c) => c.occ.replace(/\s+/g, "").toUpperCase().startsWith(q))
    .slice(0, MAX_RECENT);
  const options: Suggestion[] = [
    ...shownIdeas.map((idea) => ({ kind: "idea", idea }) as const),
    ...shownRecent.map((contract) => ({ kind: "recent", contract }) as const),
  ];

  const show = () => {
    if (!open) onOpen();
    setOpen(true);
  };
  const choose = (s: Suggestion) => {
    setOpen(false);
    setActive(-1);
    onPick(s);
  };
  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      show();
      setActive((a) => Math.min(a + 1, options.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((a) => Math.max(a - 1, -1));
    } else if (e.key === "Enter" && open && active >= 0 && options[active]) {
      e.preventDefault();
      choose(options[active]);
    } else if (e.key === "Escape") {
      setOpen(false);
      setActive(-1);
    }
  };
  const optionId = (i: number) => `${listId}-${i}`;

  return (
    <div className="relative">
      <input
        type="text"
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={open && active >= 0 ? optionId(active) : undefined}
        value={value}
        onChange={(e) => {
          onChange(e.target.value);
          setActive(-1);
          show();
        }}
        onFocus={show}
        onClick={show}
        onBlur={() => setOpen(false)}
        onKeyDown={onKeyDown}
        placeholder="NVDA 260821C00225000 or NVDA"
        spellCheck={false}
        autoComplete="off"
        className="w-64 rounded border border-slate-700 bg-slate-950 px-2 py-1 font-mono text-xs text-slate-100 placeholder:text-slate-600"
      />
      {open && (
        <div
          id={listId}
          role="listbox"
          // Keep focus in the input while clicking an option, so onBlur does not close the list
          // before the click lands.
          onMouseDown={(e) => e.preventDefault()}
          className="absolute left-0 top-[calc(100%+4px)] z-20 max-h-80 w-[min(25rem,calc(100vw-2rem))] overflow-y-auto rounded-md border border-slate-700 bg-slate-900 shadow-2xl"
        >
          <SectionHeader>Today&apos;s watchlist</SectionHeader>
          {watchlistNote !== null ? (
            <div className="px-3 py-2 text-xs text-slate-500">{watchlistNote}</div>
          ) : shownIdeas.length === 0 ? (
            <div className="px-3 py-2 text-xs text-slate-500">
              No watchlist idea matches “{value.trim()}”.
            </div>
          ) : (
            shownIdeas.map((idea, i) => (
              <button
                key={`${idea.ticker}${idea.right}${idea.strike}`}
                id={optionId(i)}
                type="button"
                role="option"
                aria-selected={active === i}
                onClick={() => choose({ kind: "idea", idea })}
                className={`grid w-full grid-cols-[3.5rem_1fr_auto] items-center gap-2 border-b border-slate-800 px-3 py-1.5 text-left text-xs ${
                  active === i ? "bg-slate-800" : "hover:bg-slate-800/60"
                }`}
              >
                <span className="font-semibold text-slate-100">{idea.ticker}</span>
                <span className="tabular-nums text-slate-400">{ideaLabel(idea)}</span>
                <IdeaStatusPill idea={idea} />
              </button>
            ))
          )}
          {shownRecent.length > 0 && (
            <>
              <SectionHeader>Recent signals</SectionHeader>
              {shownRecent.map((c, j) => {
                const i = shownIdeas.length + j;
                return (
                  <button
                    key={c.occ}
                    id={optionId(i)}
                    type="button"
                    role="option"
                    aria-selected={active === i}
                    onClick={() => choose({ kind: "recent", contract: c })}
                    className={`flex w-full items-center justify-between gap-3 border-b border-slate-800 px-3 py-1.5 text-left text-xs ${
                      active === i ? "bg-slate-800" : "hover:bg-slate-800/60"
                    }`}
                  >
                    <span className="font-mono text-slate-200">{c.occ.replace(/\s+/g, " ")}</span>
                    <span className="tabular-nums text-slate-500">{recentLabel(c)}</span>
                  </button>
                );
              })}
            </>
          )}
        </div>
      )}
    </div>
  );
}

function SectionHeader({ children }: { children: React.ReactNode }) {
  return (
    <div className="border-b border-slate-800 px-3 py-1.5 text-[10px] uppercase tracking-wider text-slate-500">
      {children}
    </div>
  );
}
