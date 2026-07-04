"use client";

import { useState, useTransition } from "react";

// Per-tenant "Retry cleanup" affordance for a PARTIALLY-deleted (residual) tenant in the operator
// admin list. It reuses the page's cleanupResidualAction server action (passed as a prop): on confirm
// it submits a FormData carrying the tenant id and the operator-typed confirm value, and the action
// re-verifies the operator, forwards to the api-gateway residual-cleanup route, and redirects with a
// coarse result. Mirrors DeleteTenantButton's type-to-confirm shape (useTransition + confirm modal).
//
// Dark gate: the page renders this affordance ONLY inside its OPERATOR_TENANT_DELETE_ENABLED guard,
// so the button never appears when the feature is off — the api-gateway route is itself dark (404s)
// until its own flag is on. The remaining client guard, re-enforced server-side, is type-to-confirm:
// the confirm button stays DISABLED until the operator types the EXACT tenant id (case-sensitive).
//
// A residual tenant has NO strategy_config rows (that is what makes it residual), so cleanup only
// removes the leftover idempotent stores (broker_credentials + dashboard rows) — never a live tenant.
export function RetryCleanupButton({
  tenantId,
  action,
}: {
  tenantId: string;
  action: (formData: FormData) => void | Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState("");
  const [pending, startTransition] = useTransition();

  // Case-sensitive, exact match — a fat-fingered id does NOT arm the cleanup.
  const confirmMatches = typed === tenantId;

  function close() {
    setOpen(false);
    setTyped("");
  }

  return (
    <>
      <button
        type="button"
        disabled={pending}
        aria-label={`Retry cleanup for tenant ${tenantId}`}
        onClick={() => {
          if (pending) return;
          setTyped("");
          setOpen(true);
        }}
        className="rounded border border-amber-500/60 bg-amber-600/20 px-2 py-1 text-xs font-medium text-amber-300 transition-colors hover:bg-amber-600/30 disabled:cursor-not-allowed disabled:opacity-50"
      >
        Retry cleanup
      </button>

      {open && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label={`Retry cleanup for tenant ${tenantId}`}
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
        >
          <div className="w-full max-w-md rounded-lg border border-slate-700 bg-slate-900 p-5 shadow-xl">
            <h2 className="text-base font-semibold text-amber-300">
              Retry cleanup
            </h2>
            <p className="mt-2 text-sm text-slate-300">
              This removes the leftover data for{" "}
              <code className="rounded bg-slate-800 px-1 py-0.5 font-mono text-slate-100">
                {tenantId}
              </code>{" "}
              (broker credentials and dashboard members) after a partial delete.
              Its strategy config is already gone; this is safe to retry.
            </p>
            <p className="mt-3 text-sm text-slate-400">
              Type the tenant id to confirm:
            </p>
            <input
              type="text"
              value={typed}
              autoFocus
              spellCheck={false}
              autoComplete="off"
              onChange={(e) => setTyped(e.target.value)}
              placeholder={tenantId}
              aria-label={`Type ${tenantId} to confirm cleanup`}
              className="mt-1 w-full rounded border border-slate-700 bg-slate-950 px-2 py-1.5 font-mono text-sm text-slate-100 outline-none focus:border-amber-500/60"
            />
            <div className="mt-4 flex justify-end gap-2">
              <button
                type="button"
                onClick={close}
                disabled={pending}
                className="rounded border border-slate-600 px-3 py-1 text-xs font-medium text-slate-300 transition-colors hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={!confirmMatches || pending}
                aria-label={`Confirm cleanup for tenant ${tenantId}`}
                onClick={() => {
                  if (!confirmMatches || pending) return;
                  const formData = new FormData();
                  formData.set("tenant_id", tenantId);
                  formData.set("confirm_tenant_id", typed);
                  startTransition(async () => {
                    await action(formData);
                  });
                }}
                className="rounded border border-amber-500/60 bg-amber-600/30 px-3 py-1 text-xs font-medium text-amber-200 transition-colors hover:bg-amber-600/40 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {pending ? "…" : "Retry cleanup"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
