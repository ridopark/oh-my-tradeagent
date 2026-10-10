"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { startTransition, useEffect } from "react";

// Catch-all for a page whose server render threw — in practice a BFF read that failed or timed out
// on a page with no degrade path of its own (/live and /status render their own "unavailable"
// panels instead). Without this, Next.js shows its bare "Application error" screen: no navigation,
// so the operator has to hand-type a URL to reach the kill switch on /status.
//
// Nav is a server component (it reads the session), so it cannot render inside this client
// boundary; the links below are the minimum way back.
export default function PageError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  const router = useRouter();
  // Report what actually failed: a browser-side render error never reaches the server log, and a
  // server one is only findable by its digest. Best-effort, so a failed report can never take
  // this fallback page down too.
  useEffect(() => {
    fetch("/api/client-error", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      keepalive: true,
      body: JSON.stringify({
        message: error.message,
        digest: error.digest,
        stack: error.stack,
        path: window.location.pathname,
      }),
    }).catch(() => {});
  }, [error]);
  // reset() alone re-renders the boundary's children from the client cache, i.e. the same failed
  // server render; refresh() re-runs the server read.
  const retry = () =>
    startTransition(() => {
      router.refresh();
      reset();
    });
  return (
    <main className="mx-auto max-w-6xl px-4 py-6">
      <div className="rounded border border-amber-600/60 bg-amber-950/40 px-4 py-3">
        <div className="text-sm font-semibold text-amber-300">
          This page couldn&apos;t load
        </div>
        <div className="mt-1 text-xs text-amber-200/80">
          A dashboard data read failed (the data service may be restarting), so nothing is shown
          rather than stale or partial numbers. Try again in a moment.
          {error.digest && <> Reference: {error.digest}.</>}
        </div>
        <div className="mt-3 flex flex-wrap gap-2 text-sm">
          <button
            type="button"
            onClick={retry}
            className="rounded border border-slate-700 px-3 py-1 text-slate-100 hover:bg-slate-800"
          >
            Try again
          </button>
          <Link
            href="/status"
            className="rounded border border-slate-700 px-3 py-1 text-slate-100 hover:bg-slate-800"
          >
            Status &amp; kill switch
          </Link>
          <Link
            href="/live"
            className="rounded border border-slate-700 px-3 py-1 text-slate-100 hover:bg-slate-800"
          >
            Live
          </Link>
        </div>
      </div>
    </main>
  );
}
