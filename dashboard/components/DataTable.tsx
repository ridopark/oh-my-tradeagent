import type { ReactNode } from "react";

// A table column. An optional `render` lets a cell emit rich content (e.g. a contract link or a
// signed P&L) instead of the default string format — it receives the cell value and the whole row.
export type Column = {
  key: string;
  label: string;
  render?: (value: unknown, row: Record<string, unknown>) => ReactNode;
};

// Minimal read-only table for tabular BFF data. Server-component friendly (no client hooks).
export function DataTable({
  columns,
  rows,
  empty = "No data.",
}: {
  columns: Column[];
  // Accepts any array of row objects (typed BFF interfaces lack an index signature); each cell is
  // read by key via an internal cast.
  rows: readonly unknown[];
  empty?: string;
}) {
  if (rows.length === 0) {
    return <p className="text-sm text-slate-400">{empty}</p>;
  }
  return (
    <div className="overflow-x-auto rounded border border-slate-800 bg-slate-900">
      <table className="min-w-full divide-y divide-slate-800 text-sm">
        <thead className="bg-slate-800/50">
          <tr>
            {columns.map((c) => (
              <th
                key={c.key}
                className="px-3 py-2 text-left font-medium text-slate-400"
              >
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-800">
          {rows.map((row, i) => {
            const r = row as Record<string, unknown>;
            return (
              <tr key={i} className="hover:bg-slate-800/50">
                {columns.map((c) => (
                  <td key={c.key} className="px-3 py-2 text-slate-200">
                    {c.render ? c.render(r[c.key], r) : format(r[c.key])}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function format(v: unknown): string {
  if (v === null || v === undefined) {
    return "—";
  }
  if (typeof v === "object") {
    return JSON.stringify(v);
  }
  return String(v);
}
