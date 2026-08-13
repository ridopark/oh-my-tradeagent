import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { OptionsChat } from "@/components/OptionsChat";

export const dynamic = "force-dynamic";

/**
 * Read-only mirror of a third-party Discord room (PLAN-2026-08-12).
 *
 * The feed is the SAME for every tenant — it is one shared room, not per-tenant trading data.
 * Access is still gated: middleware.ts redirects an unauthenticated request to /signin before this
 * renders, and the BFF requires the session's tenant header regardless.
 */
export default async function OptionsChatPage() {
  const session = await auth();
  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-100">Options Chat</h1>
        <p className="mb-4 text-sm text-slate-400">
          Read-only mirror of the source Discord room. Posting is not supported. Messages appear as
          they are posted — nothing is backfilled from before the mirror started, and images are
          stored separately (shown once media mirroring is enabled).
        </p>
        <OptionsChat />
      </main>
    </>
  );
}
