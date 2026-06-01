import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { DataTable } from "@/components/DataTable";
import { getTrades } from "@/lib/bff";

export const dynamic = "force-dynamic";

export default async function TradesPage() {
  const session = await auth();
  const data = await getTrades();
  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-800">Trades</h1>
        <p className="mb-4 text-sm text-slate-500">
          Confirmed entry and partial-exit fills, newest first.
        </p>
        <DataTable
          empty="No fills yet."
          columns={[
            { key: "occurred_at", label: "Time" },
            { key: "kind", label: "Kind" },
            { key: "strategy_id", label: "Strategy" },
            { key: "subject", label: "Detail" },
          ]}
          rows={data.items}
        />
      </main>
    </>
  );
}
