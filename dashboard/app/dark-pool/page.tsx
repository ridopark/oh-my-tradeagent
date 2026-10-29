import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { DarkpoolSummaryTable } from "@/components/DarkpoolSummaryTable";
import { DarkpoolPrintsFeed } from "@/components/DarkpoolPrintsFeed";

export const dynamic = "force-dynamic";

/**
 * Read-only view of off-exchange (FINRA TRF) prints for the watchlist universe + SPY/QQQ
 * (PLAN-2026-10-29-darkpool-prints). A data product, not a signal: nothing trades on it.
 *
 * The same for every tenant — shared market data. Access is still gated: middleware.ts redirects an
 * unauthenticated request to /signin, and the BFF requires the session's tenant header.
 */
export default async function DarkPoolPage() {
  const session = await auth();
  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-6xl space-y-6 px-4 py-6">
        <div className="flex items-baseline gap-3">
          <h1 className="text-xl font-semibold text-slate-100">Dark pool</h1>
          <details className="group text-sm">
            <summary className="cursor-pointer list-none text-slate-500 hover:text-slate-300">
              <span className="underline decoration-dotted underline-offset-4">About this page</span>
            </summary>
            <p className="mt-2 max-w-3xl text-slate-400">
              Off-exchange prints (FINRA TRF, exchange &quot;D&quot;) — that is dark pools AND
              wholesaler internalization, not dark pools alone. Side is inferred from the NBBO quote
              within 5 s before each print; a print without one is &quot;unknown&quot;, never guessed.
              Research here found no intraday predictive value in this flow: read it as context, not
              as a signal. Prints are kept 90 days, 5-minute bars 400 days.
            </p>
          </details>
        </div>
        <DarkpoolSummaryTable />
        <DarkpoolPrintsFeed />
      </main>
    </>
  );
}
