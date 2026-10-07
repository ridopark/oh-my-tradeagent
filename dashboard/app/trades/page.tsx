import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { DataTable } from "@/components/DataTable";
import { ContractLink, contractCell } from "@/components/ContractLink";
import { LocalTime } from "@/components/LocalTime";
import { Pnl, fmtCurrency, pnlCell, priceCell } from "@/components/Pnl";
import { getTrades, tradeFill, tradePnl } from "@/lib/bff";

export const dynamic = "force-dynamic";

export default async function TradesPage() {
  const session = await auth();
  const data = await getTrades();
  // Flatten each event's audit `subject` into its own columns (contract / qty / fill price) so the
  // table answers "what did we trade, how many, at what price" without the operator reading JSON.
  // `subject` is kept as the last column: it still carries the broker_order_id and, on an exit, the
  // remaining qty after the fill.
  const rows = data.items.map((t) => ({
    event_id: t.event_id,
    occurred_at: t.occurred_at,
    kind: t.kind,
    strategy_id: t.strategy_id,
    ...tradeFill(t),
    // Exits only (null → "—" on an entry): an entry makes nothing, it IS the basis the exit is
    // valued against. Same rule as the /live Recent-trades strip.
    pnl: tradePnl(t),
    subject: t.subject,
  }));
  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-100">Trades</h1>
        <p className="mb-4 text-sm text-slate-400">
          Confirmed entry and partial-exit fills, newest first.
        </p>
        {/* Two layouts, one data set (as /live Holdings): the 8-column table scrolls sideways on a
            phone, leaving only Time / Kind / Strategy on screen, so narrow widths get cards that
            lead with the contract, the fill and what it made. Both are server-rendered. */}
        <div className="flex flex-col gap-2 lg:hidden">
          {rows.length === 0 ? (
            <p className="text-sm text-slate-400">No fills yet.</p>
          ) : (
            rows.map((r) => <TradeCard key={r.event_id} row={r} />)
          )}
        </div>
        <div className="hidden lg:block">
          <DataTable
            empty="No fills yet."
            columns={[
              { key: "occurred_at", label: "Time" },
              { key: "kind", label: "Kind" },
              { key: "strategy_id", label: "Strategy" },
              { key: "option_symbol", label: "Contract", render: contractCell },
              { key: "qty", label: "Qty" },
              { key: "avg_fill_price", label: "Fill price", render: priceCell },
              { key: "pnl", label: "P&L", render: pnlCell },
              { key: "subject", label: "Detail" },
            ]}
            rows={rows}
          />
        </div>
      </main>
    </>
  );
}

type TradeRow = {
  occurred_at: string;
  kind: string;
  strategy_id: string;
  option_symbol: string | null;
  qty: number | null;
  avg_fill_price: number | null;
  pnl: number | null;
};

function TradeCard({ row }: { row: TradeRow }) {
  return (
    <div className="rounded border border-slate-800 bg-slate-900 px-3 py-2 text-sm">
      <div className="flex items-baseline justify-between gap-2">
        <span className="min-w-0">
          {row.option_symbol ? (
            <ContractLink occ={row.option_symbol} compact stack={false} />
          ) : (
            <span className="text-slate-500">—</span>
          )}
          <span className="ml-2 text-slate-200">
            {row.qty !== null && `×${row.qty}`}
            {row.avg_fill_price !== null &&
              ` @ ${fmtCurrency(row.avg_fill_price)}`}
          </span>
        </span>
        <span className="shrink-0 font-medium">
          <Pnl value={row.pnl} />
        </span>
      </div>
      <div className="mt-1 text-xs text-slate-400">
        {row.kind} · {row.strategy_id} · <LocalTime iso={row.occurred_at} />
      </div>
    </div>
  );
}
