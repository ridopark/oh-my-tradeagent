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
        <div className="mb-3 flex items-baseline gap-3">
          <h1 className="text-xl font-semibold text-slate-100">Options Chat</h1>
          {/* Native <details> rather than a client-side toggle: this is a server component, and a
              disclosure widget does not justify shipping JS or turning the page into a client
              island. Collapsed by default — the caveats matter once, the messages matter every
              time, and the chat pane is the reason to be here. */}
          <details className="group text-sm">
            <summary className="cursor-pointer list-none text-slate-500 hover:text-slate-300">
              <span className="underline decoration-dotted underline-offset-4">About this page</span>
            </summary>
            <p className="mt-2 max-w-3xl text-slate-400">
              Read-only mirror of the source Discord room — posting is not supported. Messages and
              images appear as they are posted; nothing is backfilled from before the mirror
              started, and messages mirrored before image support shipped stay text-only.
            </p>
          </details>
        </div>
        <OptionsChat />
      </main>
    </>
  );
}
