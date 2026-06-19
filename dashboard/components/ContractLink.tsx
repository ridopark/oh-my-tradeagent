import type { ReactNode } from "react";

// Renders an OCC option symbol (e.g. "AMZN  260724C00260000", possibly space-padded) as a link to
// its Yahoo Finance quote page. Yahoo keys options on the COMPACT OCC (no spaces), so we strip
// whitespace for the href while showing a single-spaced form for readability.
export function ContractLink({ occ }: { occ: string }) {
  const compact = occ.replace(/\s+/g, "");
  const display = occ.replace(/\s+/g, " ").trim();
  return (
    <a
      href={`https://finance.yahoo.com/quote/${encodeURIComponent(compact)}`}
      target="_blank"
      rel="noopener noreferrer"
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
