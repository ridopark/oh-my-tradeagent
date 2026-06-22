import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { LiveProximity } from "@/components/LiveProximity";

export const dynamic = "force-dynamic";

export default async function LivePage() {
  const session = await auth();
  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-100">Live</h1>
        <p className="mb-4 text-sm text-slate-400">
          Real-time WS-feed liveness, how close each watchlist leg is to firing, and how close each
          armed position is to its stop/target. Polls every few seconds.
        </p>
        <LiveProximity />
      </main>
    </>
  );
}
