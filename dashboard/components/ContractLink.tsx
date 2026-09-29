import type { ReactNode } from "react";

// Renders an OCC option symbol (e.g. "AMZN  260724C00260000", possibly space-padded) as a link to
// its Yahoo Finance quote page. Yahoo keys options on the COMPACT OCC (no spaces), so we strip
// whitespace for the href while showing a single-spaced form for readability.
export function ContractLink({
  occ,
  compact: compactLabel = false,
}: {
  occ: string;
  /** Render the short "SMCI $50C 11/20" label instead of the full OCC (narrow layouts). */
  compact?: boolean;
}) {
  const compact = occ.replace(/\s+/g, "");
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
      {display}
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
  const bare = occ.replace(/\s+/g, "");
  const m = /^([A-Z]{1,6})(\d{2})(\d{2})(\d{2})([CP])(\d{8})$/.exec(bare);
  if (!m) {
    return occ.replace(/\s+/g, " ").trim();
  }
  const [, root, , mm, dd, cp, strike8] = m;
  // OCC strikes carry three implied decimals: 00050000 -> 50, 00152500 -> 152.5.
  const strike = Number(strike8) / 1000;
  return `${root} $${strike}${cp} ${mm}/${dd}`;
}

// The OCC's expiry as YYYY-MM-DD, or null when the symbol is not a padded OCC. Used to keep expired
// contracts out of the manual-entry picker: offering one guarantees a failed quote, and the operator
// would have to work out why.
export function occExpiryYmd(occ: string): string | null {
  const m = /^([A-Z]{1,6})(\d{2})(\d{2})(\d{2})([CP])(\d{8})$/.exec(occ.replace(/\s+/g, ""));
  return m ? `20${m[2]}-${m[3]}-${m[4]}` : null;
}
