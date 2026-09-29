import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { DataTable } from "@/components/DataTable";
import { contractCell } from "@/components/ContractLink";
import { priceCell } from "@/components/Pnl";
import { getTrades, tradeFill } from "@/lib/bff";

export const dynamic = "force-dynamic";

export default async function TradesPage() {
  const session = await auth();
  const data = await getTrades();
  // Flatten each event's audit `subject` into its own columns (contract / qty / fill price) so the
  // table answers "what did we trade, how many, at what price" without the operator reading JSON.
  // `subject` is kept as the last column: it still carries the broker_order_id and, on an exit, the
  // remaining qty after the fill.
  const rows = data.items.map((t) => ({
    occurred_at: t.occurred_at,
    kind: t.kind,
    strategy_id: t.strategy_id,
    ...tradeFill(t),
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
        <DataTable
          empty="No fills yet."
          columns={[
            { key: "occurred_at", label: "Time" },
            { key: "kind", label: "Kind" },
            { key: "strategy_id", label: "Strategy" },
            { key: "option_symbol", label: "Contract", render: contractCell },
            { key: "qty", label: "Qty" },
            { key: "avg_fill_price", label: "Fill price", render: priceCell },
            { key: "subject", label: "Detail" },
          ]}
          rows={rows}
        />
      </main>
    </>
  );
}
