import type { ReactNode } from "react";

// Currency-format a numeric-ish value; "—" for null/undefined/unparseable. Shared by the cell
// renderer and any caller that needs the same formatting for an aggregate stat.
export function fmtCurrency(v: string | number | null | undefined): string {
  if (v === null || v === undefined) {
    return "—";
  }
  const n = typeof v === "string" ? Number(v) : v;
  if (Number.isNaN(n)) {
    return String(v);
  }
  return n.toLocaleString(undefined, { style: "currency", currency: "USD" });
}

// A signed P&L value, currency-formatted and color-coded: green for ≥0, red for <0, muted "—" when
// null. Used for both per-position mark columns and the /status unrealized-P&L stats.
export function Pnl({ value }: { value: string | number | null | undefined }) {
  if (value === null || value === undefined) {
    return <span className="text-slate-500">—</span>;
  }
  const n = typeof value === "string" ? Number(value) : value;
  if (Number.isNaN(n)) {
    return <span className="text-slate-200">{String(value)}</span>;
  }
  const cls = n >= 0 ? "text-emerald-400" : "text-rose-400";
  return <span className={cls}>{fmtCurrency(n)}</span>;
}

// DataTable cell renderer for a signed-P&L column.
export function pnlCell(value: unknown): ReactNode {
  return <Pnl value={value as string | number | null | undefined} />;
}

// DataTable cell renderer for a plain (unsigned) current-price column: currency-formatted, "—" when
// null. Not color-coded — a price is not a gain/loss.
export function priceCell(value: unknown): ReactNode {
  const v = value as string | number | null | undefined;
  if (v === null || v === undefined) {
    return <span className="text-slate-500">—</span>;
  }
  return <span className="text-slate-200">{fmtCurrency(v)}</span>;
}
