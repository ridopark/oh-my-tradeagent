"use client";

import { useState, useTransition } from "react";

// Destructive per-tenant "Delete" affordance for the operator admin list. It reuses the page's
// server action (passed as a prop): on confirm it submits a FormData carrying the tenant id and the
// operator-typed confirm value, and the action re-verifies the operator, forwards to the api-gateway
// delete route, and redirects with a coarse result. Mirrors ActivateButton's shape (useTransition +
// dark-launch gate) with an added type-to-confirm modal — deleting a tenant is irreversible.
//
// Dark gate: the page renders this affordance ONLY inside its OPERATOR_TENANT_DELETE_ENABLED guard,
// so the button never appears when the feature is off — the api-gateway route is itself dark (404s)
// until its own flag is on. The remaining client guard, re-enforced server-side, is type-to-confirm:
// the "Delete tenant" confirm button stays DISABLED until the operator types the EXACT tenant id
// (case-sensitive, exact match).
//
// Honest-disabled: when a tenant is NOT deletable (live / multi-strategy / active), the page passes a
// `disabledReason` and the trigger renders DISABLED with that reason as its tooltip — instead of
// silently offering an action that would 409 server-side. The type-to-confirm modal opens only when
// the tenant is deletable (no reason supplied).
export function DeleteTenantButton({
  tenantId,
  disabledReason,
  action,
}: {
  tenantId: string;
  disabledReason?: string;
  action: (formData: FormData) => void | Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState("");
  const [pending, startTransition] = useTransition();

  // Case-sensitive, exact match — the whole point of the confirm is that a fat-fingered id does NOT
  // arm the delete.
  const confirmMatches = typed === tenantId;
  const triggerDisabled = pending || Boolean(disabledReason);

  function close() {
    setOpen(false);
    setTyped("");
  }

  return (
    <>
      <button
        type="button"
        disabled={triggerDisabled}
        aria-label={`Delete tenant ${tenantId}`}
        title={disabledReason ? `Cannot delete — ${disabledReason}` : undefined}
        onClick={() => {
          if (triggerDisabled) return;
          setTyped("");
          setOpen(true);
        }}
        className="rounded border border-red-500/60 bg-red-600/20 px-2 py-1 text-xs font-medium text-red-300 transition-colors hover:bg-red-600/30 disabled:cursor-not-allowed disabled:opacity-50"
      >
        Delete tenant
      </button>

      {open && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label={`Delete tenant ${tenantId}`}
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
        >
          <div className="w-full max-w-md rounded-lg border border-slate-700 bg-slate-900 p-5 shadow-xl">
            <h2 className="text-base font-semibold text-red-300">
              Delete tenant
            </h2>
            <p className="mt-2 text-sm text-slate-300">
              This permanently removes{" "}
              <code className="rounded bg-slate-800 px-1 py-0.5 font-mono text-slate-100">
                {tenantId}
              </code>{" "}
              and all of its stored data. This cannot be undone.
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
              aria-label={`Type ${tenantId} to confirm deletion`}
              className="mt-1 w-full rounded border border-slate-700 bg-slate-950 px-2 py-1.5 font-mono text-sm text-slate-100 outline-none focus:border-red-500/60"
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
                aria-label={`Confirm delete tenant ${tenantId}`}
                onClick={() => {
                  if (!confirmMatches || pending) return;
                  const formData = new FormData();
                  formData.set("tenant_id", tenantId);
                  formData.set("confirm_tenant_id", typed);
                  startTransition(async () => {
                    await action(formData);
                  });
                }}
                className="rounded border border-red-500/60 bg-red-600/30 px-3 py-1 text-xs font-medium text-red-200 transition-colors hover:bg-red-600/40 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {pending ? "…" : "Delete tenant"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
