import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { DataTable } from "@/components/DataTable";
import { getPositions } from "@/lib/bff";

export const dynamic = "force-dynamic";

export default async function PositionsPage() {
  const session = await auth();
  const data = await getPositions();
  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-800">Open Positions</h1>
        <p className="mb-4 text-sm text-slate-500">
          {data.count} open position{data.count === 1 ? "" : "s"} across your strategies. Notional is
          cost basis at entry (not live mark).
        </p>
        <DataTable
          empty="No open positions."
          columns={[
            { key: "strategy_id", label: "Strategy" },
            { key: "contract_symbol", label: "Contract" },
            { key: "remaining_qty", label: "Qty" },
            { key: "entry_premium", label: "Entry premium" },
            { key: "open_notional", label: "Open notional" },
          ]}
          rows={data.items}
        />
      </main>
    </>
  );
}
