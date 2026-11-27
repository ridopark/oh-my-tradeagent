import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { TradesView } from "@/components/views/TradesView";
import { getTrades } from "@/lib/bff";

export const dynamic = "force-dynamic";

export default async function TradesPage() {
  const session = await auth();
  const data = await getTrades();
  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <TradesView items={data.items} />
    </>
  );
}
