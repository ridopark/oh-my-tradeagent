import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { PositionsView } from "@/components/views/PositionsView";
import { getPortfolio } from "@/lib/bff";

export const dynamic = "force-dynamic";

export default async function PositionsPage() {
  const session = await auth();
  // Sourced from /api/portfolio (not /api/positions) so each row carries its live broker marks
  // (current price + today's/total unrealized P&L), joined by OCC in the BFF.
  const p = await getPortfolio();
  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <PositionsView p={p} />
    </>
  );
}
