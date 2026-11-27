import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { PortfolioView } from "@/components/views/PortfolioView";
import { getPortfolio } from "@/lib/bff";

export const dynamic = "force-dynamic";

export default async function PortfolioPage() {
  const session = await auth();
  const p = await getPortfolio();
  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <PortfolioView p={p} />
    </>
  );
}
