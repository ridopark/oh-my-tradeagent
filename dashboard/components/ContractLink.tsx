import type { ReactNode } from "react";

// Renders an OCC option symbol (e.g. "AMZN  260724C00260000", possibly space-padded) as a link to
// its Yahoo Finance quote page. Yahoo keys options on the COMPACT OCC (no spaces), so we strip
// whitespace for the href while showing a single-spaced form for readability.
export function ContractLink({
  occ,
  compact: compactLabel = false,
  stack = true,
}: {
  occ: string;
  /** Render the short "SMCI $50C 11/20" label instead of the full OCC (narrow layouts). */
  compact?: boolean;
  /**
   * Whether a compact label stacks onto two lines. Only meaningful with {@code compact}. The
   * Holdings TABLE needs the stack — the contract column there is narrow enough that one line
   * crowds everything to its right. The mobile CARD does not: the contract sits alone on the
   * header row opposite the quantity, with width to spare, so stacking there just makes the card
   * taller for nothing.
   */
  stack?: boolean;
}) {
  const compact = occ.replace(/\s+/g, "");
  const parts = compactLabel && stack ? occCompactParts(occ) : null;
  const display = compactLabel
    ? occCompact(occ)
    : occ.replace(/\s+/g, " ").trim();
  return (
    <a
      href={`https://finance.yahoo.com/quote/${encodeURIComponent(compact)}`}
      target="_blank"
      rel="noopener noreferrer"
      // The compact label drops the padded OCC's digits, so keep the full symbol reachable on
      // hover — it is what an operator pastes into a ticket or a query.
      title={compactLabel ? occ.replace(/\s+/g, " ").trim() : undefined}
      className="text-sky-400 hover:text-sky-300 hover:underline"
    >
      {/* Compact mode stacks the underlying over the rest ("GOOGL" / "$360C 10/16"): in the narrow
          Holdings contract column the one-line form crowds everything to its right. Only the
          two-line case splits — the activity strips use occCompact() as a STRING mid-sentence
          ("SELL GOOGL $360C 10/16 ×1 @ ..."), where a break would read as a new row. */}
      {parts === null ? (
        display
      ) : (
        <>
          <span className="block">{parts.root}</span>
          {/* nowrap: without it the column shrinks to the root's width and the strike/date wrap
              again, giving THREE lines ("GOOGL" / "$360C" / "10/16") instead of two. */}
          <span className="block whitespace-nowrap">{parts.rest}</span>
        </>
      )}
    </a>
  );
}

// "INTC $125C 10/09" as TWO links, for the /live activity strips: the underlying to the stock's
// Yahoo chart, the strike/expiry to the contract's. An unparseable symbol renders as plain text, as
// the strips did before they linked anything.
export function ContractChartLinks({ occ }: { occ: string }) {
  const parts = occCompactParts(occ);
  if (parts === null) {
    return <>{occCompact(occ)}</>;
  }
  const contract = occ.replace(/\s+/g, "");
  return (
    <>
      <ChartLink symbol={parts.root} title={`${parts.root} chart on Yahoo Finance`}>
        {parts.root}
      </ChartLink>{" "}
      <ChartLink symbol={contract} title={`${contract} chart on Yahoo Finance`}>
        {parts.rest}
      </ChartLink>
    </>
  );
}

function ChartLink({
  symbol,
  title,
  children,
}: {
  symbol: string;
  title: string;
  children: ReactNode;
}) {
  return (
    <a
      href={`https://finance.yahoo.com/quote/${encodeURIComponent(symbol)}/chart/`}
      target="_blank"
      rel="noopener noreferrer"
      title={title}
      className="text-sky-400 hover:text-sky-300 hover:underline"
    >
      {children}
    </a>
  );
}

// DataTable cell renderer for an OCC/contract column.
export function contractCell(value: unknown): ReactNode {
  return typeof value === "string" && value.trim() ? (
    <ContractLink occ={value} />
  ) : (
    <span className="text-slate-500">—</span>
  );
}

// "SMCI  261120C00050000" -> "SMCI $50C 11/20": the 19-char padded OCC is by far the widest cell in
// the Holdings table, and on a phone it alone can force a sideways scroll. Falls back to the
// single-spaced symbol whenever the string is not a padded OCC (a legacy row, or anything
// hand-entered), so an unparseable symbol degrades to today's rendering rather than to "".
export function occCompact(occ: string): string {
  const parts = occCompactParts(occ);
  return parts === null
    ? occ.replace(/\s+/g, " ").trim()
    : `${parts.root} ${parts.rest}`;
}

/**
 * The compact label split at the underlying, so a narrow column can stack it:
 * "GOOGL  261016C00360000" -> { root: "GOOGL", rest: "$360C 10/16" }. Null when the symbol is not a
 * padded OCC, which is the caller's cue to fall back to the raw text.
 */
export function occCompactParts(
  occ: string,
): { root: string; rest: string } | null {
  const m = /^([A-Z]{1,6})(\d{2})(\d{2})(\d{2})([CP])(\d{8})$/.exec(
    occ.replace(/\s+/g, ""),
  );
  if (!m) {
    return null;
  }
  const [, root, , mm, dd, cp, strike8] = m;
  // OCC strikes carry three implied decimals: 00050000 -> 50, 00152500 -> 152.5.
  const strike = Number(strike8) / 1000;
  return { root, rest: `$${strike}${cp} ${mm}/${dd}` };
}

// The OCC's expiry as YYYY-MM-DD, or null when the symbol is not a padded OCC. Used to keep expired
// contracts out of the manual-entry picker: offering one guarantees a failed quote, and the operator
// would have to work out why.
export function occExpiryYmd(occ: string): string | null {
  const m = /^([A-Z]{1,6})(\d{2})(\d{2})(\d{2})([CP])(\d{8})$/.exec(occ.replace(/\s+/g, ""));
  return m ? `20${m[2]}-${m[3]}-${m[4]}` : null;
}
